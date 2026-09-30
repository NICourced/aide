package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.prompt.PlanFormatException
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
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

    private fun engine(plan: List<PlanStep> = plan("шаг"), llm: LlmClient = textModel()): AgentRunEngine =
        AgentRunEngine(
            runs = runs,
            tasks = tasks,
            planner = RunPlanner { plan },
            llm = llm,
            events = sink,
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )

    @Test
    fun `прогон проходит planned, running и finished, и события совпадают с состояниями`() = runBlocking {
        val engine = engine(plan("раз", "два"))
        engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext())

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
        llm.awaitCall(0)
        val runId = sink.events.first().id
        engine.control(runId, RunCommand.PAUSE)
        gate.complete(Unit)

        awaitState(runId, RunState.PAUSED)
        val paused = assertNotNull(runs.load(runId))
        assertEquals(StepStatus.DONE, paused.plan[0].status, "шаг до паузы выполнен")
        assertEquals(StepStatus.PENDING, paused.plan[1].status, "шаг после паузы ещё не выполнялся")

        engine.control(runId, RunCommand.RESUME)
        worker.join()

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
        worker.join()
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
        llm.awaitCall(0)
        val runId = sink.events.first().id
        engine.control(runId, RunCommand.STOP)
        worker.join()

        val run = assertNotNull(runs.load(runId))
        assertEquals(RunState.STOPPED, run.state, "остановленный прогон — STOPPED, а не FAILED")
        assertNotNull(run.finishedAt, "у остановленного прогона обязано быть время завершения")
        assertEquals(RunInterruptReason.USER_STOP, run.interruptReason)
        assertEquals(RunState.STOPPED, sink.events.last().state)
        assertEquals(TaskStatus.FAILED, tasks.load(run.taskId)?.status)
    }

    @Test
    fun `ошибка модели даёт failed с причиной, и очередь после неё работает`() = runBlocking {
        val llm = ScriptedLlmClient(listOf(LlmResponse.Error(LlmErrorKind.NOT_CONFIGURED), text()))
        val engine = engine(plan("раз"), llm)

        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext())
        val failed = runs.all().single()
        assertEquals(RunState.FAILED, failed.state)
        assertEquals(LlmErrorKind.NOT_CONFIGURED.name, failed.interruptReason)
        assertEquals(TaskStatus.FAILED, tasks.load(failed.taskId)?.status)
        assertEquals(LlmErrorKind.NOT_CONFIGURED.name, tasks.load(failed.taskId)?.failureReason)

        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext(), "после ошибки очередь обязана продолжать работать")
        assertEquals(RunState.FINISHED, runs.all().last().state)
    }

    @Test
    fun `неожиданная ошибка модели даёт failed, и очередь продолжает работать`() = runBlocking {
        val engine = engine(plan("раз"), ThrowingOnceModel())

        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext())
        val failed = runs.all().single()
        assertEquals(RunState.FAILED, failed.state)
        assertEquals(
            RunInterruptReason.UNEXPECTED,
            failed.interruptReason,
            "неожиданная ошибка даёт замкнутый код, а не имя JVM-класса",
        )
        assertEquals(RunInterruptReason.UNEXPECTED, tasks.load(failed.taskId)?.failureReason)

        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext(), "после падения транспорта очередь обязана продолжать работать")
        assertEquals(RunState.FINISHED, runs.all().last().state)
    }

    @Test
    fun `отказ планирования помечает задачу failed с причиной`() = runBlocking {
        val engine = AgentRunEngine(
            runs = runs,
            tasks = tasks,
            planner = RunPlanner { throw PlanFormatException("не план") },
            llm = textModel(),
            events = sink,
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
            runs = runs,
            tasks = tasks,
            planner = RunPlanner { plan("раз") },
            llm = textModel(),
            events = ThrowingOnFinishSink(runs, tasks),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)

        // Рассылка FINISHED падает; движок это пробрасывает, но запись уже сделана.
        runCatching { engine.processNext() }

        val run = runs.all().single()
        assertEquals(RunState.FINISHED, run.state, "успешный прогон не подменяется отказом")
        assertEquals(TaskStatus.REVIEW, tasks.load(run.taskId)?.status, "задача остаётся на ревью")
    }

    @Test
    fun `две задачи не идут одновременно — вторая ждёт в очереди`() = runBlocking {
        val gate = CompletableDeferred<Unit>()
        val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
        val engine = engine(plan("раз"), llm)
        engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
        engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)

        val worker = launch { engine.processNext() }
        llm.awaitCall(0)
        val queued = tasks.unfinished().filter { it.status == TaskStatus.QUEUED }
        assertEquals(1, queued.size, "пока идёт первый прогон, вторая задача ждёт в очереди")

        gate.complete(Unit)
        worker.join()
        assertTrue(engine.processNext(), "вторая задача выполняется следом за первой")
        assertEquals(2, runs.all().size)
    }

    @Test
    fun `событие о смене состояния приходит после записи в базу`() = runBlocking {
        val engine = engine(plan("раз"))
        engine.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext())

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
        assertTrue(engine.processNext())

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
