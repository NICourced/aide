package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.TaskBranch
import dev.aide.host.git.ChangedFile
import dev.aide.host.git.CommitInfo
import dev.aide.host.git.GitRepository
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.git.TaskBranchRefusal
import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.ClientSession
import dev.aide.host.server.ClientSessions
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestDedupCache
import dev.aide.protocol.RequestId
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlinx.coroutines.runBlocking

/**
 * T-1.18: адаптер порта ветки задачи — перевод отказов git в коды прогона и событие
 * о смене состояния воркспейса клиентам.
 *
 * Репозиторий подставляется заглушкой: сам git проверен тестами `JGitRepository`,
 * а здесь важно, что движок получает код причины, а клиент — событие.
 */
class TaskBranchGuardTest {

    private val fixture = TempRepoFixture()
    private val workspaces = OpenWorkspaces()
    private val sessions = ClientSessions()

    /** Что получила сессия клиента: по байтам проверяется, что ушло именно событие. */
    private val delivered = mutableListOf<ByteArray>()

    @AfterTest
    fun tearDown() {
        workspaces.close()
        fixture.close()
    }

    private fun openedWith(git: GitRepository): OpenedWorkspace {
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

    /** Регистрирует клиента, которому рассылаются события; приветствие ему не нужно. */
    private fun addSession() {
        sessions.add(
            ClientSession(
                handler = ClientMessageHandler { message ->
                    HostMessage.Failure(RequestId("test"), ProtocolError.Internal("обработчик не нужен: $message"))
                },
                hostVersion = ProtocolVersion.CURRENT,
                send = { delivered += it },
                dedup = RequestDedupCache(),
                broadcast = { },
            ),
        )
    }

    @Test
    fun `без открытого воркспейса ветку ставить некуда`() {
        runBlocking {
            val guard = TaskBranchGuard(workspaces, sessions)

            val branch = assertIs<TaskBranch.Refused>(guard.ensure("ai/t-1"))

            assertEquals(RunInterruptReason.NO_WORKSPACE, branch.reason)
        }
    }

    @Test
    fun `созданная ветка отдаёт базовую и рассылает событие о смене воркспейса`() {
        runBlocking {
            val git = StubGit(TaskBranchOutcome.Created("master"))
            val opened = openedWith(git)
            workspaces.add(opened)
            addSession()
            val guard = TaskBranchGuard(workspaces, sessions)

            val branch = assertIs<TaskBranch.Created>(guard.ensure("ai/t-1"))

            assertEquals("master", branch.base)
            assertEquals("ai/t-1", git.asked)
            assertEquals(
                HostMessage.Event(HostEvent.WorkspaceChanged(opened.workspace.id)),
                delivered.single().let { bytes ->
                    assertIs<DecodeResult.Message<HostMessage>>(ProtocolCodec.decodeHostMessage(bytes)).message
                },
                "шапка клиента узнаёт о ветке только из события: без него она осталась бы прежней",
            )
        }
    }

    @Test
    fun `продолжение существующей ветки тоже рассылает событие, а базу не трогает`() {
        runBlocking {
            workspaces.add(openedWith(StubGit(TaskBranchOutcome.Existing)))
            addSession()
            val guard = TaskBranchGuard(workspaces, sessions)

            assertEquals(TaskBranch.Existing, guard.ensure("ai/t-1"))

            assertEquals(1, delivered.size, "клиент обязан перезапросить состояние и на продолжении ветки")
        }
    }

    @Test
    fun `отказ git переводится в код прогона и ничего не рассылает`() {
        runBlocking {
            val refusals = mapOf(
                TaskBranchRefusal.NO_COMMITS to RunInterruptReason.REPOSITORY_EMPTY,
                TaskBranchRefusal.DETACHED_HEAD to RunInterruptReason.HEAD_DETACHED,
                TaskBranchRefusal.READ_ONLY to RunInterruptReason.REPOSITORY_READ_ONLY,
                TaskBranchRefusal.GIT_FAILED to RunInterruptReason.BRANCH_FAILED,
            )
            addSession()

            refusals.forEach { (refusal, code) ->
                val workspaces = OpenWorkspaces()
                try {
                    workspaces.add(openedWith(StubGit(TaskBranchOutcome.Refused(refusal))))

                    val branch = assertIs<TaskBranch.Refused>(TaskBranchGuard(workspaces, sessions).ensure("ai/t-1"))

                    assertEquals(code, branch.reason, "у отказа $refusal обязан быть свой код")
                } finally {
                    workspaces.close()
                }
            }
            assertNull(delivered.firstOrNull(), "при отказе состояние воркспейса не менялось")
        }
    }

    /** Репозиторий с заданным исходом обеспечения ветки: так проверяется перевод причин. */
    private class StubGit(private val outcome: TaskBranchOutcome) : GitRepository {

        var asked: String? = null

        override fun currentBranch(): String = "master"

        override fun headCommit(): String = HEAD

        override fun changedFiles(): List<ChangedFile> = emptyList()

        override fun commitLog(limit: Int): List<CommitInfo> = emptyList()

        override fun ensureTaskBranch(branch: String): TaskBranchOutcome {
            asked = branch
            return outcome
        }

        override fun close() = Unit

        private companion object {
            /** Хеш HEAD заглушки: чтения его не проверяют. */
            const val HEAD = "0000000"
        }
    }
}
