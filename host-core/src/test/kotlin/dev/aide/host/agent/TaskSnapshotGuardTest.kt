package dev.aide.host.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.host.git.ChangedFile
import dev.aide.host.git.CommitInfo
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.GitRepository
import dev.aide.host.git.JGitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.StashOutcome
import dev.aide.host.git.StashRepository
import dev.aide.host.git.StashReturnOutcome
import dev.aide.host.git.StepCommitOutcome
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.git.snapshotRefName
import dev.aide.host.store.HostStore
import dev.aide.host.store.StoreFixtures
import dev.aide.host.store.db.HostDatabase
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
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.19: адаптер порта снапшотов — формат ссылки, скрытость от списка веток и вытеснение
 * с защитой «в использовании».
 *
 * Репозиторий настоящий: снапшот — ссылка, и проверять его на заглушке значило бы проверять
 * заглушку. Хранилище тоже настоящее: защита «в использовании» считается по задачам и прогонам.
 */
class TaskSnapshotGuardTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git: GitRepository = JGitRepository.open(fixture.root)
    private val workspaces = OpenWorkspaces().also { it.add(openedWith(git)) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))

    @AfterTest
    fun tearDown() {
        workspaces.close()
        driver.close()
        fixture.close()
    }

    @Test
    fun `снапшот ставится в формате метка времени и повод и не виден в списке веток`() {
        runBlocking {
            val created = assertIs<TaskSnapshot.Created>(
                guard(SNAPSHOT_MILLIS).create(SnapshotTrigger.BEFORE_AGENT_STEP),
            )

            assertEquals(SnapshotRef(snapshotRefName(SNAPSHOT_MILLIS, "before-agent-step")), created.ref)
            assertEquals(listOf(created.ref.value), GitCliFixture.snapshotRefs(fixture.root))
            assertEquals(
                "* master",
                GitCliFixture.branchList(fixture.root),
                "снапшот лежит вне refs/heads и в обычном списке веток не появляется",
            )
        }
    }

    @Test
    fun `пятьдесят первый снапшот вытесняет самый старый`() {
        runBlocking {
            // Числа из критерия T-1.19 (50 хранится, 51-й вытесняет), а не из константы политики:
            // иначе тест не заметил бы, что предел изменили.
            val seeded = seed(51)

            val created = assertIs<TaskSnapshot.Created>(guard(NEW_MILLIS).create(SnapshotTrigger.BEFORE_AGENT_STEP))

            val alive = GitCliFixture.snapshotRefs(fixture.root)
            assertEquals(50, alive.size, "хранится ровно последних пятьдесят")
            assertFalse(seeded.first() in alive, "самый старый обязан быть вытеснен")
            assertTrue(created.ref.value in alive, "новый снапшот на месте")
        }
    }

    @Test
    fun `снапшот незакрытой задачи не вытесняется, даже когда он самый старый`() {
        runBlocking {
            val seeded = seed(51)
            protectAll(listOf(seeded.first()), TaskStatus.REVIEW)

            val created = assertIs<TaskSnapshot.Created>(guard(NEW_MILLIS).create(SnapshotTrigger.BEFORE_AGENT_STEP))

            val alive = GitCliFixture.snapshotRefs(fixture.root)
            assertTrue(seeded.first() in alive, "к снапшоту незакрытой задачи пользователь ещё вернётся")
            assertFalse(seeded[1] in alive, "вытеснен следующий за защищённым, а не он сам")
            assertTrue(created.ref.value in alive)
        }
    }

    @Test
    fun `снапшот закрытой задачи вытесняется наравне с прочими`() {
        runBlocking {
            val seeded = seed(51)
            protectAll(listOf(seeded.first()), TaskStatus.ACCEPTED)

            guard(NEW_MILLIS).create(SnapshotTrigger.BEFORE_AGENT_STEP)

            assertFalse(
                seeded.first() in GitCliFixture.snapshotRefs(fixture.root),
                "задача закрыта — её снапшот защищать больше не от чего",
            )
        }
    }

    @Test
    fun `новый снапшот не вытесняет сам себя, когда все прочие в использовании`() {
        runBlocking {
            // Все пятьдесят ссылок защищены: единственная неиспользуемая — та, что ставится
            // сейчас, и вытеснение без запаса снесло бы именно её (тогда прогон записал бы
            // снапшот, которого в репозитории нет).
            val seeded = seed(50)
            protectAll(seeded, TaskStatus.REVIEW)

            val created = assertIs<TaskSnapshot.Created>(guard(NEW_MILLIS).create(SnapshotTrigger.BEFORE_AGENT_STEP))

            val alive = GitCliFixture.snapshotRefs(fixture.root)
            assertTrue(created.ref.value in alive, "только что созданный снапшот не может вытеснить сам себя")
            assertEquals(51, alive.size, "защищённые не вытесняются, удалять нечего — и новая ссылка на месте")
            assertTrue(seeded.all { it in alive }, "ни одна защищённая ссылка не пострадала")
        }
    }

    @Test
    fun `сбой записи ссылки переводится в код отказа, а не в исключение`() {
        runBlocking {
            // Репозиторий, у которого ссылка не записывается: у ветки задачи такой же сбой
            // обработан значением, и снапшот обязан вести себя так же — иначе исключение
            // уйдёт в предохранитель воркера и прогон останется `PLANNED` без причины.
            val workspaces = OpenWorkspaces().also { it.add(openedWith(RefusingGit())) }
            val guard = TaskSnapshotGuard(
                workspaces = workspaces,
                store = store,
                clock = { Instant.fromEpochMilliseconds(NEW_MILLIS) },
            )

            val refused = assertIs<TaskSnapshot.Refused>(guard.create(SnapshotTrigger.BEFORE_AGENT_STEP))

            assertEquals(RunInterruptReason.SNAPSHOT_FAILED, refused.reason)
        }
    }

    @Test
    fun `без открытого воркспейса снапшот ставить некуда`() {
        runBlocking {
            val guard = TaskSnapshotGuard(
                workspaces = OpenWorkspaces(),
                store = store,
                clock = { Instant.fromEpochMilliseconds(NEW_MILLIS) },
            )

            val refused = assertIs<TaskSnapshot.Refused>(guard.create(SnapshotTrigger.BEFORE_AGENT_STEP))

            assertEquals(RunInterruptReason.NO_WORKSPACE, refused.reason)
        }
    }

    private fun guard(millis: Long): TaskSnapshotGuard =
        TaskSnapshotGuard(workspaces, store, clock = { Instant.fromEpochMilliseconds(millis) })

    /** Ставит ссылки прямо в репозитории: так вытеснение проверяется без сотни прогонов. */
    private fun seed(count: Int): List<String> = (0 until count).map { index ->
        val ref = snapshotRefName(BASE_MILLIS + index, "before-agent-step")
        assertEquals(SnapshotOutcome.Created, git.createSnapshot(ref), "посев снапшота обязан удаться")
        ref
    }

    /** Помечает все [refs] снапшотами одной задачи в статусе [status]: так проверяется защита «в использовании». */
    private fun protectAll(refs: List<String>, status: TaskStatus) {
        val task = StoreFixtures.task.copy(
            id = TaskId("t-protected"),
            status = status,
            runIds = listOf(RunId("r-protected")),
        )
        store.tasks.save(task)
        store.runs.save(
            StoreFixtures.run.copy(
                id = RunId("r-protected"),
                taskId = task.id,
                snapshots = refs.map(::SnapshotRef),
            ),
        )
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

    /** Репозиторий, у которого ссылка снапшота не записывается: так проверяется перевод отказа в код. */
    private class RefusingGit : GitRepository {

        override fun currentBranch(): String = "master"

        override fun headCommit(): String = "0000000"

        override fun changedFiles(): List<ChangedFile> = emptyList()

        override fun commitLog(limit: Int): List<CommitInfo> = emptyList()

        override fun ensureTaskBranch(branch: String): TaskBranchOutcome = TaskBranchOutcome.Existing

        override fun commitStep(branch: String, message: String): StepCommitOutcome =
            StepCommitOutcome.NothingToCommit

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

    private companion object {
        /** База для посеянных ссылок: их метки времени идут подряд от этой. */
        const val BASE_MILLIS = 1_758_535_200_000L

        /** Метка времени снапшота, который ставит сам guard в проверяемом сценарии. */
        const val SNAPSHOT_MILLIS = 1_758_535_200_000L

        /** Метка времени новых снапшотов: больше посеянных, поэтому они не самые старые. */
        const val NEW_MILLIS = 1_758_535_300_000L
    }
}
