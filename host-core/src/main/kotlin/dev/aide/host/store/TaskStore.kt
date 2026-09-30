package dev.aide.host.store

import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.host.store.db.HostDatabase

/**
 * Задачи в хранилище хоста (§ 4, § 8.3).
 *
 * Поля, по которым ищут (`id`, `status`, `created_at`), лежат в колонках, задача
 * целиком — в `payload`; поэтому фильтр по статусу работает индексом, а домен
 * остаётся единственным описанием объекта.
 */
class TaskStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет задачу; повторная запись с тем же `id` заменяет прежнюю. */
    fun save(task: Task) {
        database.taskQueries.insert(
            id = task.id.value,
            title = task.title,
            prompt = task.prompt,
            branch = task.branch,
            status = task.status.name,
            created_at = task.createdAt.toEpochMilliseconds(),
            payload = StoreCodec.encode(Task.serializer(), task),
        )
    }

    /** Читает задачу по идентификатору; неизвестный идентификатор даёт null. */
    fun load(id: TaskId): Task? =
        database.taskQueries.byId(id.value).executeAsOneOrNull()?.let(RowMapper::task)

    /** Читает задачи с указанным статусом, от новых к старым — сырьё инбокса ревью (§ 4). */
    fun byStatus(status: TaskStatus): List<Task> =
        database.taskQueries.byStatus(status.name).executeAsList().map(RowMapper::task)

    /**
     * Задачи, работа по которым ещё не завершена, в порядке постановки.
     *
     * «Не завершено» задано отрицанием завершённых статусов, поэтому новый незавершённый
     * статус не выпадет из очереди. Порядок возрастающий, а не как у [byStatus]: очередь —
     * FIFO, следующая задача берётся по времени постановки (О-8). Схему это не меняет.
     */
    fun unfinished(): List<Task> =
        database.taskQueries.unfinished().executeAsList().map(RowMapper::task)

    /**
     * Незакрытые задачи: не в терминальном состоянии, включая ожидающие ревью (T-1.19).
     *
     * Отличается от [unfinished] намеренно: в очередь берутся только задачи без прогона,
     * а здесь важны и те, что ждут ревью, — их снапшоты ещё нужны пользователю для отката.
     * «Не завершено» задано отрицанием завершённых статусов, поэтому новый незавершённый
     * статус не выпадет из защиты от вытеснения. Схему это не меняет.
     */
    fun unclosed(): List<Task> =
        database.taskQueries.unclosed().executeAsList().map(RowMapper::task)

    /** Все задачи в порядке постановки — снимок состояния агента (T-1.1). */
    fun all(): List<Task> = database.taskQueries.all().executeAsList().map(RowMapper::task)

    /** Удаляет задачу. Журнал её прогонов при этом не удаляется (§ 10.2). */
    fun delete(id: TaskId) {
        database.taskQueries.delete(id.value)
    }

    /** Сколько задач в базе. */
    fun count(): Long = database.taskQueries.count().executeAsOne()
}
