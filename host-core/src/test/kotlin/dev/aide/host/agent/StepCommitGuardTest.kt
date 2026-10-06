package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.host.git.ChangedFile
import dev.aide.host.git.CommitInfo
import dev.aide.host.git.GitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.StashOutcome
import dev.aide.host.git.StashRepository
import dev.aide.host.git.StashReturnOutcome
import dev.aide.host.git.StepCommitOutcome
import dev.aide.host.git.StepCommitRefusal
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.tools.ports.StepCommit
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlinx.coroutines.runBlocking

/**
 * T-1.11: адаптер фиксации шага — ветка и сообщение доходят до git, отказы типизированы.
 *
 * Репозиторий подменён заглушкой: проверяется перевод исходов git в исходы порта
 * и то, что ветка и сообщение не подменяются по дороге. Настоящий коммит проверяет
 * `JGitRepositoryTest` — здесь была бы проверка заглушки.
 */
class StepCommitGuardTest {

    private val fixture = TempRepoFixture()

    @AfterTest
    fun tearDown() {
        fixture.close()
    }

    private fun guardWith(git: GitRepository): StepCommitGuard =
        StepCommitGuard(OpenWorkspaces().also { it.add(openedWith(git, fixture.root)) })

    @Test
    fun `ветка и сообщение доходят до git, а хеш возвращается вызывающему`() {
        runBlocking {
            val git = StubGit(StepCommitOutcome.Committed(HASH))

            val outcome = guardWith(git).commit(BRANCH, MESSAGE)

            assertEquals(StepCommit.Committed(HASH), outcome)
            assertEquals(BRANCH, git.branch, "ветка задачи обязана дойти до git без подмены")
            assertEquals(MESSAGE, git.message, "сообщение шага обязано дойти до git без подмены")
        }
    }

    @Test
    fun `чистое дерево остаётся NothingToCommit`() {
        runBlocking {
            assertIs<StepCommit.NothingToCommit>(
                guardWith(StubGit(StepCommitOutcome.NothingToCommit)).commit(BRANCH, MESSAGE),
            )
        }
    }

    @Test
    fun `отказ по чужой ветке переводится в свой код причины`() {
        runBlocking {
            val outcome = assertIs<StepCommit.Refused>(
                guardWith(StubGit(StepCommitOutcome.Refused(StepCommitRefusal.WRONG_BRANCH))).commit(BRANCH, MESSAGE),
            )

            assertEquals(RunInterruptReason.COMMIT_WRONG_BRANCH, outcome.reason)
        }
    }

    @Test
    fun `сбой git переводится в код отказа, а не в исключение`() {
        runBlocking {
            val outcome = assertIs<StepCommit.Refused>(
                guardWith(StubGit(StepCommitOutcome.Refused(StepCommitRefusal.GIT_FAILED))).commit(BRANCH, MESSAGE),
            )

            assertEquals(RunInterruptReason.COMMIT_FAILED, outcome.reason)
        }
    }

    @Test
    fun `без открытого воркспейса коммита нет`() {
        runBlocking {
            val outcome = assertIs<StepCommit.Refused>(
                StepCommitGuard(OpenWorkspaces()).commit(BRANCH, MESSAGE),
            )

            assertEquals(RunInterruptReason.NO_WORKSPACE, outcome.reason)
        }
    }

    private companion object {

        const val BRANCH: String = "ai/t-1"
        const val MESSAGE: String = "Шаг 1: поправить вход"
        const val HASH: String = "abc1234"
    }
}

/** Воркспейс поверх репозитория — так же, как его собирает хост. */
private fun openedWith(git: GitRepository, root: Path): OpenedWorkspace {
    val workspace = Workspace.open(root)
    val fileSystem = WorkspaceFileSystem(workspace)
    return OpenedWorkspace(
        workspace = workspace,
        fileSystem = fileSystem,
        treeBuilder = FileTreeBuilder(fileSystem, workspace),
        boundary = WorkspaceBoundaryAdapter(fileSystem),
        git = git,
    )
}

/** Репозиторий с заданным исходом коммита: так проверяется перевод причин. */
private class StubGit(private val outcome: StepCommitOutcome) : GitRepository {

    var branch: String? = null
    var message: String? = null

    override fun currentBranch(): String = "master"

    override fun headCommit(): String = "0000000"

    override fun changedFiles(): List<ChangedFile> = emptyList()

    override fun commitLog(limit: Int): List<CommitInfo> = emptyList()

    override fun ensureTaskBranch(branch: String): TaskBranchOutcome = TaskBranchOutcome.Existing

    override fun commitStep(branch: String, message: String): StepCommitOutcome {
        this.branch = branch
        this.message = message
        return outcome
    }

    override fun createSnapshot(ref: String): SnapshotOutcome = SnapshotOutcome.NoHead

    override fun snapshotRefs(): List<String> = emptyList()

    override fun deleteSnapshots(refs: List<String>) = Unit

    override val workStash: StashRepository = object : StashRepository {
        override fun stashEdits(ref: String, message: String): StashOutcome = StashOutcome.Nothing

        override fun returnStashEdits(ref: String, branch: String?): StashReturnOutcome =
            StashReturnOutcome.Returned
    }

    override fun close() = Unit
}
