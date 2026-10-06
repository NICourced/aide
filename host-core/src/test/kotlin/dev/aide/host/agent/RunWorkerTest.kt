package dev.aide.host.agent

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.RunPlanner
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.agent.ports.TaskStash
import dev.aide.agent.ports.WorkStash
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Instant

/**
 * Предохранитель воркера: сбой обработки задачи не останавливает очередь, но и не
 * превращается в холостой цикл.
 *
 * Тесты различают одноразовый отказ (очередь доходит до конца) и постоянный (число
 * попыток ограничено паузой, а не thousands per second).
 */
class RunWorkerTest {

    @Test
    fun `сбой обработки одной задачи не останавливает воркер`() = runBlocking {
        val runs = MemoryRunRepository()
        val tasks = FlakyTaskRepository()
        val engine = engine(runs, tasks)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = RunWorker(engine).start(scope)
        try {
            tasks.failNextRunningSave = true
            engine.postTask("первая", AutonomyMode.ASK_BEFORE_CHANGES)
            engine.postTask("вторая", AutonomyMode.ASK_BEFORE_CHANGES)

            withTimeout(10_000) {
                while (runs.all().count { it.state == RunState.FINISHED } < 2) delay(10)
            }
            assertEquals(2, runs.all().count { it.state == RunState.FINISHED }, "очередь обязана дойти до конца")
        } finally {
            job.cancelAndJoin()
            scope.cancel()
        }
    }

    @Test
    fun `постоянный сбой не превращается в холостой цикл`() = runBlocking {
        val runs = MemoryRunRepository()
        val tasks = AlwaysFailingTaskRepository()
        val engine = engine(runs, tasks)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val job = RunWorker(engine, initialRetryMillis = 10, maxRetryMillis = 50).start(scope)
        try {
            engine.postTask("задача", AutonomyMode.ASK_BEFORE_CHANGES)
            delay(OBSERVE_MILLIS)

            assertTrue(tasks.failures > 0, "сбой обязан произойти, иначе тест проверял бы пустоту")
            assertTrue(
                tasks.failures < MAX_EXPECTED_ATTEMPTS,
                "за $OBSERVE_MILLIS мс попыток: ${tasks.failures} — воркер крутится вхолостую",
            )
            assertTrue(job.isActive, "постоянный сбой не убивает воркер")
        } finally {
            job.cancelAndJoin()
            scope.cancel()
        }
    }

    private fun engine(runs: RunRepository, tasks: TaskRepository): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, NoopSink),
        models = ModelProvider { Result.success(ConfiguredModel("test/scripted", TextModel())) },
        planner = RunPlanner { _, _ -> listOf(PlanStep(index = 0, summary = "шаг", status = StepStatus.PENDING)) },
        tools = testStepTools(),
        // Ветка в этом тесте ни при чём: проверяется предохранитель воркера, а не T-1.18.
        // Снапшот тоже ни при чём: «коммитов нет» — самый дешёвый исход порта (T-1.19).
        // Откладывать нечего: правки пользователя тут не предмет проверки (T-1.59).
        repositories = RepositoryPorts(
            branches = TaskBranches { TaskBranch.Existing },
            snapshots = Snapshots { TaskSnapshot.NoHead },
            workStash = object : WorkStash {
                override suspend fun stash(taskId: TaskId): TaskStash = TaskStash.Nothing

                override suspend fun restore(ref: String, branch: String?): StashReturn = StashReturn.Returned
            },
        ),
        clock = { Instant.fromEpochMilliseconds(1) },
    )

    private class MemoryRunRepository : RunRepository {
        private val stored = LinkedHashMap<RunId, AgentRun>()

        override fun save(run: AgentRun) {
            stored[run.id] = run
        }

        override fun load(id: RunId): AgentRun? = stored[id]

        override fun unfinished(): List<AgentRun> =
            stored.values.filter { it.finishedAt == null }.sortedBy { it.startedAt }

        fun all(): List<AgentRun> = stored.values.toList()
    }

    /** Хранилище, один раз отказывающее на записи статуса RUNNING. */
    private class FlakyTaskRepository : TaskRepository {
        private val stored = LinkedHashMap<TaskId, Task>()

        var failNextRunningSave = false

        override fun save(task: Task) {
            if (failNextRunningSave && task.status == TaskStatus.RUNNING) {
                failNextRunningSave = false
                error("хранилище недоступно")
            }
            stored[task.id] = task
        }

        override fun load(id: TaskId): Task? = stored[id]

        override fun unfinished(): List<Task> = unfinishedTasks(stored.values)
    }

    /** Хранилище, отказывающее на записи статуса RUNNING всегда, и считающее попытки. */
    private class AlwaysFailingTaskRepository : TaskRepository {
        private val stored = LinkedHashMap<TaskId, Task>()

        var failures = 0
            private set

        override fun save(task: Task) {
            if (task.status == TaskStatus.RUNNING) {
                failures += 1
                error("хранилище недоступно")
            }
            stored[task.id] = task
        }

        override fun load(id: TaskId): Task? = stored[id]

        override fun unfinished(): List<Task> = unfinishedTasks(stored.values)
    }

    private object NoopSink : AgentEventSink {
        override suspend fun runStateChanged(run: AgentRun) = Unit

        override suspend fun taskStateChanged(task: Task) = Unit
    }

    private class TextModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }

    private companion object {
        /** Сколько наблюдать за воркером при постоянном отказе. */
        const val OBSERVE_MILLIS = 700L

        /** Верхняя граница разумного числа попыток за это время; холостой цикл дал бы тысячи. */
        const val MAX_EXPECTED_ATTEMPTS = 100
    }
}

/** Незавершённые задачи в порядке постановки; «не завершено» — отрицанием завершённых статусов. */
private fun unfinishedTasks(source: Collection<Task>): List<Task> = source
    .filter { it.status != TaskStatus.REVIEW && it.status != TaskStatus.ACCEPTED &&
        it.status != TaskStatus.REJECTED && it.status != TaskStatus.FAILED }
    .sortedBy { it.createdAt }
