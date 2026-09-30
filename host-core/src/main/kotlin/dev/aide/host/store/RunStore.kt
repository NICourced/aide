package dev.aide.host.store

import dev.aide.domain.AgentRun
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.host.store.db.HostDatabase

/**
 * Прогоны агента в хранилище хоста (§ 4, FR-AGENT-10).
 *
 * Стоимость хранится дважды: числом в `cost_micros` (по нему идёт суммирование
 * в SQL) и целиком в `payload`. Флаг `cost_known` вынесен в колонку, потому что
 * по нему итог помечается неполным (FR-COST-5), а это фильтр, а не поле карточки.
 */
class RunStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет прогон; повторная запись с тем же `id` заменяет прежнюю. */
    fun save(run: AgentRun) {
        database.agentRunQueries.insert(
            id = run.id.value,
            task_id = run.taskId.value,
            state = run.state.name,
            mode = run.mode.name,
            started_at = run.startedAt.toEpochMilliseconds(),
            finished_at = run.finishedAt?.toEpochMilliseconds(),
            elapsed_millis = run.elapsedMillis,
            cost_micros = run.cost.amountMicros,
            cost_known = if (run.cost.known) 1L else 0L,
            payload = StoreCodec.encode(AgentRun.serializer(), run),
        )
    }

    /** Читает прогон по идентификатору; неизвестный идентификатор даёт null. */
    fun load(id: RunId): AgentRun? =
        database.agentRunQueries.byId(id.value).executeAsOneOrNull()?.let(RowMapper::run)

    /** Читает прогоны задачи в порядке запуска. */
    fun byTask(taskId: TaskId): List<AgentRun> =
        database.agentRunQueries.byTask(taskId.value).executeAsList().map(RowMapper::run)

    /** Читает все прогоны хоста в порядке запуска — ответ на запрос состояния прогонов (T-1.1). */
    fun all(): List<AgentRun> = database.agentRunQueries.all().executeAsList().map(RowMapper::run)

    /**
     * Прогоны, не завершившиеся до остановки хоста (`finished_at` = null).
     *
     * Признак «незавершён» берётся из колонки, а не из состояния: завершённый прогон
     * обязан иметь `finishedAt`, и по нему восстановление отличает прерванный прогон
     * от законченного (T-1.1). Добавление запроса схему не меняет, миграция не нужна.
     */
    fun unfinished(): List<AgentRun> =
        database.agentRunQueries.unfinished().executeAsList().map(RowMapper::run)

    /**
     * Суммарная стоимость всех прогонов задачи.
     *
     * [Cost.known] = false, если хотя бы у одного прогона цена неизвестна: тогда
     * в [Cost.amountMicros] лежит сумма только известных частей, а потребитель
     * обязан показать итог как неполный (FR-AGENT-10, FR-COST-5).
     */
    fun totalCost(taskId: TaskId): Cost {
        val known = database.agentRunQueries.totalCostMicrosByTask(taskId.value).executeAsOne()
        val unknown = database.agentRunQueries.countUnknownCostByTask(taskId.value).executeAsOne()
        return Cost(amountMicros = known, known = unknown == 0L)
    }
}
