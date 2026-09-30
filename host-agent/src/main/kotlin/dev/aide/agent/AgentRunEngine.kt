package dev.aide.agent

import dev.aide.agent.llm.LlmCallException
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.code
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.prompt.PlanFormatException
import dev.aide.agent.prompt.StepPrompt
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.agent.provider.ModelUnavailableException
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.code
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.channels.ReceiveChannel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

/** Режим задачи, если он не был передан: безопасный по умолчанию (FR-AGENT-2). */
private val DEFAULT_MODE = AutonomyMode.ASK_BEFORE_CHANGES

/** Ограничение длины названия задачи: карточка показывает одну строку. */
private const val TITLE_LIMIT = 80

/** Причина отмены прогона по команде пользователя. */
private const val STOP_MESSAGE = "Прогон остановлен пользователем"

/**
 * Код причины отказа: понятную строку строит UI из ресурсов (NFR-13).
 *
 * Неожиданные ошибки дают код [RunInterruptReason.UNEXPECTED], а не имя JVM-класса:
 * словарь кодов должен оставаться замкнутым — по проводу и в базе едет код, который
 * UI умеет показать, а имя класса и сообщение уходят только в журнал.
 */
private fun reasonOf(error: Throwable): String = when (error) {
    is LlmCallException -> error.error.kind.code
    // Отказ выбрать модель (T-1.56) — это код, а не текст: у «модель не настроена»,
    // «нет переменной с ключом» и «протокол не поддержан» свои коды, и все они
    // понятны UI без разбора строк (NFR-13).
    is ModelUnavailableException -> error.failure.code
    is PlanFormatException -> RunInterruptReason.PLAN_UNREADABLE
    else -> RunInterruptReason.UNEXPECTED
}

/**
 * Цикл прогона агента (T-1.1).
 *
 * Движок даёт механику: очередь, переходы состояния прогона и статуса задачи, сохранение,
 * событие о смене. Прогон живёт в корутине хоста, а не в обработчике запроса: `postTask`
 * отвечает сразу, дальше состояние идёт событиями (решение 1). Один прогон за раз, все
 * переходы под замком, событие — после записи (О-8).
 *
 * Модель приходит [ModelProvider], а не готовым клиентом (T-1.56): движок берёт её
 * **один раз на старте прогона**, записывает алиас в `AgentRun.modelAlias` и дальше
 * работает выбранным клиентом. Поэтому смена модели в настройках не меняет правила
 * идущего прогона (FR-AGENT-5), а отказ выбора — обычное состояние с кодом причины.
 *
 * @param clock источник времени; подменяется в тестах.
 */
class AgentRunEngine(
    private val runs: RunRepository,
    private val tasks: TaskRepository,
    private val models: ModelProvider,
    private val planner: RunPlanner,
    private val events: AgentEventSink,
    private val clock: () -> Instant = Clock.System::now,
) {

    private val logger = LoggerFactory.getLogger(AgentRunEngine::class.java)

    private val queue = RunQueue(tasks)
    private val writer = RunStateWriter(runs, tasks, events, clock, Mutex())
    private val controls = ConcurrentHashMap<RunId, RunControl>()
    private val pendingModes = ConcurrentHashMap<TaskId, AutonomyMode>()

    /** Сигнал воркеру хоста: в очереди появилась задача. */
    private val wakeUp = Channel<Unit>(Channel.CONFLATED)

    /** Канал, по которому воркер хоста узнаёт о новой задаче. */
    val taskWakeUp: ReceiveChannel<Unit> = wakeUp

    /**
     * Ставит задачу в очередь; прогон создаётся, когда план готов (решение 2).
     *
     * Статус задачи пишется в базу и рассылается событием — иначе постановка на чистой
     * установке выглядела бы успешной, а отказ планирования пользователь бы не увидел.
     */
    suspend fun postTask(prompt: String, mode: AutonomyMode): TaskId {
        val id = TaskId(UUID.randomUUID().toString())
        writer.persistTask(
            Task(
                id = id,
                title = taskTitle(prompt),
                prompt = prompt,
                branch = "ai/${id.value}",
                status = TaskStatus.QUEUED,
                createdAt = clock(),
            ),
        )
        pendingModes[id] = mode
        wakeUp.trySend(Unit)
        return id
    }

    /**
     * Обрабатывает одну задачу из очереди.
     *
     * @return false, если очередь пуста. Вызывается воркером хоста — единственным
     *   местом, где прогоны запускаются.
     */
    suspend fun processNext(): Boolean {
        val task = queue.next()
        if (task != null) executeOrFail(task)
        return task != null
    }

    /**
     * Ведёт одну задачу: статус, выбор модели, план, прогон.
     *
     * Модель выбирается до планирования: прогон создаётся, когда план готов (решение 2),
     * и алиас модели обязан попасть в уже созданный прогон. Отказ на любом из шагов
     * виден на задаче, потому что прогона в этот момент ещё не существует.
     */
    private suspend fun executeOrFail(task: Task) {
        val running = task.copy(status = TaskStatus.RUNNING)
        writer.persistTask(running)
        // Режим снимается до планирования: при отказе планирования запись не должна
        // оставаться в памяти до конца жизни хоста.
        val mode = pendingModes.remove(task.id) ?: DEFAULT_MODE
        val model = modelOrFail(task)
        if (model != null) {
            val plan = planOrFail(running, model)
            if (plan != null) executeRun(running, plan, mode, model)
        }
    }

    /**
     * Берёт модель для прогона; при отказе помечает задачу кодом причины, а не молчит.
     *
     * Отказ — нормальное состояние: на чистой установке провайдер не настроен, у настроенного
     * может не быть переменной окружения с ключом. Задача падает тем же кодом, что и раньше
     * (`NOT_CONFIGURED`), и пользователь видит причину в строке задачи.
     */
    private suspend fun modelOrFail(task: Task): ConfiguredModel? =
        models.current().getOrElse { error ->
            val reason = reasonOf(error)
            logger.warn("Задача ${task.id.value} не начала прогон ($reason): ${error.message}", error)
            writer.failTask(task.id, reason)
            null
        }

    /** Пауза, продолжение или стоп; неизвестный прогон — ошибка, а не молчание. */
    fun control(runId: RunId, command: RunCommand) {
        val control = controls[runId] ?: throw UnknownRunException(runId)
        control.request(command)
    }

    /**
     * Планирует задачу; при отказе помечает её провайдером причины, а не молчит.
     *
     * Ловится любая ошибка клиента модели: `LlmClient` не запрещает исключения, а
     * настоящий транспорт (T-1.56) их и бросает. Отмена хоста не превращается в отказ
     * задачи — её проверяет `ensureActive`.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun planOrFail(task: Task, model: ConfiguredModel): List<PlanStep>? = try {
        planner.plan(task, model.client)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        val reason = reasonOf(error)
        logger.warn("Задача ${task.id.value} не запланирована ($reason): ${error.message}", error)
        writer.failTask(task.id, reason)
        null
    }

    /**
     * Создаёт прогон и выполняет его в отдельной корутине.
     *
     * Отдельная корутина, а не тело воркера: стоп отменяет именно её, и следующий
     * прогон в очереди от отмены не страдает. `join` не пробрасывает отмену наружу,
     * поэтому остановленный прогон не завершает цикл воркера.
     */
    private suspend fun executeRun(
        task: Task,
        plan: List<PlanStep>,
        mode: AutonomyMode,
        model: ConfiguredModel,
    ) {
        val run = AgentRun(
            id = RunId(UUID.randomUUID().toString()),
            taskId = task.id,
            state = RunState.PLANNED,
            mode = mode,
            plan = plan,
            startedAt = clock(),
            modelAlias = model.alias,
        )
        val control = RunControl()
        controls[run.id] = control
        try {
            coroutineScope {
                val job = launch { execute(task, writer.persist(run), control, model.client) }
                control.attach(job)
                job.join()
            }
        } finally {
            controls.remove(run.id)
        }
    }

    /**
     * Виток цикла: шаги, точки проверки, завершение.
     *
     * Отмена пользователя отличается от отмены хоста флагом [RunControl.stopRequested]:
     * остановленный прогон финализируется как STOPPED, а отменённый хостом остаётся
     * RUNNING в базе и помечается прерванным при следующем старте (решение 8).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(task: Task, start: AgentRun, control: RunControl, llm: LlmClient) {
        var current = writer.persist(start.copy(state = RunState.RUNNING))
        try {
            for (step in start.plan) {
                current = checkpoint(current, control)
                current = applyStep(task, current, step, llm)
                current = checkpoint(current, control)
            }
        } catch (cancel: CancellationException) {
            if (!control.stopRequested) throw cancel
            // Запись в уже отменённой корутине: без NonCancellable состояние осталось
            // бы RUNNING навсегда, и следующий старт пометил бы прогон прерванным,
            // а не «остановлен пользователем» (решение 6).
            withContext(NonCancellable) { writer.stop(current) }
            return
        } catch (error: Exception) {
            val reason = reasonOf(error)
            logger.warn("Прогон ${start.id.value} завершился ошибкой ($reason): ${error.message}", error)
            writer.fail(current, reason)
            return
        }
        // Терминальная запись вне try: решение о завершении уже принято, и сбой доставки
        // события не должен отменять его — иначе успешный прогон можно было бы подменить
        // отказом (fail перевёл бы задачу REVIEW обратно в FAILED). Запись идёт раньше
        // рассылки (О-8), поэтому исключение из неё оставляет корректный итог в базе.
        writer.finish(current)
    }

    /** Точка проверки: пауза переводит прогон в PAUSED и ждёт продолжения, стоп отменяет цикл. */
    private suspend fun checkpoint(run: AgentRun, control: RunControl): AgentRun {
        val command = if (control.stopRequested) RunCommand.STOP else control.take()
        if (command == RunCommand.STOP) throw CancellationException(STOP_MESSAGE)
        if (command != RunCommand.PAUSE) return run
        val paused = writer.persist(run.copy(state = RunState.PAUSED))
        // Из паузы выводит только RESUME: повторная PAUSE означает «уже на паузе» и не
        // должна ни продолжаться, ни теряться молча.
        var resume = control.awaitCommand()
        while (resume == RunCommand.PAUSE) {
            logger.info("Прогон ${run.id.value} уже на паузе: повторная PAUSE пропущена")
            resume = control.awaitCommand()
        }
        if (control.stopRequested || resume == RunCommand.STOP) throw CancellationException(STOP_MESSAGE)
        return writer.persist(paused.copy(state = RunState.RUNNING))
    }

    /** Один шаг — один вызов модели; ошибка провайдера поднимается наружу как [LlmCallException]. */
    private suspend fun applyStep(
        task: Task,
        run: AgentRun,
        step: PlanStep,
        llm: LlmClient,
    ): AgentRun = when (val response = llm.complete(StepPrompt.request(task, step, run.plan))) {
        is LlmResponse.Text -> writer.recordStep(run, step, response)
        is LlmResponse.Error -> throw LlmCallException(response)
    }
}

/** Название задачи по её постановке: первая непустая строка, не длиннее [TITLE_LIMIT]. */
private fun taskTitle(prompt: String): String {
    val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= TITLE_LIMIT) line else line.take(TITLE_LIMIT)
}
