package dev.aide.host.store

import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.host.store.db.HostDatabase

/**
 * Вызовы инструментов в хранилище хоста (§ 4, T-1.14).
 *
 * Журнал читается по прогону и фильтруется по инструменту — оба поля
 * проиндексированы, потому что журнал аудита листается страницами.
 */
class ToolCallStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет вызов инструмента. */
    fun save(call: ToolCall) {
        database.toolCallQueries.insert(
            id = call.id.value,
            run_id = call.runId.value,
            tool = call.tool,
            outcome = call.outcome.name,
            required_approval = if (call.requiredApproval) 1L else 0L,
            duration_millis = call.durationMillis,
            at = call.at.toEpochMilliseconds(),
            payload = StoreCodec.encode(ToolCall.serializer(), call),
        )
    }

    /** Читает вызовы прогона в порядке выполнения; при [tool] не null — только по этому инструменту. */
    fun forRun(runId: RunId, tool: String? = null): List<ToolCall> {
        val rows = if (tool == null) {
            database.toolCallQueries.byRun(runId.value).executeAsList()
        } else {
            database.toolCallQueries.byRunAndTool(runId.value, tool).executeAsList()
        }
        return rows.map(RowMapper::toolCall)
    }
}
