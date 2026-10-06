package dev.aide.host.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.agent.RunInterruptReason
import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.host.git.ChangedFile
import dev.aide.host.git.CommitInfo
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.GitRepository
import dev.aide.host.git.JGitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.StashOutcome
import dev.aide.host.git.StashRepository
import dev.aide.host.git.StashReturnOutcome
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.git.snapshotRefName
import dev.aide.host.store.HostStore
import dev.aide.host.store.db.HostDatabase
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.tools.ports.ChangeSnapshot
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.8: адаптер точки отката — ссылка на HEAD, один снапшот на изменение HEAD, отказы.
 *
 * Репозиторий настоящий: точка отката — ссылка, и проверять её на заглушке значило бы
 * проверять заглушку. Дедупликация по HEAD (решение 4) — главное, что здесь проверяется:
 * без неё шаг с несколькими записями наплодил бы ссылок на один коммит.
 */
class ChangeSnapshotGuardTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git: GitRepository = JGitRepository.open(fixture.root)
    private val workspaces = OpenWorkspaces().also { it.add(openedWith(git, fixture.root)) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private var now = BASE_MILLIS

    @AfterTest
    fun tearDown() {
        workspaces.close()
        driver.close()
        fixture.close()
    }

    private fun guard(over: OpenWorkspaces = workspaces): ChangeSnapshotGuard =
        ChangeSnapshotGuard(over, store) { Instant.fromEpochMilliseconds(now) }

    @Test
    fun `точка отката ставится ссылкой на текущий HEAD и не видна в списке веток`() {
        runBlocking {
            val taken = assertIs<ChangeSnapshot.Taken>(guard().beforeChange(RUN))

            assertEquals(SnapshotRef(snapshotRefName(BASE_MILLIS, "before-agent-step")), taken.ref)
            assertEquals(listOf(taken.ref.value), GitCliFixture.snapshotRefs(fixture.root))
            assertEquals(
                "* master",
                GitCliFixture.branchList(fixture.root),
                "ссылка лежит вне refs/heads и в обычном списке веток не появляется",
            )
        }
    }

    @Test
    fun `второй вызов при неизменном HEAD внутри прогона возвращает ту же ссылку`() {
        runBlocking {
            val guard = guard()
            val first = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))
            // Метка времени уехала: если бы снапшот ставился заново, получилась бы другая ссылка.
            now += STEP_MILLIS

            val second = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))

            assertEquals(first.ref, second.ref, "HEAD не двигался — это та же точка отката")
            assertEquals(1, GitCliFixture.snapshotRefs(fixture.root).size, "дубль ссылки на тот же коммит не создаётся")
        }
    }

    @Test
    fun `второй прогон не получает ссылку первого при том же HEAD`() {
        runBlocking {
            val guard = guard()
            val first = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))
            now += STEP_MILLIS

            // HEAD тот же, но прогон другой: кэш «HEAD → ссылка» не должен переехать в него,
            // иначе вторая задача запишет себе снапшот, поставленный временем первой.
            val second = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(OTHER_RUN))

            assertTrue(first.ref != second.ref, "у нового прогона своя точка отката, даже если коммит тот же")
            assertEquals(
                listOf(first.ref.value, second.ref.value).sorted(),
                GitCliFixture.snapshotRefs(fixture.root),
                "обе точки отката лежат в репозитории",
            )
        }
    }

    @Test
    fun `удалённая извне ссылка не возвращается из кэша, а ставится заново`() {
        runBlocking {
            val guard = guard()
            val first = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))
            // Ссылку мог вытеснить другой прогон: кэш обязан заметить, что она мертва.
            git.deleteSnapshots(listOf(first.ref.value))
            now += STEP_MILLIS

            val second = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))

            assertTrue(first.ref != second.ref, "мёртвая ссылка не возвращается — ставится новая")
            assertEquals(
                listOf(second.ref.value),
                GitCliFixture.snapshotRefs(fixture.root),
                "в репозитории остаётся только новая ссылка",
            )
        }
    }

    @Test
    fun `после сдвига HEAD ставится новая ссылка, прежняя остаётся`() {
        runBlocking {
            val guard = guard()
            val first = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))
            commitEverything(fixture.root)
            now += STEP_MILLIS

            val second = assertIs<ChangeSnapshot.Taken>(guard.beforeChange(RUN))

            assertTrue(first.ref != second.ref, "новый коммит — новая точка отката")
            assertEquals(
                listOf(first.ref.value, second.ref.value).sorted(),
                GitCliFixture.snapshotRefs(fixture.root),
                "прежняя точка отката никуда не делась",
            )
        }
    }

    @Test
    fun `пустой репозиторий даёт NoHead`() {
        runBlocking {
            val empty = TempRepoFixture().also { GitCliFixture.createEmptyRepo(it.root) }
            val emptyGit = JGitRepository.open(empty.root)
            val emptyWorkspaces = OpenWorkspaces().also { it.add(openedWith(emptyGit, empty.root)) }
            try {
                val outcome = guard(emptyWorkspaces).beforeChange(RUN)

                assertIs<ChangeSnapshot.NoHead>(outcome, "коммитов нет — ссылаться не на что")
            } finally {
                emptyWorkspaces.close()
                empty.close()
            }
        }
    }

    @Test
    fun `без открытого воркспейса точки отката нет`() {
        runBlocking {
            val refused = assertIs<ChangeSnapshot.Failed>(guard(OpenWorkspaces()).beforeChange(RUN))

            assertEquals(RunInterruptReason.NO_WORKSPACE, refused.reason)
        }
    }

    @Test
    fun `сбой записи ссылки переводится в код отказа, а не в исключение`() {
        runBlocking {
            val refusing = OpenWorkspaces().also { it.add(openedWith(RefusingGit(), fixture.root)) }
            try {
                val refused = assertIs<ChangeSnapshot.Failed>(guard(refusing).beforeChange(RUN))

                assertEquals(RunInterruptReason.SNAPSHOT_FAILED, refused.reason)
            } finally {
                refusing.close()
            }
        }
    }

    private companion object {

        /** Прогон, в котором идут проверки кэша; второй нужен, чтобы граница прогона была видна. */
        val RUN: RunId = RunId("run-1")
        val OTHER_RUN: RunId = RunId("run-2")

        /** Метка времени первой точки отката: под неё считается имя ссылки в проверках. */
        const val BASE_MILLIS = 1_758_535_200_000L

        /** Насколько уезжает время между вызовами: с неизменным временем ссылки не различить. */
        const val STEP_MILLIS = 1_000L
    }
}

/** Фиксирует текущее содержимое репозитория коммитом: так двигается HEAD между вызовами. */
private fun commitEverything(root: Path) {
    val added = GitCliFixture.run(listOf("git", "add", "-A"), root)
    assertEquals(0, added.exitCode, "git add обязан пройти: ${added.output}")
    val committed = GitCliFixture.run(listOf("git", "commit", "-m", "сдвиг HEAD"), root)
    assertEquals(0, committed.exitCode, "git commit обязан пройти: ${committed.output}")
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

/** Репозиторий, у которого ссылка снапшота не записывается: так проверяется перевод отказа в код. */
private class RefusingGit : GitRepository {

    override fun currentBranch(): String = "master"

    override fun headCommit(): String = "0000000"

    override fun changedFiles(): List<ChangedFile> = emptyList()

    override fun commitLog(limit: Int): List<CommitInfo> = emptyList()

    override fun ensureTaskBranch(branch: String): TaskBranchOutcome = TaskBranchOutcome.Existing

    override fun createSnapshot(ref: String): SnapshotOutcome = SnapshotOutcome.Refused

    override fun snapshotRefs(): List<String> = emptyList()

    override fun deleteSnapshots(refs: List<String>) = Unit

    override val workStash: StashRepository = object : StashRepository {
        override fun stashEdits(ref: String, message: String): StashOutcome = StashOutcome.Nothing

        override fun returnStashEdits(ref: String, branch: String?): StashReturnOutcome =
            StashReturnOutcome.Returned
    }

    override fun close() = Unit
}
