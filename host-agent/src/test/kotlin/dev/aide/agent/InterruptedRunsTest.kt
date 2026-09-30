package dev.aide.agent

import dev.aide.domain.RunId
import dev.aide.domain.RunState
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
        runs.save(testRun("r-planned", RunState.PLANNED))
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
            assertEquals(RunInterruptReason.HOST_RESTART, stored.interruptReason)
        }
        assertEquals(RunState.FINISHED, runs.load(RunId("r-done"))?.state, "завершённый прогон не трогается")
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
}
