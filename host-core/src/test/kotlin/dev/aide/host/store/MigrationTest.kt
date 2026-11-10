package dev.aide.host.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.host.store.db.HostDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrationTest {

    @Test
    fun `ресурс версии 1 описывает прежнюю схему без payload`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        StoreTestSupport.createLegacyDatabase(driver)

        assertEquals(
            listOf("id", "title", "prompt", "branch", "status", "created_at"),
            StoreTestSupport.columns(driver, "task"),
        )
        assertEquals(
            listOf("tool", "read_permission", "write_permission"),
            StoreTestSupport.columns(driver, "tool_permission"),
        )
        assertEquals(1L, StoreTestSupport.userVersion(driver))

        driver.close()
    }

    @Test
    fun `миграция с версии 1 на версию 2 сохраняет записи`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 1. База версии 1: своя схема и своя метка версии.
        StoreTestSupport.createLegacyDatabase(driver)

        val taskId = "task-legacy"
        driver.execute(
            null,
            "INSERT INTO task(id, title, prompt, branch, status, created_at) " +
                "VALUES ('$taskId', 'Старая задача', 'текст', 'ai/$taskId', 'REVIEW', 1758535200000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO tool_permission(tool, read_permission, write_permission) " +
                "VALUES ('fs.read', 'ALLOW', 'DENY')",
            0,
        )

        // 2. Миграция до версии 2.
        HostDatabase.Schema.migrate(driver, 1, 2, *arrayOf<AfterVersion>())

        // 3. Записи на месте, и появилась колонка payload со значением по умолчанию.
        val database = HostDatabase(driver)
        val legacy = database.taskQueries.byId(taskId).executeAsOne()
        assertEquals("Старая задача", legacy.title)
        assertEquals("ai/$taskId", legacy.branch)
        assertTrue(legacy.payload.isEmpty(), "У записей версии 1 payload пустой")

        val permission = database.toolPermissionQueries.byTool("fs.read").executeAsOne()
        assertEquals("ALLOW", permission.read_permission)

        // 4. Новые записи после миграции пишутся и читаются.
        database.taskQueries.insert(
            id = "task-new",
            title = "Новая задача",
            prompt = "текст",
            branch = "ai/task-new",
            status = "QUEUED",
            created_at = 1_758_535_300_000,
            payload = byteArrayOf(1, 2, 3),
        )
        assertEquals(2, database.taskQueries.count().executeAsOne())
        assertEquals(3, database.taskQueries.byId("task-new").executeAsOne().payload.size)

        driver.close()
    }

    @Test
    fun `миграция с версии 2 на версию 3 добавляет индекс журнала и сохраняет записи`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 1. Непустая база после первой миграции — это состояние версии 2.
        StoreTestSupport.createLegacyDatabase(driver)
        HostDatabase.Schema.migrate(driver, 1, 2, *arrayOf<AfterVersion>())
        driver.execute(
            null,
            "INSERT INTO tool_call(id, run_id, tool, outcome, required_approval, duration_millis, at, payload) " +
                "VALUES ('tc-2', 'r-1', 'fs.write', 'SUCCESS', 1, 42, 1758535260000, X'')",
            0,
        )
        assertFalse(
            StoreTestSupport.indexes(driver, "tool_call").contains("tool_call_run_at"),
            "до миграции составного индекса быть не должно: ${StoreTestSupport.indexes(driver, "tool_call")}",
        )

        // 2. Миграция до версии 3.
        HostDatabase.Schema.migrate(driver, 2, 3, *arrayOf<AfterVersion>())

        // 3. Запись на месте, и появился составной индекс под страницы журнала.
        val database = HostDatabase(driver)
        assertEquals("fs.write", database.toolCallQueries.byId("tc-2").executeAsOne().tool)
        assertTrue(
            StoreTestSupport.indexes(driver, "tool_call").contains("tool_call_run_at"),
            "версия 2 обязана добавить индекс (run_id, at, id): ${StoreTestSupport.indexes(driver, "tool_call")}",
        )

        driver.close()
    }

    @Test
    fun `миграция с версии 3 на версию 4 добавляет таблицу предложений`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 1. База после второй миграции — состояние версии 3.
        StoreTestSupport.createLegacyDatabase(driver)
        HostDatabase.Schema.migrate(driver, 1, 2, *arrayOf<AfterVersion>())
        HostDatabase.Schema.migrate(driver, 2, 3, *arrayOf<AfterVersion>())
        assertFalse(
            StoreTestSupport.columns(driver, "change_proposal").contains("id"),
            "до миграции таблицы предложений быть не должно",
        )

        // 2. Миграция до версии 4.
        HostDatabase.Schema.migrate(driver, 3, 4, *arrayOf<AfterVersion>())

        // 3. Появилась таблица с нужными индексами.
        val database = HostDatabase(driver)
        database.changeProposalQueries.insert(
            id = "cp-1",
            run_id = "r-1",
            call_id = "tc-1",
            tool = "write_file",
            risk = "NORMAL",
            created_at = 1_758_535_200_000,
            payload = byteArrayOf(1, 2, 3),
        )
        assertEquals(1, database.changeProposalQueries.count().executeAsOne())
        assertTrue(
            StoreTestSupport.indexes(driver, "change_proposal").contains("change_proposal_run"),
            "версия 4 обязана добавить индекс по run_id",
        )
        assertTrue(
            StoreTestSupport.indexes(driver, "change_proposal").contains("change_proposal_call"),
            "версия 4 обязана добавить индекс по call_id",
        )

        driver.close()
    }

    @Test
    fun `чистая база создаётся в текущей версии и принимает записи`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HostDatabase.Schema.create(driver)
        val database = HostDatabase(driver)

        assertEquals(0, database.taskQueries.count().executeAsOne())
        database.taskQueries.insert(
            "t-1",
            "Задача",
            "текст",
            "ai/t-1",
            "QUEUED",
            1_758_535_200_000,
            byteArrayOf(),
        )
        assertEquals(1, database.taskQueries.count().executeAsOne())
        driver.close()
    }

    @Test
    fun `записи читаются тем же запросом после перезапуска хоста`() {
        val dbFile = java.nio.file.Files.createTempFile("aide-store-", ".db").toFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            run {
                val driver = JdbcSqliteDriver(url)
                HostDatabase.Schema.create(driver)
                val database = HostDatabase(driver)
                database.taskQueries.insert(
                    "t-restart", "До перезапуска", "текст", "ai/t-restart", "REVIEW", 1_758_535_200_000, byteArrayOf(9),
                )
                driver.close()
            }

            // Перезапуск: новый драйвер и новая обёртка над тем же файлом.
            val driver = JdbcSqliteDriver(url)
            val database = HostDatabase(driver)
            val beforeRestart = database.taskQueries.byId("t-restart").executeAsOne()
            assertEquals("До перезапуска", beforeRestart.title)
            assertEquals(1, beforeRestart.payload.size)

            // Запись, сделанная после перезапуска, читается вместе со старой: файл базы не пересоздавался.
            database.taskQueries.insert(
                "t-after-restart", "После перезапуска", "текст", "ai/t-after-restart", "QUEUED",
                1_758_535_400_000, byteArrayOf(7),
            )
            assertEquals(2, database.taskQueries.count().executeAsOne())
            assertEquals("После перезапуска", database.taskQueries.byId("t-after-restart").executeAsOne().title)
            driver.close()
        } finally {
            dbFile.delete()
        }
    }
}
