package dev.aide.agent

import dev.aide.agent.llm.LlmCallException
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.code
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.prompt.PlanFormatException
import dev.aide.agent.prompt.StepPrompt
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.agent.provider.ModelUnavailableException
import dev.aide.agent.tools.StepTools
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
 * Сколько витков «модель просит инструмент → инструмент отвечает» допускается в шаге.
 *
 * Число с причиной: шаг — это несколько чтений и поисков, а модель, зациклившаяся на
 * вызовах (ищет одно и то же, зовёт несуществующий файл), сама не остановится. Предел
 * конечен, потому что за витками стоят токены пользователя и время прогона; двенадцати
 * витков хватает на осмысленный шаг, а исчерпание — видимый отказ, а не тишина.
 */
internal const val MAX_STEP_TURNS: Int = 12

/** Шаг исчерпал лимит вызовов инструментов: прогон падает кодом, а не крутится дальше. */
private class StepToolLimitException(turns: Int) : Exception(
    "шаг запросил инструменты $turns раз подряд: предел в $MAX_STEP_TURNS витков исчерпан",
)

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
    is StepToolLimitException -> RunInterruptReason.TOOL_LOOP_LIMIT
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
 * Ветку задачи, снапшот и отложенные правки движок получает из [RepositoryPorts]. Незакоммиченные
 * правки человека откладываются до `checkout` (T-1.59), затем ставится ветка задачи и до
 * планирования (T-1.18): планирование читает репозиторий, и читать его надо уже в той ветке,
 * где агент будет работать. Снапшот ставится там же (T-1.19) — это точка отсчёта для отката;
 * пустой репозиторий снапшота не даёт, и это не мешает прогону: без коммитов читать и планировать
 * можно. После прогона, каким бы исходом он ни завершился, правки возвращаются на свою ветку.
 *
 * @param clock источник времени; подменяется в тестах.
 */
class AgentRunEngine(
    private val ports: RunPorts,
    private val models: ModelProvider,
    private val planner: RunPlanner,
    private val tools: StepTools,
    private val repositories: RepositoryPorts,
    private val clock: () -> Instant = Clock.System::now,
) {

    private val logger = LoggerFactory.getLogger(AgentRunEngine::class.java)

    private val queue = RunQueue(ports.tasks)
    private val writer = RunStateWriter(ports.runs, ports.tasks, ports.events, clock, Mutex())
    private val placement = TaskBranchPlacement(repositories.branches, writer)
    private val runSnapshots = RunSnapshots(repositories.snapshots, writer)
    private val workStash = TaskWorkStash(repositories.workStash, ports.tasks, writer)
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
     * Ведёт одну задачу: статус, выбор модели, отложенные правки, ветка, план, прогон.
     *
     * Модель выбирается до планирования: прогон создаётся, когда план готов (решение 2),
     * и алиас модели обязан попасть в уже созданный прогон. Отказ на любом из шагов
     * виден на задаче, потому что прогона в этот момент ещё не существует.
     *
     * Порядок «модель, потом правки, потом ветка» осознан: если прогон не может начаться
     * из-за выбранной модели, репозиторий пользователя не переключается — незачем менять
     * его состояние ради прогона, которого не будет. Правки откладываются до `checkout`
     * ветки задачи, а возвращаются в [finally] — после любого исхода прогона (T-1.59).
     */
    private suspend fun executeOrFail(task: Task) {
        val running = task.copy(status = TaskStatus.RUNNING)
        writer.persistTask(running)
        // Режим снимается до планирования: при отказе планирования запись не должна
        // оставаться в памяти до конца жизни хоста.
        val mode = pendingModes.remove(task.id) ?: DEFAULT_MODE
        val model = modelOrFail(task)
        // Откладывать правки незачем, если прогон всё равно не начнётся: порядок «модель,
        // потом правки» не трогает репозиторий ради прогона, которого не будет.
        val stashed = if (model == null) null else workStash.beforeRun(running)
        if (model == null || stashed == null) return
        try {
            val placed = placement.place(stashed)
            val plan = if (placed == null) null else planOrFail(placed, model)
            if (placed != null && plan != null) executeRun(placed, plan, mode, model)
        } finally {
            // Возврат в NonCancellable: остановка и отмена хоста не должны оставить правки
            // отложенными, иначе для человека прогон выглядит как их потеря (T-1.59).
            withContext(NonCancellable) { workStash.afterRun(task.id) }
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
     *
     * Снапшот ставится здесь, до первого шага (T-1.19): поставить его после первой правки
     * агента значило бы откатывать к уже испорченному состоянию. Отказ снапшота завершает
     * прогон отказом — обещание «перед изменением есть снапшот» не должно становиться пустым.
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
            val start = runSnapshots.beforeRun(writer.persist(run)) ?: return
            coroutineScope {
                val job = launch { execute(task, start, control, model.client) }
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

    /**
     * Шаг — цикл «модель просит инструмент, инструмент отвечает» (T-1.7).
     *
     * Пока модель просит инструменты, диалог продолжается: её ответ с вызовами и
     * результаты вызовов дописываются к репликам, и запрос уходит снова. Витки
     * ограничены [MAX_STEP_TURNS]: зациклившаяся на вызовах модель иначе жгла бы
     * токены и время пользователя без конца. Исчерпание предела — отказ прогона
     * кодом `tool_loop_limit`, а не молчаливое завершение шага: шаг, брошенный
     * на середине, выполненным считать нельзя.
     *
     * Стоимость и время каждого вызова накапливаются сразу, поэтому отказ на середине
     * шага не прячет уже потраченное (FR-COST-2), а ошибка провайдера поднимается
     * наружу как [LlmCallException].
     */
    private suspend fun applyStep(
        task: Task,
        run: AgentRun,
        step: PlanStep,
        llm: LlmClient,
    ): AgentRun {
        var current = run
        var messages = StepPrompt.request(task, step, run.plan, tools.definitions).messages
        var turns = 0
        while (true) {
            val response = when (val answer = llm.complete(LlmRequest(messages, tools.definitions))) {
                is LlmResponse.Text -> answer
                is LlmResponse.Error -> throw LlmCallException(answer)
            }
            current = writer.accumulate(current, response)
            if (response.toolCalls.isEmpty()) return writer.recordStep(current, step)
            turns += 1
            if (turns > MAX_STEP_TURNS) throw StepToolLimitException(turns)
            // Вызовы выполняются по порядку (map, а не параллельный запуск): инструменты
            // ходят на диск, и цена ошибки, когда порядок результатов перестаёт совпадать
            // с порядком вызовов в ответе модели, больше выигрыша от гонки трёх чтений.
            // Точку отката, которую поставил изменяющий вызов, дописывает писатель состояния
            // (T-1.8): движок остаётся единственным, кто решает, что попадает в прогон.
            val results = response.toolCalls.map { call ->
                val result = tools.invoke(run.id, call)
                current = writer.snapshot(current, result.snapshotRef)
                LlmMessage.tool(call.id, result.text)
            }
            messages = messages + LlmMessage.assistant(response.text, response.toolCalls) + results
        }
    }
}

/** Название задачи по её постановке: первая непустая строка, не длиннее [TITLE_LIMIT]. */
private fun taskTitle(prompt: String): String {
    val line = prompt.lineSequence().firstOrNull { it.isNotBlank() }?.trim().orEmpty()
    return if (line.length <= TITLE_LIMIT) line else line.take(TITLE_LIMIT)
}
