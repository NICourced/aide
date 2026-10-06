package dev.aide.host.agent

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.JGitRepository
import dev.aide.host.server.ClientSessions
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.59 сквозь движок и настоящий git: прогон начинается с чистого дерева, правки человека
 * не уезжают в ветку агента, а после прогона возвращаются на свою ветку.
 *
 * Репозиторий и порты настоящие: смысл проверки — в согласии движка и git, а заглушка
 * на месте дерева доказывала бы только заглушку. Модель скриптованная (О-11).
 */
class WorkStashEndToEndTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git = JGitRepository.open(fixture.root)
    private val workspaces = OpenWorkspaces().also { it.add(openedWith()) }
    private val sessions = ClientSessions()
    private val runs = MemoryRunRepository()
    private val tasks = MemoryTaskRepository()

    /** Состояние дерева, снятое планировщиком: планирование идёт после откладывания правок. */
    private var duringRun: Captured? = null

    @AfterTest
    fun tearDown() {
        workspaces.close()
        fixture.close()
    }

    private fun engine(llm: LlmClient = TextModel()): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, NoopSink),
        models = ModelProvider { Result.success(ConfiguredModel("test/scripted", llm)) },
        planner = RunPlanner { _, _ ->
            duringRun = Captured(
                branch = git.currentBranch(),
                porcelain = GitCliFixture.porcelainLines(fixture.root),
                login = Files.readString(fixture.root.resolve("src/Login.kt")),
            )
            listOf(PlanStep(index = 0, summary = "шаг", status = StepStatus.PENDING))
        },
        tools = testStepTools(),
        repositories = RepositoryPorts(
            branches = TaskBranchGuard(workspaces, sessions),
            snapshots = Snapshots { TaskSnapshot.NoHead },
            workStash = WorkStashGuard(workspaces, sessions),
        ),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    @Test
    fun `прогон идёт с чистого дерева, а после него правки на месте`() {
        runBlocking {
            val engine = engine()

            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
            assertTrue(engine.processNext())

            val task = tasks.all().single()
            // Во время прогона: дерево чистое, HEAD — в ветке задачи, правок человека нет.
            val captured = requireNotNull(duringRun) { "планирование обязано было состояться" }
            assertEquals(task.branch, captured.branch, "прогон идёт в ветке задачи")
            assertTrue(captured.porcelain.isEmpty(), "прогон обязан начинаться с чистого дерева")
            assertEquals(
                "fun login() = Unit\n",
                captured.login,
                "правка человека не должна быть видна агенту во время прогона",
            )

            // В ветке агента правок человека нет: там записан исходный файл, а нового файла нет.
            assertEquals("fun login() = Unit", commitContent(task.branch, "src/Login.kt"))
            assertTrue(
                GitCliFixture.run(listOf("git", "cat-file", "-e", "${task.branch}:src/New.kt"), fixture.root)
                    .exitCode != 0,
                "новый файл человека в ветку агента не попал",
            )

            // После прогона: дерево вернулось на базовую ветку, правки на месте, ссылки нет.
            assertEquals("master", git.currentBranch(), "правки вернулись на ту ветку, где были")
            assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(fixture.root))
            assertEquals("fun login() = \"token\"\n", Files.readString(fixture.root.resolve("src/Login.kt")))
            assertNull(task.stashRef, "после возврата задача больше не держит отложенное")
        }
    }

    @Test
    fun `падение прогона тоже возвращает правки`() {
        runBlocking {
            val engine = engine(ThrowingModel())

            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
            assertTrue(engine.processNext())

            assertEquals("master", git.currentBranch())
            assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(fixture.root))
            assertNull(tasks.all().single().stashRef)
        }
    }

    /** Содержимое файла в коммите ветки [branch] — независимая проверка через git. */
    private fun commitContent(branch: String, path: String): String =
        GitCliFixture.run(listOf("git", "show", "$branch:$path"), fixture.root).output.trim()

    private fun openedWith(): OpenedWorkspace {
        val workspace = Workspace.open(fixture.root)
        val fileSystem = WorkspaceFileSystem(workspace)
        return OpenedWorkspace(
            workspace = workspace,
            fileSystem = fileSystem,
            treeBuilder = FileTreeBuilder(fileSystem, workspace),
            boundary = WorkspaceBoundaryAdapter(fileSystem),
            git = git,
        )
    }

    /** Что видит планировщик: ветка, состояние дерева и содержимое правки человека. */
    private data class Captured(val branch: String, val porcelain: List<String>, val login: String)

    private class TextModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }

    private class ThrowingModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse = error("провайдер недоступен")
    }

    private object NoopSink : AgentEventSink {
        override suspend fun runStateChanged(run: AgentRun) = Unit

        override suspend fun taskStateChanged(task: Task) = Unit
    }

    private class MemoryRunRepository : RunRepository {
        private val stored = LinkedHashMap<RunId, AgentRun>()

        override fun save(run: AgentRun) {
            stored[run.id] = run
        }

        override fun load(id: RunId): AgentRun? = stored[id]

        override fun unfinished(): List<AgentRun> =
            stored.values.filter { it.finishedAt == null }.sortedBy { it.startedAt }
    }

    private class MemoryTaskRepository : TaskRepository {
        private val stored = LinkedHashMap<TaskId, Task>()

        override fun save(task: Task) {
            stored[task.id] = task
        }

        override fun load(id: TaskId): Task? = stored[id]

        override fun unfinished(): List<Task> =
            stored.values.filter { it.status != TaskStatus.REVIEW && it.status != TaskStatus.ACCEPTED &&
                it.status != TaskStatus.REJECTED && it.status != TaskStatus.FAILED }

        fun all(): List<Task> = stored.values.toList()
    }
}
