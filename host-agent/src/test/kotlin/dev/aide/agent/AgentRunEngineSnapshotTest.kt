package dev.aide.agent

import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunState
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.19 в движке: снапшот ставится в начале прогона и записывается в прогон,
 * а пустой репозиторий прогону не мешает.
 *
 * Git здесь не участвует: проверяется поведение движка, а сам репозиторий — тестами
 * `JGitRepository` и `TaskSnapshotGuard`. Модель скриптованная (О-11).
 */
class AgentRunEngineSnapshotTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val snapshots = RecordingSnapshots()
    private val tools = stepTools()

    private fun engine(): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(textModel()),
        planner = RunPlanner { _, _ -> plan("шаг") },
        tools = tools,
        repositories = RepositoryPorts(branchAlreadyExists, snapshots, noWorkStash),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    @Test
    fun `прогон ставит снапшот в начале и записывает его в прогон`() {
        runBlocking {
            val engine = engine()

            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
            assertTrue(engine.processNext())

            assertEquals(listOf(SnapshotTrigger.BEFORE_AGENT_STEP), snapshots.triggers, "снапшот ставится до шагов")
            val run = runs.all().single()
            assertEquals(
                listOf(SnapshotRef(SNAPSHOT_REF)),
                run.snapshots,
                "ссылка обязана попасть в прогон: по ней хост отличает нужные снапшоты от вытесняемых",
            )
            assertEquals(RunState.FINISHED, run.state)
            assertEquals(TaskStatus.REVIEW, tasks.load(run.taskId)?.status)
        }
    }

    @Test
    fun `пустой репозиторий не мешает прогону`() {
        runBlocking {
            snapshots.answer(TaskSnapshot.NoHead)
            val engine = engine()

            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
            assertTrue(engine.processNext())

            val run = runs.all().single()
            assertTrue(run.snapshots.isEmpty(), "снапшота нет, и это видно: список пуст")
            assertEquals(RunState.FINISHED, run.state, "без коммитов читать и планировать можно")
            assertEquals(TaskStatus.REVIEW, tasks.load(run.taskId)?.status)
        }
    }

    @Test
    fun `отказ снапшота помечает задачу кодом причины, а не молчит`() {
        runBlocking {
            // Так выглядит сбой записи ссылки на стороне движка: порт вернул отказ,
            // задача обязана получить код, а прогон — завершиться, а не остаться `PLANNED`.
            snapshots.answer(TaskSnapshot.Refused(RunInterruptReason.SNAPSHOT_FAILED))
            val engine = engine()

            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
            assertTrue(engine.processNext())

            val task = tasks.all().single()
            assertEquals(TaskStatus.FAILED, task.status)
            assertEquals(RunInterruptReason.SNAPSHOT_FAILED, task.failureReason)
            assertEquals(
                RunState.FAILED,
                runs.all().single().state,
                "прогон завершён отказом, а не остался planned или running",
            )
        }
    }
}

/** Ссылка снапшота, которую порт отдаёт по умолчанию. */
private const val SNAPSHOT_REF = "refs/ai/snap/1758535200000-before-agent-step"

/** Порт снапшотов, запоминающий поводы и отвечающий заданным исходом. */
private class RecordingSnapshots(
    private var outcome: TaskSnapshot = TaskSnapshot.Created(SnapshotRef(SNAPSHOT_REF)),
) : Snapshots {

    /** Поводы, с которыми движок просил снапшот; по ним видно, что он спросил и когда. */
    val triggers = mutableListOf<SnapshotTrigger>()

    /** Меняет ответ порта: так проверяются пустой репозиторий и отказ. */
    fun answer(outcome: TaskSnapshot) {
        this.outcome = outcome
    }

    override suspend fun create(trigger: SnapshotTrigger): TaskSnapshot {
        triggers += trigger
        return outcome
    }
}
