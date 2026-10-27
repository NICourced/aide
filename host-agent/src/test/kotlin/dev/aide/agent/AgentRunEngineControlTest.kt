package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.agent.ports.WorkspaceRestoreOutcome
import dev.aide.agent.tools.StepTools
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.SnapshotRef
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant

/**
 * T-1.4 в движке: пауза внутри шага, восстановление дерева при стопе и обрезанный ответ модели.
 *
 * Проверки идут на фейковых портах и скриптованной модели (О-11): настоящий git здесь
 * ни к чему — порядок и коды причин проверяются тестами `JGitRepository` и сквозным
 * `AgentRunProtocolTest`, а сам репозиторий движку неведом (О-1).
 */
class AgentRunEngineControlTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)

    /** Порт возврата дерева: по умолчанию «восстановлено», исход задаёт тест. */
    private val restore = FakeRestore()

    /**
     * Инструменты шага: настоящие, как в хосте. Пауза внутри шага проверяется на шаге,
     * который действительно зовёт инструмент, — иначе проверялось бы отсутствие вызовов.
     */
    private val tools = stepTools()

    private fun engine(
        plan: List<PlanStep> = plan("шаг"),
        llm: LlmClient = textModel(),
        snapshots: Snapshots = Snapshots { TaskSnapshot.Created(SnapshotRef(ENGINE_SNAPSHOT_REF)) },
        tools: StepTools = this.tools,
        runs: RunRepository = this.runs,
    ): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(llm),
        planner = RunPlanner { _, _, _ -> plan },
        tools = tools,
        repositories = RepositoryPorts(branchAlreadyExists, snapshots, noWorkStash, restore),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    /**
     * Пауза, запрошенная между витками шага, встаёт на границе витка.
     *
     * Состояние шага (диалог, счётчик витков, признак изменения) живёт в локальных переменных
     * корутины, поэтому продолжение идёт с того же витка: модель не спрашивается заново, и шаг
     * не переделывается. Без точки проверки внутри шага пауза ждала бы конца шага — а шаг это
     * до двенадцати витков с инструментами.
     */
    @Test
    fun `пауза между витками шага встаёт в paused, и шаг не переделывается`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(toolCall(), text("готово"))) { index -> if (index == 0) gate.await() }
            val engine = engine(plan("раз", "два"), llm)
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            val runId = sink.events.first().id
            // Пауза уходит, пока модель ещё отвечает на первый виток шага: движок увидит её
            // на ближайшей границе витка, а не после шага.
            engine.control(runId, RunCommand.PAUSE)
            gate.complete(Unit)

            awaitState(runId, RunState.PAUSED)
            val paused = assertNotNull(runs.load(runId))
            assertEquals(
                StepStatus.PENDING,
                paused.plan[0].status,
                "пауза встала внутри шага, а не после него: шаг ещё не выполнен",
            )
            assertEquals(1, llm.callCount, "второй виток шага до продолжения не начат")

            engine.control(runId, RunCommand.RESUME)
            awaitCompletion(worker)

            val finished = assertNotNull(runs.load(runId))
            assertEquals(RunState.FINISHED, finished.state)
            assertEquals(StepStatus.DONE, finished.plan[0].status, "после продолжения шаг доведён до конца")
            assertEquals(
                3,
                llm.callCount,
                "продолжение идёт с того же витка: ни один вызов шага не повторён",
            )
        }
    }

    /**
     * Стоп возвращает дерево к последнему снапшоту прогона, и это обычная остановка.
     *
     * Снапшот берётся из записанного прогона, а не из копии в корутине: копия помнит
     * состояние на начало шага, и точка отката изменяющего вызова (T-1.8) в неё не попала бы.
     * Код причины проверяется здесь же: успешное восстановление — это `user_stop`, а не
     * отдельный код (сбой проверяется соседним тестом).
     */
    @Test
    fun `стоп возвращает дерево к последнему снапшоту прогона`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(plan("шаг"), llm)
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            val runId = sink.events.first().id
            engine.control(runId, RunCommand.STOP)
            awaitCompletion(worker)

            assertEquals(
                listOf<SnapshotRef?>(SnapshotRef(ENGINE_SNAPSHOT_REF)),
                restore.asked,
                "восстанавливать надо к последнему снапшоту прогона",
            )
            val run = assertNotNull(runs.load(runId))
            assertEquals(RunState.STOPPED, run.state, "стоп — это STOPPED, а не FAILED")
            assertEquals(
                RunInterruptReason.USER_STOP,
                run.interruptReason,
                "восстановленное дерево — обычная остановка, отдельного кода нет",
            )
            assertEquals(RunInterruptReason.USER_STOP, tasks.load(run.taskId)?.failureReason)
            assertEquals(RunState.STOPPED, sink.events.last().state, "остановка видна событием")
        }
    }

    /**
     * Последний снапшот — тот, что поставлен внутри шага, а не стартовый (T-1.4).
     *
     * Изменяющий вызов записывает точку отката в прогон из глубины шага; копия прогона
     * в корутине её не видит. Поэтому восстановление обязано читать записанную версию:
     * иначе дерево ушло бы к точке начала шага, то есть на одну правку назад.
     */
    @Test
    fun `стоп восстанавливает к снапшоту, поставленному внутри шага`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(writeCall(), text("готово"))) { index -> if (index == 1) gate.await() }
            val engine = engine(plan("шаг"), llm, tools = stepTools(stored = writeAllowed))
            engine.postTask("Поправь файл", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            // Второй виток начат: изменяющий вызов уже прошёл, точка отката записана в прогон.
            llm.awaitCall(1)
            val runId = sink.events.first().id
            engine.control(runId, RunCommand.STOP)
            awaitCompletion(worker)

            val run = assertNotNull(runs.load(runId))
            assertEquals(
                listOf(SnapshotRef(ENGINE_SNAPSHOT_REF), SnapshotRef(CHANGE_SNAPSHOT_REF)),
                run.snapshots,
                "в прогоне обе точки: начала шага и изменяющего вызова",
            )
            assertEquals(
                listOf<SnapshotRef?>(SnapshotRef(CHANGE_SNAPSHOT_REF)),
                restore.asked,
                "восстанавливать надо к последнему снапшоту — тому, что записан внутри шага",
            )
        }
    }

    /** Сбой восстановления не отменяет стоп, но виден кодом: прогон STOPPED с причиной сбоя. */
    @Test
    fun `сбой восстановления не отменяет стоп, а виден отдельным кодом`() {
        runBlocking {
            restore.answer(WorkspaceRestoreOutcome.Refused(RunInterruptReason.RESTORE_FAILED))
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(plan("шаг"), llm)
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            val runId = sink.events.first().id
            engine.control(runId, RunCommand.STOP)
            awaitCompletion(worker)

            val run = assertNotNull(runs.load(runId))
            assertEquals(RunState.STOPPED, run.state, "остановка состоялась — это решение пользователя")
            assertEquals(
                RunInterruptReason.RESTORE_FAILED,
                run.interruptReason,
                "сбой восстановления обязан быть виден, а не проглочен",
            )
            assertEquals(RunInterruptReason.RESTORE_FAILED, tasks.load(run.taskId)?.failureReason)
            assertEquals(RunState.STOPPED, sink.events.last().state, "остановка видна событием, несмотря на сбой")
        }
    }

    /**
     * Исключение порта восстановления не оставляет прогон без итога (T-1.4, решение 5).
     *
     * `writer.stop` обязан выполниться при любом исходе восстановления: иначе сбой порта
     * оставил бы прогон `RUNNING`, и следующий старт хоста пометил бы его прерванным,
     * хотя пользователь нажал «Стоп».
     */
    @Test
    fun `исключение порта восстановления не оставляет прогон running`() {
        runBlocking {
            restore.failure = IllegalStateException("порт недоступен")
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(plan("шаг"), llm)
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            val runId = sink.events.first().id
            engine.control(runId, RunCommand.STOP)
            awaitCompletion(worker)

            val run = assertNotNull(runs.load(runId))
            assertEquals(RunState.STOPPED, run.state, "стоп состоялся, несмотря на сбой порта")
            assertEquals(
                RunInterruptReason.RESTORE_FAILED,
                run.interruptReason,
                "исключение становится кодом причины, а не уходит наружу",
            )
        }
    }

    /**
     * Если последняя запись прогона не прочиталась, дерево не восстанавливается вовсе.
     *
     * Копия в корутине помнит снапшот начала шага — то есть заведомо более старую точку.
     * Вернуть дерево «на шаг назад» хуже, чем не вернуть и сказать об этом кодом причины.
     */
    @Test
    fun `без последней записи прогона дерево не восстанавливается по устаревшей копии`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(
                plan("шаг"),
                llm,
                runs = LoadFailingRuns(runs),
            )
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            val runId = sink.events.first().id
            engine.control(runId, RunCommand.STOP)
            awaitCompletion(worker)

            assertTrue(restore.asked.isEmpty(), "по устаревшей копии дерево не восстанавливают")
            val run = assertNotNull(runs.load(runId))
            assertEquals(RunState.STOPPED, run.state)
            assertEquals(RunInterruptReason.RESTORE_FAILED, run.interruptReason)
        }
    }

    /**
     * Отмена хостом дерево не трогает: прерванный прогон не остановлен пользователем.
     *
     * Хост помечает незавершённый прогон прерванным при следующем старте (решение 8), и дерево
     * к снапшоту при этом не возвращается: пользователь не просил отменять работу агента,
     * а незакоммиченные правки прогона видит тот, кто разбирает последствия падения.
     */
    @Test
    fun `отмена хостом дерево не трогает`() {
        runBlocking {
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(plan("шаг"), llm)
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            engine.awaitShownPlan(runs)
            llm.awaitCall(0)
            worker.cancelAndJoin()

            assertTrue(
                restore.asked.isEmpty(),
                "отмена хоста — не стоп: дерево не возвращают к снапшоту",
            )
            val run = assertNotNull(runs.all().single())
            assertEquals(
                RunState.RUNNING,
                run.state,
                "прогон остаётся незавершённым: его пометит прерванным следующий старт хоста",
            )
        }
    }

    /**
     * Обрезанный ответ модели без вызовов инструментов шаг не завершает.
     *
     * Признак обрезки (`LlmResponse.Text.truncated`) приходит от адаптера провайдера: текст
     * есть, а хвост ответа потерян. Считать такой шаг выполненным значило бы показать
     * пользователю успех работы, которую модель не договорила, а повтор запроса дал бы ту же
     * обрезку — поэтому прогон отказывает кодом.
     */
    @Test
    fun `обрезанный ответ без вызовов инструментов не даёт шагу выполниться`() {
        runBlocking {
            val engine = engine(plan("шаг"), ScriptedLlmClient(listOf(truncatedText("обрыв"))))
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            assertTrue(engine.processNextApproved(runs))

            val run = assertNotNull(runs.all().single())
            assertEquals(RunState.FAILED, run.state, "обрезанный ответ — отказ прогона, а не выполненный шаг")
            assertEquals(
                RunInterruptReason.RESPONSE_TRUNCATED,
                run.interruptReason,
                "причина — свой код, а не unexpected",
            )
            assertEquals(StepStatus.PENDING, run.plan.single().status, "шаг не отмечен выполненным")
            assertEquals(RunState.FAILED, sink.events.last().state, "отказ виден событием")
            assertEquals(TaskStatus.FAILED, tasks.load(run.taskId)?.status)
            assertEquals(RunInterruptReason.RESPONSE_TRUNCATED, tasks.load(run.taskId)?.failureReason)
        }
    }

    /** Обрезанный ответ с вызовом инструмента диалог не обрывает: модель всё-таки просит работу. */
    @Test
    fun `обрезанный ответ с вызовом инструмента шаг не обрывает`() {
        runBlocking {
            val engine = engine(
                plan("шаг"),
                ScriptedLlmClient(listOf(toolCall(truncated = true), text("готово"))),
            )
            engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

            assertTrue(engine.processNextApproved(runs))

            val run = assertNotNull(runs.all().single())
            assertEquals(RunState.FINISHED, run.state, "вызов инструмента в ответе — работа продолжается")
            assertEquals(StepStatus.DONE, run.plan.single().status)
        }
    }

    private suspend fun awaitState(runId: RunId, state: RunState) {
        withTimeout(5_000) {
            while (runs.load(runId)?.state != state) delay(10)
        }
    }

    private companion object {
        /** Идентификатор вызова инструмента: по нему результат уходит обратно модели. */
        const val CALL_ID: String = "call-1"

        /** Имя инструмента чтения: существует в настоящем реестре, файлы ему не нужны. */
        const val FIND_FILES: String = "find_files"

        /** Имя изменяющего инструмента: только его вызов ставит точку отката внутри шага. */
        const val WRITE_FILE: String = "write_file"
    }

    /** Ответ модели с вызовом инструмента; [truncated] задаёт признак обрезки. */
    private fun toolCall(truncated: Boolean = false): LlmResponse.Text = LlmResponse.Text(
        text = "",
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 1,
        toolCalls = listOf(LlmToolCall(id = CALL_ID, name = FIND_FILES, arguments = """{"mask":"*"}""")),
        truncated = truncated,
    )

    /** Ответ модели с изменяющим вызовом: он и записывает в прогон точку отката внутри шага. */
    private fun writeCall(): LlmResponse.Text = LlmResponse.Text(
        text = "",
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 1,
        toolCalls = listOf(
            LlmToolCall(
                id = "write-1",
                name = WRITE_FILE,
                arguments = """{"path":"src/App.kt","content":"новое\n"}""",
            ),
        ),
    )

    /** Успешный ответ с признаком обрезки: текст есть, а конец ответа провайдер не отдал. */
    private fun truncatedText(value: String): LlmResponse.Text = LlmResponse.Text(
        text = value,
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 5,
        truncated = true,
    )
}

/**
 * Хранилище, у которого не читается последняя запись прогона.
 *
 * Так проверяется честный отказ восстановления (T-1.4): если последнюю версию прогона
 * прочитать нельзя, по копии в корутине дерево не возвращают — она помнит снапшот начала шага.
 */
private class LoadFailingRuns(private val delegate: FakeRunRepository) : RunRepository {

    override fun save(run: AgentRun) = delegate.save(run)

    override fun load(id: RunId): AgentRun? = null

    override fun unfinished(): List<AgentRun> = delegate.unfinished()
}
