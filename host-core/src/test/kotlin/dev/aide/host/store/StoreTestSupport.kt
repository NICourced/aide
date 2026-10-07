package dev.aide.host.store

import app.cash.sqldelight.db.QueryResult
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver

/**
 * Подпорки для тестов хранилища: схема предыдущей версии и прямые запросы к базе.
 *
 * Схема версии 1 берётся из тестового ресурса, а не из сгенерированной текущей
 * схемы: SQLDelight генерирует только текущую версию, и подсунуть её в тест
 * миграции значило бы проверять миграцию на уже мигрированной базе.
 */
internal object StoreTestSupport {

    /** DDL версии 1 — без колонок `payload`. */
    fun legacySchemaStatements(): List<String> =
        requireNotNull(StoreTestSupport::class.java.classLoader.getResourceAsStream("schema-v1.sql")) {
            "Не найден ресурс schema-v1.sql — без него миграция непроверяема"
        }.bufferedReader().readText()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    /** Создаёт базу версии 1: DDL без `payload` и метка версии в `PRAGMA user_version`. */
    fun createLegacyDatabase(driver: JdbcSqliteDriver) {
        legacySchemaStatements().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 1", 0)
    }

    /** По одной записи в каждую таблицу схемы версии 1 — у них ещё нет колонки `payload`. */
    fun insertLegacyRows(driver: JdbcSqliteDriver) {
        val inserts = listOf(
            """
            INSERT INTO task(id, title, prompt, branch, status, created_at)
            VALUES ('t-legacy', 'Старая задача', 'текст', 'ai/t-legacy', 'REVIEW', 1758535200000)
            """,
            """
            INSERT INTO agent_run(
                id, task_id, state, mode, started_at, finished_at, elapsed_millis, cost_micros, cost_known
            ) VALUES ('r-legacy', 't-legacy', 'FINISHED', 'ASK_BEFORE_CHANGES', 1758535200000, 1758535260000, 60000, 12500, 1)
            """,
            """
            INSERT INTO tool_call(id, run_id, tool, outcome, required_approval, duration_millis, at)
            VALUES ('tc-legacy', 'r-legacy', 'fs.write', 'SUCCESS', 1, 42, 1758535260000)
            """,
            """
            INSERT INTO review_decision(
                packet_id, packet_revision, scope, target_hunk_id, value, client_platform, decided_at
            ) VALUES ('p-legacy', 1, 'HUNK', 'h-1', 'ACCEPTED', 'ANDROID', 1758535260000)
            """,
            """
            INSERT INTO tool_permission(tool, read_permission, write_permission)
            VALUES ('fs.read', 'ALLOW', 'DENY')
            """,
        )
        inserts.forEach { driver.execute(null, it.trimIndent(), 0) }
    }

    /** Версия схемы, записанная в базе. */
    fun userVersion(driver: SqlDriver): Long = long(driver, "PRAGMA user_version")

    /** Единственное число из запроса. */
    fun long(driver: SqlDriver, sql: String): Long =
        driver.executeQuery(
            null,
            sql,
            { cursor -> QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L) },
            0,
        ).value

    /** Имена колонок таблицы в порядке объявления. */
    fun columns(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            null,
            "PRAGMA table_info($table)",
            { cursor ->
                while (cursor.next().value) names += cursor.getString(1).orEmpty()
                QueryResult.Value(Unit)
            },
            0,
        )
        return names
    }

    /** Имена индексов таблицы: по ним видно, что миграция добавила составной индекс журнала. */
    fun indexes(driver: SqlDriver, table: String): List<String> {
        val names = mutableListOf<String>()
        driver.executeQuery(
            null,
            "PRAGMA index_list($table)",
            { cursor ->
                while (cursor.next().value) names += cursor.getString(1).orEmpty()
                QueryResult.Value(Unit)
            },
            0,
        )
        return names
    }
}
