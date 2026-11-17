package dev.aide.host.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.SnapshotRef
import dev.aide.domain.TaskId
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.JGitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.snapshotRefName
import dev.aide.host.server.ClientSession
import dev.aide.host.server.ClientSessions
import dev.aide.host.store.HostStore
import dev.aide.host.store.db.HostDatabase
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SnapshotRollbackRejection
import dev.aide.protocol.WorkspaceId
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/** T-1.6: обработчик отката к снапшоту — перевод протокола, защита от активного прогона, рассылка событий. */
class SnapshotRollbackHandlerTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git = JGitRepository.open(fixture.root)
    private val openedWorkspace = openedWith(git)
    private val workspaces = OpenWorkspaces().also { it.add(openedWorkspace) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private val sessions = ClientSessions()
    private val delivered = CopyOnWriteArrayList<HostEvent>()

    private val handler = SnapshotRollbackHandler(
        workspaces = workspaces,
        runs = store.runs,
        sessions = sessions,
    )

    @AfterTest
    fun tearDown() {
        workspaces.close()
        driver.close()
        fixture.close()
    }

    @Test
    fun `список обогащает снапшоты задачей из прогона`() {
        runBlocking {
            val ref = createSnapshot()
            val taskId = TaskId("t-1")
            store.runs.save(agentRun(runId = RunId("r-1"), taskId = taskId, snapshots = listOf(ref)))

            val response = handler.handle(
                ClientMessage.SnapshotList(requestId = RequestId("req"), workspaceId = workspaceId()),
            )

            val list = assertIs<HostMessage.SnapshotListResponse>(response)
            assertEquals(1, list.snapshots.size)
            assertEquals(ref.value, list.snapshots[0].ref.value)
            assertEquals(taskId, list.snapshots[0].taskId)
        }
    }

    @Test
    fun `предпросмотр обогащает снапшот задачей из прогона`() {
        runBlocking {
            val ref = createSnapshot()
            val taskId = TaskId("t-1")
            store.runs.save(agentRun(runId = RunId("r-1"), taskId = taskId, snapshots = listOf(ref)))
            makeHeadDiffer()

            val response = handler.handle(
                ClientMessage.SnapshotPreviewRequest(
                    requestId = RequestId("req"),
                    workspaceId = workspaceId(),
                    ref = ref,
                ),
            )

            val preview = assertIs<HostMessage.SnapshotPreviewResponse>(response)
            assertEquals(taskId, preview.preview.snapshot.taskId)
        }
    }

    @Test
    fun `откат при незавершенном прогоне отклоняется с ACTIVE_RUN`() {
        runBlocking {
            val ref = createSnapshot()
            store.runs.save(
                agentRun(
                    runId = RunId("r-1"),
                    taskId = TaskId("t-1"),
                    state = RunState.RUNNING,
                    snapshots = listOf(ref),
                ),
            )
            makeHeadDiffer()
            val preview = assertIs<HostMessage.SnapshotPreviewResponse>(
                handler.handle(
                    ClientMessage.SnapshotPreviewRequest(
                        requestId = RequestId("p"),
                        workspaceId = workspaceId(),
                        ref = ref,
                    ),
                ),
            )

            val response = handler.handle(
                ClientMessage.SnapshotRevert(
                    requestId = RequestId("req"),
                    workspaceId = workspaceId(),
                    ref = ref,
                    expectedHead = preview.preview.headCommit,
                ),
            )

            val failure = assertIs<HostMessage.Failure>(response)
            val rejection = assertIs<ProtocolError.SnapshotRollbackRejected>(failure.error)
            assertEquals(SnapshotRollbackRejection.ACTIVE_RUN, rejection.rejection)
            assertTrue(delivered.isEmpty(), "при отказе события не рассылаются")
        }
    }

    @Test
    fun `завершенный прогон не мешает откату`() {
        runBlocking {
            val ref = createSnapshot()
            store.runs.save(
                agentRun(
                    runId = RunId("r-1"),
                    taskId = TaskId("t-1"),
                    state = RunState.FINISHED,
                    snapshots = listOf(ref),
                ),
            )
            makeHeadDiffer()
            val preview = assertIs<HostMessage.SnapshotPreviewResponse>(
                handler.handle(
                    ClientMessage.SnapshotPreviewRequest(
                        requestId = RequestId("p"),
                        workspaceId = workspaceId(),
                        ref = ref,
                    ),
                ),
            )
            val session = collectingSession()
            sessions.add(session)

            val response = handler.handle(
                ClientMessage.SnapshotRevert(
                    requestId = RequestId("req"),
                    workspaceId = workspaceId(),
                    ref = ref,
                    expectedHead = preview.preview.headCommit,
                ),
            )

            val reverted = assertIs<HostMessage.SnapshotReverted>(response)
            assertTrue(reverted.commit.isNotBlank(), "ответ обязан содержать хеш обратного коммита")
            assertEquals(1, delivered.size)
            val changed = assertIs<HostEvent.WorkspaceChanged>(delivered.single())
            assertEquals(workspaceId(), changed.workspaceId)
        }
    }

    @Test
    fun `закрытый воркспейс сохраняет WorkspaceClosed`() {
        runBlocking {
            workspaces.close()

            val response = handler.handle(
                ClientMessage.SnapshotList(
                    requestId = RequestId("req"),
                    workspaceId = workspaceId(),
                ),
            )

            val failure = assertIs<HostMessage.Failure>(response)
            assertIs<ProtocolError.WorkspaceClosed>(failure.error)
        }
    }

    private fun createSnapshot(): SnapshotRef {
        val ref = SnapshotRef(snapshotRefName(SNAPSHOT_MILLIS, LABEL))
        assertEquals(SnapshotOutcome.Created, git.createSnapshot(ref.value))
        return ref
    }

    private fun makeHeadDiffer() {
        fixture.root.resolve("src/Login.kt").writeText("fun login() = \"changed\"\n")
        GitCliFixture.run(listOf("git", "add", "-A"), fixture.root)
        GitCliFixture.run(listOf("git", "commit", "-m", "правка"), fixture.root)
    }

    private fun openedWith(git: JGitRepository): OpenedWorkspace {
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

    private fun workspaceId(): WorkspaceId = openedWorkspace.workspace.id

    private fun agentRun(
        runId: RunId,
        taskId: TaskId,
        state: RunState = RunState.FINISHED,
        snapshots: List<SnapshotRef> = emptyList(),
    ): AgentRun = AgentRun(
        id = runId,
        taskId = taskId,
        state = state,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        snapshots = snapshots,
        startedAt = Instant.fromEpochMilliseconds(SNAPSHOT_MILLIS),
        finishedAt = if (state == RunState.FINISHED) Instant.fromEpochMilliseconds(SNAPSHOT_MILLIS + 1) else null,
    )

    private fun collectingSession(): ClientSession = ClientSession(
        handler = { error("не используется") },
        hostVersion = dev.aide.protocol.ProtocolVersion.CURRENT,
        send = { bytes ->
            val message = dev.aide.protocol.ProtocolCodec.decodeHostMessage(bytes)
            if (message is dev.aide.protocol.DecodeResult.Message<HostMessage>) {
                (message.message as? HostMessage.Event)?.event?.let { delivered += it }
            }
        },
        dedup = dev.aide.protocol.RequestDedupCache(),
        broadcast = { event -> delivered += event },
    )

    private companion object {
        const val SNAPSHOT_MILLIS = 1_758_535_200_000L
        const val LABEL = "before-agent-step"
    }
}
