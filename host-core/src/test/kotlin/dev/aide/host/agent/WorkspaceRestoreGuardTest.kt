package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.WorkspaceRestoreOutcome
import dev.aide.domain.SnapshotRef
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.JGitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.snapshotRefName
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
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.4: адаптер возврата дерева — «нечего восстанавливать», успех и перевод отказов в коды.
 *
 * Репозиторий настоящий: возврат дерева — это `reset` и `clean`, и проверять их на заглушке
 * значило бы проверять заглушку. Отсутствие снапшота решается адаптером, поэтому и оно здесь.
 */
class WorkspaceRestoreGuardTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git = JGitRepository.open(fixture.root)

    @AfterTest
    fun tearDown() {
        git.close()
        fixture.close()
    }

    /** Метка времени снапшота из T-1.19: миллисекунды от эпохи. */
    private val snapshotMillis = 1_758_535_200_000L

    @Test
    fun `без открытого воркспейса дерево вернуть некуда`() {
        runBlocking {
            val workspaces = OpenWorkspaces()
            try {
                val outcome = assertIs<WorkspaceRestoreOutcome.Refused>(
                    WorkspaceRestoreGuard(workspaces).restoreTo(SnapshotRef(snapshotRefName(snapshotMillis, LABEL))),
                )

                assertEquals(RunInterruptReason.NO_WORKSPACE, outcome.reason)
            } finally {
                workspaces.close()
            }
        }
    }

    @Test
    fun `без снапшота восстанавливать нечего, и дерево не трогается`() {
        runBlocking {
            val workspaces = workspacesWith()
            try {
                val outcome = WorkspaceRestoreGuard(workspaces).restoreTo(null)

                assertEquals(WorkspaceRestoreOutcome.Nothing, outcome)
                assertEquals(
                    listOf(" M src/Login.kt", "?? src/New.kt"),
                    GitCliFixture.porcelainLines(fixture.root),
                    "пустой репозиторий — не отказ, но и не повод трогать дерево",
                )
            } finally {
                workspaces.close()
            }
        }
    }

    @Test
    fun `дерево возвращается к снапшоту, а отказ git переводится в код прогона`() {
        runBlocking {
            val workspaces = workspacesWith()
            try {
                val guard = WorkspaceRestoreGuard(workspaces)
                val ref = SnapshotRef(snapshotRefName(snapshotMillis, LABEL))
                assertEquals(SnapshotOutcome.Created, git.createSnapshot(ref.value))

                assertEquals(WorkspaceRestoreOutcome.Restored, guard.restoreTo(ref))
                assertTrue(
                    GitCliFixture.porcelainLines(fixture.root).isEmpty(),
                    "правки прогона убраны: дерево совпало со снапшотом",
                )
                assertEquals(
                    "fun login() = Unit\n",
                    Files.readString(fixture.root.resolve("src/Login.kt")),
                    "отслеживаемая правка откачена к содержимому коммита",
                )

                val missing = assertIs<WorkspaceRestoreOutcome.Refused>(
                    guard.restoreTo(SnapshotRef("refs/ai/snap/1758535200000-нет-такого")),
                )
                assertEquals(
                    RunInterruptReason.RESTORE_FAILED,
                    missing.reason,
                    "отказ git — код словаря прогона, а не имя класса исключения",
                )
            } finally {
                workspaces.close()
            }
        }
    }

    /** Воркспейс поверх настоящего репозитория — так же, как его собирает хост. */
    private fun workspacesWith(): OpenWorkspaces {
        val workspace = Workspace.open(fixture.root)
        val fileSystem = WorkspaceFileSystem(workspace)
        return OpenWorkspaces().also {
            it.add(
                OpenedWorkspace(
                    workspace = workspace,
                    fileSystem = fileSystem,
                    treeBuilder = FileTreeBuilder(fileSystem, workspace),
                    boundary = WorkspaceBoundaryAdapter(fileSystem),
                    git = git,
                ),
            )
        }
    }

    private companion object {
        /** Повод снапшота в имени ссылки: тот же, что у точки отката перед шагом агента. */
        const val LABEL = "before-agent-step"
    }
}
