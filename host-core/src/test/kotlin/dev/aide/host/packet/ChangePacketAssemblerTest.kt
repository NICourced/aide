package dev.aide.host.packet

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ChangeProposal
import dev.aide.domain.ChangeSource
import dev.aide.domain.Cost
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.PacketId
import dev.aide.domain.PacketStatus
import dev.aide.domain.PlanStep
import dev.aide.domain.ProposalId
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.SnapshotRef
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import dev.aide.domain.ToolCallId
import dev.aide.domain.addedLines
import dev.aide.domain.removedLines
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.JGitRepository
import dev.aide.host.server.ClientSessions
import dev.aide.host.store.HostStore
import dev.aide.host.store.db.HostDatabase
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * Сборка неизменяемого пакета изменений после прогона (T-1.24).
 *
 * Проверяет, что пакет собирается из git-diff и неприменённых предложений,
 * сохраняется в базу и рассылается событие.
 */
class ChangePacketAssemblerTest {

    private val repo = dev.aide.host.workspace.TempRepoFixture()
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private val sessions = ClientSessions()
    private val workspaces = OpenWorkspaces()

    @AfterTest
    fun tearDown() {
        driver.close()
        repo.close()
    }

    @Test
    fun `пакет собирается из diff между базовой и задачной ветками`() = runBlocking {
        val (root, baseCommit, taskBranch) = prepareRepo()
        openWorkspace(root)
        val task = task(baseBranch = "master", branch = taskBranch)
        val run = run(task.id)

        val assembler = ChangePacketAssembler(
            workspaces = workspaces,
            store = store,
            sessions = sessions,
            clock = { Instant.fromEpochMilliseconds(1_758_535_200_000) },
        )

        val packet = assembler.publish(task, run)

        assertEquals(PacketId(task.id.value), packet.id)
        assertEquals(1, packet.revision)
        assertEquals(baseCommit, packet.baseCommit)
        assertTrue(packet.headCommit?.isNotEmpty() == true)
        assertEquals(1, packet.files.size)
        assertEquals("src/Login.kt", packet.files.single().path)
        assertEquals(1, packet.addedLines)
        assertEquals(1, packet.removedLines)
        assertEquals(PacketStatus.AWAITING_REVIEW, packet.status)
        assertEquals(ChangeSource.AGENT, packet.source)
    }

    @Test
    fun `пустой diff без предложений даёт пустой пакет с NORMAL риском`() = runBlocking {
        val root = repo.root
        GitCliFixture.createRepo(root)
        GitCliFixture.run(listOf("git", "checkout", "-b", "ai/empty"), root)
        openWorkspace(root)
        val task = task(baseBranch = "master", branch = "ai/empty")
        val run = run(task.id)

        val packet = ChangePacketAssembler(
            workspaces = workspaces,
            store = store,
            sessions = sessions,
            clock = { Instant.fromEpochMilliseconds(1_758_535_200_000) },
        ).publish(task, run)

        assertTrue(packet.files.isEmpty())
        assertEquals(RiskLevel.NORMAL, packet.risk)
        assertEquals(TestState.NOT_RUN, packet.tests.state)
    }

    @Test
    fun `неприменённое предложение добавляется к файлам пакета`() = runBlocking {
        val (root, _, taskBranch) = prepareRepo()
        openWorkspace(root)
        val task = task(baseBranch = "master", branch = taskBranch)
        val run = run(task.id)
        store.runs.save(run)
        store.proposals.save(
            ChangeProposal(
                id = ProposalId("cp-1"),
                runId = RunId("r-1"),
                callId = ToolCallId("tc-1"),
                tool = "write_file",
                files = listOf(
                    FileChange(
                        path = "src/Proposal.kt",
                        changeKind = FileChangeKind.ADDED,
                        hunks = listOf(
                            Hunk(
                                id = HunkId("h-prop"),
                                filePath = "src/Proposal.kt",
                                startLine = 1,
                                kind = HunkKind.ADD,
                                risk = RiskLevel.NORMAL,
                                lines = listOf(
                                    HunkLine(
                                        kind = LineKind.ADDED,
                                        oldNumber = null,
                                        newNumber = 1,
                                        text = "prop",
                                    ),
                                ),
                            ),
                        ),
                        addedLines = 1,
                        removedLines = 0,
                    ),
                ),
                risk = RiskLevel.NORMAL,
                createdAt = Instant.fromEpochMilliseconds(1_758_535_200_000),
            ),
        )

        val packet = ChangePacketAssembler(
            workspaces = workspaces,
            store = store,
            sessions = sessions,
            clock = { Instant.fromEpochMilliseconds(1_758_535_200_000) },
        ).publish(task, run)

        assertEquals(2, packet.files.size)
        assertTrue(packet.files.any { it.path == "src/Proposal.kt" })
    }

    private fun prepareRepo(): Triple<Path, String, String> {
        val root = repo.root
        GitCliFixture.createRepo(root)
        val baseCommit = GitCliFixture.headHash(root)
        val taskBranch = "ai/task"
        GitCliFixture.run(listOf("git", "checkout", "-b", taskBranch), root)
        root.resolve("src/Login.kt").writeText("fun login(token: String) = Unit\n")
        GitCliFixture.run(listOf("git", "add", "src/Login.kt"), root)
        GitCliFixture.run(listOf("git", "commit", "-m", "правка"), root)
        return Triple(root, baseCommit, taskBranch)
    }

    private fun openWorkspace(root: Path) {
        val workspace = Workspace.open(root)
        val fileSystem = WorkspaceFileSystem(workspace)
        workspaces.add(
            OpenedWorkspace(
                workspace = workspace,
                fileSystem = fileSystem,
                treeBuilder = FileTreeBuilder(fileSystem, workspace),
                boundary = WorkspaceBoundaryAdapter(fileSystem),
                git = JGitRepository.open(root),
            ),
        )
    }

    private fun task(baseBranch: String, branch: String): Task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Сделай вход",
        branch = branch,
        baseBranch = baseBranch,
        status = TaskStatus.RUNNING,
        createdAt = Instant.fromEpochMilliseconds(1_758_535_200_000),
    )

    private fun run(taskId: TaskId): AgentRun = AgentRun(
        id = RunId("r-1"),
        taskId = taskId,
        state = RunState.FINISHED,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        plan = listOf(PlanStep(index = 0, summary = "первый шаг", status = dev.aide.domain.StepStatus.DONE)),
        snapshots = listOf(SnapshotRef("refs/ai/snap/1")),
        startedAt = Instant.fromEpochMilliseconds(1_758_535_200_000),
        finishedAt = Instant.fromEpochMilliseconds(1_758_535_206_000),
        elapsedMillis = 60000,
        cost = Cost(amountMicros = 0, known = true),
    )
}
