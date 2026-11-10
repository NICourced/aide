package dev.aide.host.store

import app.cash.sqldelight.db.SqlDriver
import dev.aide.host.store.db.HostDatabase

/**
 * Точка входа в хранилище метаданных хоста (§ 8.3, § 9).
 *
 * Задачи, прогоны, вызовы инструментов, решения ревью, предложения и права хранятся в SQLite.
 * Код репозитория в базе не хранится — он живёт в git. Модули `client-*` к базе
 * не обращаются: им это запрещено правилом границ из задачи 4, а единственный
 * путь к этим данным для клиента — сообщения протокола.
 *
 * Доступ сгруппирован по сущностям — [tasks], [runs], [toolCalls], [decisions],
 * [proposals], [permissions]: шесть небольших наборов операций читаются лучше,
 * чем класс на шестнадцать разнородных методов.
 */
class HostStore internal constructor(
    database: HostDatabase,
    private val driver: SqlDriver? = null,
) : AutoCloseable {

    /** Задачи: постановка, чтение по идентификатору и по статусу. */
    val tasks: TaskStore = TaskStore(database)

    /** Прогоны агента: состояние, стоимость, история по задаче. */
    val runs: RunStore = RunStore(database)

    /** Вызовы инструментов: журнал прогона с фильтром по инструменту. */
    val toolCalls: ToolCallStore = ToolCallStore(database)

    /** Решения ревью: по пакету, по ревизии и по платформе клиента. */
    val decisions: DecisionStore = DecisionStore(database)

    /** Очередь неприменённых файловых предложений (T-1.15). */
    val proposals: ChangeProposalStore = ChangeProposalStore(database)

    /** Права на инструменты. */
    val permissions: PermissionStore = PermissionStore(database)

    /**
     * Закрывает соединение с базой.
     *
     * База, открытая в памяти драйвером, созданным вызывающей стороной, остаётся
     * на её попечении: [driver] равен null, и закрывать нужно драйвер.
     */
    override fun close() {
        driver?.close()
    }
}
