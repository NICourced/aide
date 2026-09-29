package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.absolutePathString
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull

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

            assertEquals(2L, userVersionOf(path))
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
            // База версии 1: схема без payload, запись и метка версии.
            val legacyDriver = JdbcSqliteDriver("jdbc:sqlite:${path.absolutePathString()}")
            try {
                StoreTestSupport.createLegacyDatabase(legacyDriver)
                legacyDriver.execute(
                    null,
                    "INSERT INTO task(id, title, prompt, branch, status, created_at) " +
                        "VALUES ('t-legacy', 'Старая задача', 'текст', 'ai/t-legacy', 'REVIEW', 1758535200000)",
                    0,
                )
                assertEquals(1L, StoreTestSupport.userVersion(legacyDriver))
            } finally {
                legacyDriver.close()
            }

            // Открытие хоста после обновления: миграция 1 → 2 на непустой базе.
            DatabaseFactory.open(path).use { store ->
                val task = assertNotNull(store.tasks.load(TaskId("t-legacy")))
                assertEquals("Старая задача", task.title)
                assertEquals(TaskStatus.REVIEW, task.status)
                assertEquals("ai/t-legacy", task.branch)

                store.tasks.save(StoreFixtures.queuedTask)
                assertEquals(2L, store.tasks.count())
            }

            assertEquals(2L, userVersionOf(path))
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
