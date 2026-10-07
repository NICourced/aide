package dev.aide.agent

import dev.aide.domain.AutonomyMode
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.datetime.Instant

/**
 * T-1.1: после падения хоста прогон помечается прерванным, а не продолжается с середины молча.
 *
 * Проверяются ровно незавершённые прогоны и незавершённые задачи; завершённые не трогаются.
 */
class InterruptedRunsTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val finishedAt = Instant.fromEpochMilliseconds(9_000)

    @Test
    fun `прерываются ровно незавершённые прогоны`() {
        runs.save(testRun("r-planned", RunState.PLANNED, plan = listOf(PlanStep(0, "шаг", StepStatus.PENDING))))
        runs.save(testRun("r-running", RunState.RUNNING))
        runs.save(testRun("r-paused", RunState.PAUSED))
        runs.save(testRun("r-done", RunState.FINISHED, finishedAt = Instant.fromEpochMilliseconds(1)))

        val interrupted = InterruptedRuns(runs, tasks) { finishedAt }.markInterrupted()

        assertEquals(
            setOf(RunId("r-planned"), RunId("r-running"), RunId("r-paused")),
            interrupted.map { it.id }.toSet(),
        )
        // Проверяется состояние в хранилище, а не только возвращённый список: без
        // сохранения тест на одном списке проходил бы, ничего не доказывая.
        listOf("r-planned", "r-running", "r-paused").forEach { id ->
            val stored = assertNotNull(runs.load(RunId(id)))
            assertEquals(RunState.INTERRUPTED, stored.state)
            assertEquals(finishedAt, stored.finishedAt, "у прерванного прогона есть время и причина")
        }
        assertEquals(
            RunInterruptReason.PLAN_AWAITING_CONFIRMATION,
            runs.load(RunId("r-planned"))?.interruptReason,
            "ждавший плана прогон прерывается своим кодом: работу он ещё не начинал (T-1.2)",
        )
        listOf("r-running", "r-paused").forEach { id ->
            assertEquals(RunInterruptReason.HOST_RESTART, runs.load(RunId(id))?.interruptReason)
        }
        assertEquals(RunState.FINISHED, runs.load(RunId("r-done"))?.state, "завершённый прогон не трогается")
    }

    @Test
    fun `план ожидавшего прогона сохраняется, а не теряется при прерывании`() {
        val plan = listOf(PlanStep(0, "разобрать", StepStatus.PENDING), PlanStep(1, "поправить", StepStatus.PENDING))
        runs.save(testRun("r-planned", RunState.PLANNED, plan = plan))

        InterruptedRuns(runs, tasks) { finishedAt }.markInterrupted()

        assertEquals(plan, runs.load(RunId("r-planned"))?.plan, "план лежит в базе и прерыванием не стирается")
    }

    @Test
    fun `в режиме без шлюза плановый прогон прерывается общим кодом, а не кодом ожидания`() {
        // SUGGEST_ONLY: шлюза нет, PLANNED — мгновение прогона, а не ожидание решения.
        runs.save(testRun("r-suggest", RunState.PLANNED, mode = AutonomyMode.SUGGEST_ONLY))

        InterruptedRuns(runs, tasks) { finishedAt }.markInterrupted()

        assertEquals(
            RunInterruptReason.HOST_RESTART,
            runs.load(RunId("r-suggest"))?.interruptReason,
            "без шлюза ожидания плана не бывает — и код обязан быть общим",
        )
    }

    @Test
    fun `незавершённые задачи становятся failed, остальные не меняются`() {
        tasks.save(testTask("t-queued", TaskStatus.QUEUED))
        tasks.save(testTask("t-running", TaskStatus.RUNNING))
        tasks.save(testTask("t-review", TaskStatus.REVIEW))
        tasks.save(testTask("t-failed", TaskStatus.FAILED))

        InterruptedRuns(runs, tasks) { finishedAt }.markInterrupted()

        assertEquals(TaskStatus.FAILED, tasks.load(TaskId("t-queued"))?.status)
        assertEquals(RunInterruptReason.HOST_RESTART, tasks.load(TaskId("t-queued"))?.failureReason)
        assertEquals(TaskStatus.FAILED, tasks.load(TaskId("t-running"))?.status)
        assertEquals(TaskStatus.REVIEW, tasks.load(TaskId("t-review"))?.status, "задача на ревью не трогается")
        assertEquals(TaskStatus.FAILED, tasks.load(TaskId("t-failed"))?.status)
    }

    @Test
    fun `задача ожидавшего плана прогона падает кодом ожидания, а работавшего — общим`() {
        runs.save(testRun("waiting", RunState.PLANNED))
        runs.save(testRun("working", RunState.RUNNING))
        tasks.save(testTask("t-waiting", TaskStatus.RUNNING))
        tasks.save(testTask("t-working", TaskStatus.RUNNING))

        InterruptedRuns(runs, tasks) { finishedAt }.markInterrupted()

        assertEquals(
            RunInterruptReason.PLAN_AWAITING_CONFIRMATION,
            tasks.load(TaskId("t-waiting"))?.failureReason,
            "по коду на задаче видно, что работа даже не начиналась (T-1.2)",
        )
        assertEquals(RunInterruptReason.HOST_RESTART, tasks.load(TaskId("t-working"))?.failureReason)
    }
}
