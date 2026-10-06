package dev.aide.host.agent

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.agent.InterruptedRuns
import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.TaskStash
import dev.aide.domain.AgentRun
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.host.git.GitCliFixture
import dev.aide.host.git.JGitRepository
import dev.aide.host.store.HostStore
import dev.aide.host.store.StoreFixtures
import dev.aide.host.store.db.HostDatabase
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
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.59: отложенные правки переживают падение хоста и возвращаются при следующем старте.
 *
 * Репозиторий настоящий: проверяется именно то, что ссылка git и запись в задаче переживают
 * перезапуск, — на заглушке это доказывало бы заглушку. Прогон помечается прерванным так же,
 * как это делает старт хоста.
 */
class StashRecoveryTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val git = JGitRepository.open(fixture.root)
    private val workspaces = OpenWorkspaces().also { it.add(openedWith()) }
    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private val events = RecordingTaskSink()
    private val sessions = ClientSessions()

    @AfterTest
    fun tearDown() {
        workspaces.close()
        driver.close()
        fixture.close()
    }

    private val guard get() = WorkStashGuard(workspaces, sessions)

    @Test
    fun `после падения хоста правки возвращаются при следующем старте`() {
        runBlocking {
            val task = runningTask()
            store.runs.save(interruptedCandidate())
            stashEditsFor(task)

            assertTrue(GitCliFixture.porcelainLines(fixture.root).isEmpty(), "прогон начался с чистого дерева")

            // Падение хоста: прогон помечается прерванным, задача — failed. Правки при этом
            // остаются отложенными: репозитория при старте ещё нет, вернуть их некуда.
            InterruptedRuns(StoreRunRepository(store.runs), StoreTaskRepository(store.tasks)).markInterrupted()
            assertTrue(
                GitCliFixture.porcelainLines(fixture.root).isEmpty(),
                "пометка прерванным правки не возвращает — это делает восстановление",
            )

            StashRecovery(guard, store, events).afterRestart()

            assertEquals("master", git.currentBranch(), "правки вернулись на ту ветку, где были")
            assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(fixture.root))
            assertNull(store.tasks.load(task.id)?.stashRef, "ссылка на отложенное очищена")
            assertEquals(TaskStatus.FAILED, store.tasks.load(task.id)?.status, "прерванный прогон остаётся failed")
        }
    }

    @Test
    fun `возврат не трогает правки идущего прогона`() {
        runBlocking {
            val task = runningTask()
            store.runs.save(interruptedCandidate())
            val stashed = stashEditsFor(task)

            // Прогон ещё идёт (не падал): возвращать его правки из-под него нельзя.
            StashRecovery(guard, store, events).afterRestart()

            assertTrue(
                GitCliFixture.porcelainLines(fixture.root).isEmpty(),
                "правки идущего прогона остаются в стороне",
            )
            assertEquals(stashed.stashRef, store.tasks.load(task.id)?.stashRef)
            assertTrue(events.tasks.isEmpty(), "идущий прогон не трогается вовсе")
        }
    }

    @Test
    fun `конфликт при возврате сохраняет отложенное и метит задачу кодом`() {
        runBlocking {
            GitCliFixture.run(listOf("git", "checkout", "-b", TASK_BRANCH), fixture.root)
            val task = runningTask(branch = TASK_BRANCH)
            val stashed = stashEditsFor(task)
            // Агент правит тот же файл и коммитит шаг — возврат на ту же ветку конфликтует.
            Files.writeString(fixture.root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")
            GitCliFixture.run(listOf("git", "add", "src/Login.kt"), fixture.root)
            GitCliFixture.run(listOf("git", "commit", "-m", "шаг агента"), fixture.root)

            StashRecovery(guard, store, events).afterRestart()

            val after = assertIs<Task>(store.tasks.load(task.id))
            assertEquals(RunInterruptReason.STASH_CONFLICT, after.failureReason, "конфликт обязан быть виден")
            assertEquals(stashed.stashRef, after.stashRef, "отложенное при конфликте не теряется")
            assertTrue(
                Files.readString(fixture.root.resolve("src/Login.kt")).contains("<<<<<<<"),
                "конфликт показан метками, а не проглочен",
            )
        }
    }

    /** Задача, у которой прогон идёт и уже отложил правки: так выглядит состояние на момент падения. */
    private suspend fun stashEditsFor(task: Task): Task {
        val stashed = assertIs<TaskStash.Stashed>(guard.stash(task.id))
        return task.copy(stashRef = stashed.ref, stashBranch = stashed.branch).also(store.tasks::save)
    }

    private fun runningTask(branch: String = "ai/t-1"): Task = StoreFixtures.task.copy(
        id = TaskId("t-1"),
        branch = branch,
        status = TaskStatus.RUNNING,
        failureReason = null,
    )

    /** Незавершённый прогон: его пометит прерванным старт хоста, как после падения. */
    private fun interruptedCandidate(): AgentRun = StoreFixtures.run.copy(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.RUNNING,
        finishedAt = null,
    )

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

    /** Приёмник, запоминающий события задач: по нему видно, что возврат доехал до клиента. */
    private class RecordingTaskSink : AgentEventSink {

        val tasks = mutableListOf<Task>()

        override suspend fun runStateChanged(run: AgentRun) = Unit

        override suspend fun taskStateChanged(task: Task) {
            tasks += task
        }
    }

    private companion object {
        /** Ветка задачи из соглашения § 8.3. */
        const val TASK_BRANCH = "ai/t-1"
    }
}
