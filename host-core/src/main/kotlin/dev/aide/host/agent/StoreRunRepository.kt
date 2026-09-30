package dev.aide.host.agent

import dev.aide.agent.ports.RunRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.RunId
import dev.aide.host.store.RunStore

/**
 * Порт сохранения прогонов поверх хранилища хоста (О-1).
 *
 * Адаптер живёт в `host-core`, потому что база — его ответственность; рантайм
 * агента видит только интерфейс [RunRepository].
 */
class StoreRunRepository(private val store: RunStore) : RunRepository {

    override fun save(run: AgentRun) {
        store.save(run)
    }

    override fun load(id: RunId): AgentRun? = store.load(id)

    override fun unfinished(): List<AgentRun> = store.unfinished()
}
