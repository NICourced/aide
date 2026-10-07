package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.ClientPlatform
import dev.aide.domain.DecisionValue
import dev.aide.domain.HunkId
import dev.aide.domain.PacketId
import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolOutcome
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class DatabaseFactoryTest {

    @Test
    fun `новая база создаётся в текущей версии`() {
        val directory = Files.createTempDirectory("aide-store-fresh-")
        val path = directory.resolve("host.db")
        try {
            DatabaseFactory.open(path).use { store ->
                store.tasks.save(StoreFixtures.task)
                assertEquals(StoreFixtures.task, store.tasks.load(StoreFixtures.task.id))
            }

            assertEquals(3L, userVersionOf(path))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `открытая база читается после повторного открытия`() {
        val directory = Files.createTempDirectory("aide-store-reopen-")
        val path = directory.resolve("host.db")
        try {
            DatabaseFactory.open(path).use { store ->
                store.tasks.save(StoreFixtures.task)
                store.runs.save(StoreFixtures.run)
                store.permissions.save(StoreFixtures.toolPermission)
            }

            DatabaseFactory.open(path).use { store ->
                assertEquals(StoreFixtures.task, store.tasks.load(StoreFixtures.task.id))
                assertEquals(StoreFixtures.run, store.runs.load(StoreFixtures.run.id))
                assertEquals(StoreFixtures.toolPermission, store.permissions.load("fs.write"))
                assertEquals(1L, store.tasks.count())
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `база предыдущей версии доводится миграцией и сохраняет записи`() {
        val directory = Files.createTempDirectory("aide-store-legacy-")
        val path = directory.resolve("host.db")
        try {
            // База версии 1: схема без payload, по записи в каждую таблицу и метка версии.
            val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
            try {
                StoreTestSupport.createLegacyDatabase(legacyDriver)
                StoreTestSupport.insertLegacyRows(legacyDriver)
                assertEquals(1L, StoreTestSupport.userVersion(legacyDriver))
            } finally {
                legacyDriver.close()
            }

            // Открытие хоста после обновления: миграция 1 → 3 (1 → 2 → 3) на непустой базе.
            DatabaseFactory.open(path).use { store ->
                val task = assertNotNull(store.tasks.load(TaskId("t-legacy")))
                assertEquals("Старая задача", task.title)
                assertEquals(TaskStatus.REVIEW, task.status)
                assertEquals("ai/t-legacy", task.branch)
                assertEquals(Instant.fromEpochMilliseconds(1_758_535_200_000), task.createdAt)

                val run = assertNotNull(store.runs.load(RunId("r-legacy")))
                assertEquals(TaskId("t-legacy"), run.taskId)
                assertEquals(RunState.FINISHED, run.state)
                assertEquals(12_500, run.cost.amountMicros)
                assertTrue(run.cost.known)

                val call = store.toolCalls.forRun(RunId("r-legacy")).single()
                assertEquals("fs.write", call.tool)
                assertEquals(ToolOutcome.SUCCESS, call.outcome)
                assertTrue(call.requiredApproval)

                val decision = store.decisions.forPacket(PacketId("p-legacy")).single()
                assertEquals(DecisionValue.ACCEPTED, decision.value)
                assertEquals(ClientPlatform.ANDROID, decision.clientPlatform)
                assertEquals(HunkId("h-1"), decision.targetHunkId)

                val permission = assertNotNull(store.permissions.load("fs.read"))
                assertEquals(Permission.ALLOW, permission.read)
                assertEquals(Permission.DENY, permission.write)

                // Колонка payload, появившаяся миграцией, работает: новая запись читается вместе со старой.
                store.tasks.save(StoreFixtures.queuedTask)
                assertEquals(2L, store.tasks.count())
            }

            assertEquals(3L, userVersionOf(path))
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    @Test
    fun `база в памяти принимает записи`() {
        DatabaseFactory.openInMemory().use { store ->
            store.tasks.save(StoreFixtures.task)
            assertEquals(StoreFixtures.task, store.tasks.load(StoreFixtures.task.id))
        }
    }

    private fun userVersionOf(path: Path): Long {
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
        try {
            return StoreTestSupport.userVersion(driver)
        } finally {
            driver.close()
        }
    }
}
