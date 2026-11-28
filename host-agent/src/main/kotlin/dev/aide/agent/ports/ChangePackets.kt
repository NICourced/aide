package dev.aide.agent.ports

import dev.aide.domain.AgentRun
import dev.aide.domain.ChangePacket
import dev.aide.domain.Task

/**
 * Порт публикации пакета изменений после прогона (T-1.24).
 *
 * Движок знает только этот интерфейс: реализация живёт в `host-core` и умеет git,
 * SQLite и протокол. Порт возвращает сохранённую ревизию, чтобы движок мог
 * продолжить только после успешной сборки.
 */
interface ChangePackets {

    /** Собирает и сохраняет новую ревизию пакета; отказ сборки — исключение. */
    suspend fun publish(task: Task, run: AgentRun): ChangePacket
}
