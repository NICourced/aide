package dev.aide.agent

import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus

/**
 * Очередь задач: одна за раз (О-8).
 *
 * Порядок задаёт хранилище, а не это класс: `unfinished()` отдаёт задачи по времени
 * постановки, поэтому очередь — FIFO, восстанавливать порядок по полю не нужно.
 * Два одновременных прогона в одном воркспейсе гарантированно конфликтуют по файлам,
 * поэтому следующая задача берётся только когда предыдущая завершена.
 */
class RunQueue(private val tasks: TaskRepository) {

    /** Следующая задача к выполнению; null, если очередь пуста. */
    fun next(): Task? = tasks.unfinished().firstOrNull { it.status == TaskStatus.QUEUED }
}
