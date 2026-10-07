package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.prompt.PlanFormatException
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelFailureCode
import dev.aide.domain.PlanDecision
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/**
 * T-1.1: состояния прогона и статусы задачи, пауза с продолжением, стоп, ошибка модели,
 * очередь «одна за раз».
 *
 * Все проверки идут на фейковых портах и скриптованной модели: сеть в тестах запрещена (О-11).
 */
class AgentRunEngineTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)

    /**
     * Инструменты шага: проверки этого класса их не зовут — состояния прогона
     * проверяются без файлов, — но движок собирается с настоящими инструментами,
     * а не с заглушкой: заглушка проверяла бы заглушку.
     */
    private val tools = stepTools()

    private fun engine(plan: List<PlanStep> = plan("шаг"), llm: LlmClient = textModel()): AgentRunEngine =
        AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(llm),
            planner = RunPlanner { _, _, _ -> plan },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )

    @Test
    fun `прогон проходит planned, running и finished, и события совпадают с состояниями`() = runBlocking {
        val engine = engine(plan("раз", "два"))
        engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))

        assertEquals(listOf(RunState.PLANNED, RunState.RUNNING, RunState.FINISHED), sink.states)
        assertEquals(
            listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.REVIEW),
            sink.taskStates,
        )
        val run = runs.all().single()
        assertEquals(RunState.FINISHED, run.state)
        assertNotNull(run.finishedAt)
        assertTrue(run.plan.all { it.status == StepStatus.DONE }, "все шаги обязаны быть выполнены")
        assertEquals(TaskStatus.REVIEW, tasks.load(run.taskId)?.status)
    }

    @Test
    fun `пауза между шагами встаёт в paused, а продолжение идёт с того же шага`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(listOf(text("первый"), text("второй"))) { index -> if (index == 0) gate.await() }
        val engine = engine(plan("раз", "два"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        engine.awaitShownPlan(runs)
        llm.awaitCall(0)
        val runId = sink.events.first().id
        engine.control(runId, RunCommand.PAUSE)
        gate.complete(Unit)

        awaitState(runId, RunState.PAUSED)
        val paused = assertNotNull(runs.load(runId))
        assertEquals(StepStatus.DONE, paused.plan[0].status, "шаг до паузы выполнен")
        assertEquals(StepStatus.PENDING, paused.plan[1].status, "шаг после паузы ещё не выполнялся")

        engine.control(runId, RunCommand.RESUME)
        awaitCompletion(worker)

        assertEquals(RunState.FINISHED, runs.load(runId)?.state)
        assertEquals(2, llm.callCount, "шаг со статусом DONE не повторяется при продолжении")
    }

    @Test
    fun `двойная пауза не возобновляет прогон`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(listOf(text("первый"), text("второй"))) { index -> if (index == 0) gate.await() }
        val engine = engine(plan("раз", "два"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        engine.awaitShownPlan(runs)
        llm.awaitCall(0)
        val runId = sink.events.first().id
        engine.control(runId, RunCommand.PAUSE)
        gate.complete(Unit)
        awaitState(runId, RunState.PAUSED)

        // Вторая пауза приходит, когда прогон уже на паузе: она не должна его возобновить.
        engine.control(runId, RunCommand.PAUSE)
        delay(PAUSE_SETTLE_MILLIS)
        assertEquals(RunState.PAUSED, runs.load(runId)?.state, "повторная PAUSE не выводит прогон из PAUSED")

        engine.control(runId, RunCommand.RESUME)
        awaitCompletion(worker)
        assertEquals(RunState.FINISHED, runs.load(runId)?.state)
        assertEquals(2, llm.callCount, "продолжение ровно с того же шага: шаг не повторялся")
    }

    @Test
    fun `стоп даёт stopped, а не failed`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
        val engine = engine(plan("раз"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        engine.awaitShownPlan(runs)
        llm.awaitCall(0)
        val runId = sink.events.first().id
        engine.control(runId, RunCommand.STOP)
        awaitCompletion(worker)

        val run = assertNotNull(runs.load(runId))
        assertEquals(RunState.STOPPED, run.state, "остановленный прогон — STOPPED, а не FAILED")
        assertNotNull(run.finishedAt, "у остановленного прогона обязано быть время завершения")
        assertEquals(RunInterruptReason.USER_STOP, run.interruptReason)
        assertEquals(RunState.STOPPED, sink.events.last().state)
        assertEquals(TaskStatus.FAILED, tasks.load(run.taskId)?.status)
    }

    @Test
    fun `ошибка модели даёт failed с причиной, и очередь после неё работает`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(LlmResponse.Error(LlmErrorKind.UNAUTHORIZED), text()))
        val engine = engine(plan("раз"), llm)

        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))
        val failed = runs.all().single()
        assertEquals(RunState.FAILED, failed.state)
        assertEquals(ModelFailureCode.UNAUTHORIZED, failed.interruptReason)
        assertEquals(TaskStatus.FAILED, tasks.load(failed.taskId)?.status)
        assertEquals(ModelFailureCode.UNAUTHORIZED, tasks.load(failed.taskId)?.failureReason)

        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs), "после ошибки очередь обязана продолжать работать")
        assertEquals(RunState.FINISHED, runs.all().last().state)
    }

    @Test
    fun `неожиданная ошибка модели даёт failed, и очередь продолжает работать`() = runBlocking {
        val engine = engine(plan("раз"), ThrowingOnceModel())

        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))
        val failed = runs.all().single()
        assertEquals(RunState.FAILED, failed.state)
        assertEquals(
            RunInterruptReason.UNEXPECTED,
            failed.interruptReason,
            "неожиданная ошибка даёт замкнутый код, а не имя JVM-класса",
        )
        assertEquals(RunInterruptReason.UNEXPECTED, tasks.load(failed.taskId)?.failureReason)

        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs), "после падения транспорта очередь обязана продолжать работать")
        assertEquals(RunState.FINISHED, runs.all().last().state)
    }

    @Test
    fun `отказ планирования помечает задачу failed с причиной`() = runBlocking {
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(textModel()),
            planner = RunPlanner { _, _, _ -> throw PlanFormatException("не план") },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        assertTrue(engine.processNext())
        val task = tasks.all().single()
        assertEquals(TaskStatus.FAILED, task.status, "отказ планирования виден на задаче")
        assertEquals(RunInterruptReason.PLAN_UNREADABLE, task.failureReason)
        assertTrue(runs.all().isEmpty(), "до плана прогон не создаётся (решение 2)")
        assertEquals(listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.FAILED), sink.taskStates)
    }

    @Test
    fun `сбой рассылки терминального события не превращает завершённый прогон в отказ`() = runBlocking {
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, ThrowingOnFinishSink(runs, tasks)),
            models = fixedModel(textModel()),
            planner = RunPlanner { _, _, _ -> plan("раз") },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        // Рассылка FINISHED падает; движок это пробрасывает, но запись уже сделана.
        // План подтверждается явно: иначе прогон стоял бы на стоянке и до FINISHED не дошёл.
        val worker = launch { runCatching { engine.processNext() } }
        engine.awaitShownPlan(runs)
        awaitCompletion(worker)

        val run = runs.all().single()
        assertEquals(RunState.FINISHED, run.state, "успешный прогон не подменяется отказом")
        assertEquals(TaskStatus.REVIEW, tasks.load(run.taskId)?.status, "задача остаётся на ревью")
    }

    @Test
    fun `прогон записывает, на какой модели он шёл`() = runBlocking {
        val engine = engine(plan("раз"), textModel())
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        assertTrue(engine.processNextApproved(runs))

        assertEquals("test/scripted", runs.all().single().modelAlias, "алиас обязан попасть в прогон (T-1.56)")
    }

    @Test
    fun `отказ выбрать модель помечает задачу кодом без создания прогона`() = runBlocking {
        // Пустая конфигурация — состояние чистой установки (решение 9): прогон падает
        // тем же кодом, что и раньше, и это видно на задаче, потому что прогона ещё нет.
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = failingModel(ModelCheckFailure.NotConfigured),
            planner = RunPlanner { _, _, _ -> plan("раз") },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        assertTrue(engine.processNext())
        val task = tasks.all().single()
        assertEquals(TaskStatus.FAILED, task.status)
        assertEquals(ModelFailureCode.NOT_CONFIGURED, task.failureReason)
        assertTrue(runs.all().isEmpty(), "до выбора модели прогон не создаётся")
    }

    @Test
    fun `отсутствие переменной окружения с ключом даёт свой код отказа`() = runBlocking {
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = failingModel(ModelCheckFailure.MissingKey("DEEPSEEK_API_KEY")),
            planner = RunPlanner { _, _, _ -> plan("раз") },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        assertTrue(engine.processNext())

        // Код отличается от «модель не настроена»: пользователь обязан понять, что дело
        // в переменной окружения, а не в пустой конфигурации (решение 6).
        assertEquals(ModelFailureCode.MISSING_KEY, tasks.all().single().failureReason)
    }

    @Test
    fun `смена модели в настройках не меняет правила идущего прогона`() = runBlocking {
        // FR-AGENT-5: модель берётся один раз на старте прогона. Иначе половина прогона
        // шла бы одной моделью, а половина другой, и стоимость прогона было бы нечем объяснить.
        val started = ScriptedLlmClient(listOf(text("первый"), text("второй")))
        val replacement = ScriptedLlmClient(listOf(text("третий")))
        var chosen = ConfiguredModel("model/a", started)
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = ModelProvider { Result.success(chosen) },
            planner = RunPlanner { _, _, _ -> plan("раз", "два") },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        engine.awaitShownPlan(runs)
        started.awaitCall(0)
        // Пользователь сменил модель в настройках прямо во время прогона.
        chosen = ConfiguredModel("model/b", replacement)
        awaitCompletion(worker)

        assertEquals(2, started.callCount, "оба шага идут моделью, которой прогон начался")
        assertEquals(0, replacement.callCount, "новая модель не подхватывается на середине прогона")
        assertEquals("model/a", runs.all().single().modelAlias)
    }

    @Test
    fun `две задачи не идут одновременно — вторая ждёт в очереди`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
        val engine = engine(plan("раз"), llm)
        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        engine.awaitShownPlan(runs)
        llm.awaitCall(0)
        val queued = tasks.unfinished().filter { it.status == TaskStatus.QUEUED }
        assertEquals(1, queued.size, "пока идёт первый прогон, вторая задача ждёт в очереди")

        gate.complete(Unit)
        awaitCompletion(worker)
        assertTrue(engine.processNextApproved(runs), "вторая задача выполняется следом за первой")
        assertEquals(2, runs.all().size)
    }

    @Test
    fun `событие о смене состояния приходит после записи в базу`() = runBlocking {
        val engine = engine(plan("раз"))
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))

        assertEquals(listOf(RunState.PLANNED, RunState.RUNNING, RunState.FINISHED), sink.states)
        sink.events.forEachIndexed { index, emitted ->
            assertEquals(
                emitted.state,
                sink.storedAtEmit[index]?.state,
                "в базе к моменту рассылки обязано лежать то же состояние, что в событии",
            )
        }
    }

    @Test
    fun `событие о смене статуса задачи приходит после записи в базу`() = runBlocking {
        val engine = engine(plan("раз"))
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))

        assertEquals(listOf(TaskStatus.QUEUED, TaskStatus.RUNNING, TaskStatus.REVIEW), sink.taskStates)
        sink.taskEvents.forEachIndexed { index, emitted ->
            assertEquals(
                emitted.status,
                sink.taskStoredAtEmit[index]?.status,
                "в базе к моменту рассылки обязан лежать тот же статус задачи",
            )
        }
    }

    @Test
    fun `неизвестный прогон не управляется молча`() {
        val engine = engine()
        assertFailsWith<UnknownRunException> { engine.control(RunId("нет-такого"), RunCommand.STOP) }
    }

    @Test
    fun `без решения работа не начинается, а подтверждение её запускает`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(text("готово")))
        val engine = engine(plan("шаг"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        val planned = awaitPlannedRun(runs)
        // Дать прогону время сделать хоть что-нибудь, если он собирался: без решения
        // он обязан молчать — ни вызова модели, ни смены состояния.
        delay(PAUSE_SETTLE_MILLIS)
        assertEquals(0, llm.callCount, "до решения модель не спрашивают")
        assertEquals(RunState.PLANNED, runs.load(planned.id)?.state, "прогон стоит в PLANNED")
        assertTrue(
            runs.load(planned.id)?.plan?.all { it.status == StepStatus.PENDING } == true,
            "ни один шаг не начат до решения",
        )

        engine.decidePlan(planned.id, PlanDecision.Approve)
        awaitCompletion(worker)

        assertEquals(RunState.FINISHED, runs.load(planned.id)?.state, "подтверждение запускает работу")
        assertEquals(1, llm.callCount)
        // Решение переводит прогон в RUNNING: без этого перехода «работа идёт» было бы
        // неотличимо от мгновенного завершения (T-1.2).
        assertEquals(
            listOf(RunState.PLANNED, RunState.RUNNING, RunState.FINISHED),
            sink.states,
            "после решения состояние обязано смениться на RUNNING",
        )
    }

    @Test
    fun `в режиме только предлагать стоянки нет и прогон идёт сразу`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(text("готово")))
        val engine = engine(plan("шаг"), llm)
        engine.postTask("Только предложи", AutonomyMode.SUGGEST_ONLY)

        // Ограничение по времени, а не голый вызов: если стоянка появится в этом режиме,
        // проверка обязана упасть, а не зависнуть.
        val processed = withTimeoutOrNull(5_000) { engine.processNext() }
        assertEquals(true, processed, "без стоянки прогон обязан завершиться в этом же вызове")
        assertEquals(RunState.FINISHED, runs.all().single().state)
        assertEquals(1, llm.callCount, "в этом режиме подтверждения не спрашивают")
    }

    @Test
    fun `перепланирование с комментарием вызывает планировщик заново и снова ждёт`() = runBlocking {
        val comments = mutableListOf<String?>()
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(textModel()),
            planner = RunPlanner { _, _, comment ->
                comments += comment
                if (comment == null) plan("первоначальный") else plan("переделанный")
            },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        val planned = awaitPlannedRun(runs)
        engine.decidePlan(planned.id, PlanDecision.Replan("разбей иначе"))

        // Перепланированный план записывается и уходит событием, а прогон снова ждёт решения.
        val replanned = awaitPlanSummary(runs, planned.id, "переделанный")
        assertEquals(listOf(null, "разбей иначе"), comments, "комментарий уходит планировщику отдельным вызовом")
        assertEquals(RunState.PLANNED, replanned.state, "после перепланирования стоянка прежняя")
        assertTrue(
            sink.events.any { it.plan.firstOrNull()?.summary == "переделанный" },
            "новый план обязан уехать событием",
        )

        engine.decidePlan(planned.id, PlanDecision.Approve)
        awaitCompletion(worker)

        assertEquals(RunState.FINISHED, runs.load(planned.id)?.state)
        assertEquals(2, comments.size, "третьего планирования без новой просьбы не было")
    }

    @Test
    fun `стоп после перепланирования сохраняет новый план, а не первоначальный`() = runBlocking {
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(textModel()),
            planner = RunPlanner { _, _, comment ->
                if (comment == null) plan("первоначальный") else plan("переделанный")
            },
            tools = tools,
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        val planned = awaitPlannedRun(runs)
        engine.decidePlan(planned.id, PlanDecision.Replan("разбей иначе"))
        awaitPlanSummary(runs, planned.id, "переделанный")

        engine.control(planned.id, RunCommand.STOP)
        awaitCompletion(worker)

        val stopped = assertNotNull(runs.load(planned.id))
        assertEquals(RunState.STOPPED, stopped.state)
        // Стоп берёт последнюю записанную версию прогона: копия из корутины помнит
        // первоначальный план, и без перечитывания остановка вернула бы его назад.
        assertEquals(
            listOf("переделанный"),
            stopped.plan.map { it.summary },
            "остановленный прогон обязан нести перепланированный план, а не прежний",
        )
    }

    @Test
    fun `стоп во время стоянки даёт stopped, а не failed`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(text("готово")))
        val engine = engine(plan("шаг"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        val planned = awaitPlannedRun(runs)
        engine.control(planned.id, RunCommand.STOP)
        awaitCompletion(worker)

        val run = assertNotNull(runs.load(planned.id))
        assertEquals(RunState.STOPPED, run.state, "ожидание решения прерывается стопом")
        assertEquals(RunInterruptReason.USER_STOP, run.interruptReason)
        assertEquals(0, llm.callCount, "работы в этом прогоне не было")
    }

    @Test
    fun `пауза во время стоянки не оставляет прогон на паузе после подтверждения`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(text("готово")))
        val engine = engine(plan("шаг"), llm)
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        val planned = awaitPlannedRun(runs)
        // Пауза приходит, пока работа ещё не началась: смысла она не имеет и не должна
        // всплыть после подтверждения — иначе прогон встал бы на паузу сразу после решения.
        engine.control(planned.id, RunCommand.PAUSE)
        engine.decidePlan(planned.id, PlanDecision.Approve)
        awaitCompletion(worker)

        assertEquals(RunState.FINISHED, runs.load(planned.id)?.state, "пауза во время стоянки отброшена (T-1.2)")
        assertEquals(1, llm.callCount)
    }

    private suspend fun awaitState(runId: RunId, state: RunState) {
        withTimeout(5_000) {
            while (runs.load(runId)?.state != state) delay(10)
        }
    }

    private companion object {
        /** Сколько ждать, чтобы лишняя команда паузы успела дойти до цикла прогона. */
        const val PAUSE_SETTLE_MILLIS = 100L
    }
}

/** План из перечисленных шагов с последовательными номерами. */
fun plan(vararg summaries: String): List<PlanStep> =
    summaries.mapIndexed { index, summary -> PlanStep(index = index, summary = summary, status = StepStatus.PENDING) }

/** Модель, отвечающая заданным текстом: шаг выполнен, стоимость нулевая. */
fun textModel(vararg texts: String): LlmClient =
    ScriptedLlmClient(if (texts.isEmpty()) listOf(text()) else texts.map { text(it) })

/** Успешный ответ модели. */
fun text(value: String = "готово"): LlmResponse.Text =
    LlmResponse.Text(text = value, cost = Cost(amountMicros = 0, known = true), elapsedMillis = 5)
