package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.host.store.db.HostDatabase
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories

/**
 * Создание и открытие базы метаданных хоста.
 *
 * База лежит в каталоге данных приложения, а не в воркспейсе: иначе она попадала
 * бы в `git status`, в коммиты и в снапшоты, а этого быть не должно (§ 9).
 */
object DatabaseFactory {

    /** Каталог данных приложения: `$XDG_DATA_HOME/aide` или `~/.local/share/aide`. */
    fun defaultDatabasePath(): Path {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".local", "share")
        return base.resolve("aide").resolve("host.db")
    }

    /**
     * Открывает базу: схему создаёт только для новой базы, старую доводит
     * миграциями до текущей версии.
     *
     * Версию схемы ведёт драйвер в `PRAGMA user_version`, а не код хоста: иначе
     * базу, созданную прошлым запуском, следующий запуск принял бы за пустую и
     * попытался накатить миграцию поверх уже добавленных колонок.
     */
    fun open(path: Path = defaultDatabasePath()): HostStore {
        path.parent?.createDirectories()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}", Properties(), HostDatabase.Schema)
        return HostStore(HostDatabase(driver), driver)
    }

    /** Открывает базу в памяти — для тестов и для пробного запуска. */
    fun openInMemory(): HostStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY, Properties(), HostDatabase.Schema)
        return HostStore(HostDatabase(driver), driver)
    }
}
