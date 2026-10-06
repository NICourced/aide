package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskStash
import dev.aide.domain.TaskId
import dev.aide.host.git.ChangedFile
import dev.aide.host.git.CommitInfo
import dev.aide.host.git.GitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.StashOutcome
import dev.aide.host.git.StashRepository
import dev.aide.host.git.StashReturnOutcome
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.server.ClientSessions
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * T-1.59: адаптер порта отложенных правок — имя ссылки с идентификатором задачи и перевод
 * исходов git в коды прогона.
 *
 * Git подставляется заглушкой: сами откладывание и возврат проверены `JGitRepositoryTest`,
 * а здесь важно, что движок получает код причины, а имя ссылки несёт метку задачи.
 */
class WorkStashGuardTest {

    private val fixture = TempRepoFixture()
    private val workspaces = OpenWorkspaces()
    private val taskId = TaskId("t-1")

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

    @Test
    fun `без открытого воркспейса правки откладывать некуда`() {
        runBlocking {
            val guard = WorkStashGuard(workspaces, ClientSessions())

            val stash = assertIs<TaskStash.Refused>(guard.stash(taskId))
            val restore = assertIs<StashReturn.Refused>(guard.restore("refs/ai/stash/t-1", "master"))

            assertEquals(RunInterruptReason.NO_WORKSPACE, stash.reason)
            assertEquals(RunInterruptReason.NO_WORKSPACE, restore.reason)
        }
    }

    @Test
    fun `ссылка несёт метку задачи, а исходы откладывания переводятся в коды`() {
        runBlocking {
            val outcomes = mapOf(
                StashOutcome.Stashed(REF, "master") to TaskStash.Stashed(REF, "master"),
                StashOutcome.Nothing to TaskStash.Nothing,
                StashOutcome.Refused to TaskStash.Refused(RunInterruptReason.STASH_FAILED),
            )

            outcomes.forEach { (git, expected) ->
                val stub = StubGit(stash = git)
                val workspaces = OpenWorkspaces().also { it.add(openedWith(stub)) }
                try {
                    assertEquals(expected, WorkStashGuard(workspaces, ClientSessions()).stash(taskId), "исход $git")
                    assertEquals(REF, stub.stashRef, "имя ссылки собирается из идентификатора задачи")
                    assertEquals("ai/t-1", stub.stashMessage, "в метке видно, чьи это правки")
                } finally {
                    workspaces.close()
                }
            }
        }
    }

    @Test
    fun `исходы возврата переводятся в коды прогона, а ссылка и ветка доезжают`() {
        runBlocking {
            val outcomes = mapOf(
                StashReturnOutcome.Returned to StashReturn.Returned,
                StashReturnOutcome.Conflict to StashReturn.Conflict(RunInterruptReason.STASH_CONFLICT),
                StashReturnOutcome.Refused to StashReturn.Refused(RunInterruptReason.STASH_RETURN_FAILED),
            )

            outcomes.forEach { (git, expected) ->
                val stub = StubGit(restore = git)
                val workspaces = OpenWorkspaces().also { it.add(openedWith(stub)) }
                try {
                    val guard = WorkStashGuard(workspaces, ClientSessions())
                    assertEquals(expected, guard.restore(REF, "master"), "исход $git")
                    assertEquals(REF to "master", stub.returned, "вернуть надо ту самую ссылку на ту же ветку")
                } finally {
                    workspaces.close()
                }
            }
        }
    }

    /** Репозиторий с заданными исходами правок: так проверяется перевод причин. */
    private class StubGit(
        private val stash: StashOutcome = StashOutcome.Nothing,
        private val restore: StashReturnOutcome = StashReturnOutcome.Returned,
    ) : GitRepository, StashRepository {

        var stashRef: String? = null
        var stashMessage: String? = null
        var returned: Pair<String, String?>? = null

        override val workStash: StashRepository get() = this

        override fun currentBranch(): String = "master"

        override fun headCommit(): String = "0000000"

        override fun changedFiles(): List<ChangedFile> = emptyList()

        override fun commitLog(limit: Int): List<CommitInfo> = emptyList()

        override fun ensureTaskBranch(branch: String): TaskBranchOutcome = TaskBranchOutcome.Existing

        override fun createSnapshot(ref: String): SnapshotOutcome = SnapshotOutcome.NoHead

        override fun snapshotRefs(): List<String> = emptyList()

        override fun deleteSnapshots(refs: List<String>) = Unit

        override fun stashEdits(ref: String, message: String): StashOutcome {
            stashRef = ref
            stashMessage = message
            return stash
        }

        override fun returnStashEdits(ref: String, branch: String?): StashReturnOutcome {
            returned = ref to branch
            return restore
        }

        override fun close() = Unit
    }

    private companion object {
        /** Ссылка отложенного по имени задачи: её собирает адаптер, а не тест. */
        const val REF = "refs/ai/stash/t-1"
    }
}
