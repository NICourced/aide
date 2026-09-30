package dev.aide.host.agent

import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.host.store.TaskStore

/** Порт задач поверх хранилища хоста (О-1); порядок очереди задаёт хранилище. */
class StoreTaskRepository(private val store: TaskStore) : TaskRepository {

    override fun save(task: Task) {
        store.save(task)
    }

    override fun load(id: TaskId): Task? = store.load(id)

    override fun unfinished(): List<Task> = store.unfinished()
}
