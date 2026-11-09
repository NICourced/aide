package dev.aide.host.store

import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.host.store.db.HostDatabase
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallScope

/** Размер страницы журнала по умолчанию (T-1.3): столько влезает в кадр и рисуется списком. */
const val TOOL_CALL_PAGE_DEFAULT_LIMIT: Int = 20

/** Верхняя граница размера страницы (T-1.3): весь журнал одним кадром не отдаётся. */
const val TOOL_CALL_PAGE_MAX_LIMIT: Int = 100

/** Страница журнала: записи от новых к старых и признак «есть ещё старше». */
data class ToolCallPage(val calls: List<ToolCall>, val hasMore: Boolean)

/**
 * Вызовы инструментов в хранилище хоста (§ 4, T-1.14).
 *
 * Журнал читается по прогону и по задаче, фильтруется по инструменту — все поля
 * проиндексированы, потому что журнал аудита листается страницами.
 *
 * Страницы (T-1.3) читаются по курсору «время, идентификатор», а не по смещению:
 * живые записи добавляются сверху и `OFFSET` сдвигался бы, давая пропуски и дубли.
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

    /** Читает полную запись по идентификатору; неизвестный идентификатор даёт null (T-1.3). */
    fun byId(id: ToolCallId): ToolCall? =
        database.toolCallQueries.byId(id.value).executeAsOneOrNull()?.let(RowMapper::toolCall)

    /**
     * Читает страницу журнала от новых к старым (T-1.3, T-1.14).
     *
     * [before] — курсор предыдущей страницы: отдаются записи строго старше этой пары.
     * null означает первую (самую новую) страницу. [limit] ограничивает размер и обязан
     * быть не меньше единицы: нулевая или отрицательная страница — ошибка вызывающего,
     * а не «пустой журнал", и молча превращать её в пустой ответ значило бы прятать баг.
     *
     * Запрашивается [limit] + 1 строка, а «есть ещё» получается из факта её наличия:
     * на границе «всего ровно лимит» размер страницы ничего не говорит, и клиент показывал
     * бы лишнюю кнопку «показать ещё». Лишняя строка при этом не отдаётся.
     */
    fun page(scope: ToolCallScope, tool: String?, before: ToolCallCursor?, limit: Int): ToolCallPage {
        require(limit >= 1) { "размер страницы журнала должен быть не меньше 1, а передан $limit" }
        val probe = limit + 1
        val rows = when (scope) {
            is ToolCallScope.Run -> pageByRun(scope.runId, tool, before, probe)
            is ToolCallScope.Task -> pageByTask(scope.taskId, tool, before, probe)
        }
        return ToolCallPage(calls = rows.take(limit).map(RowMapper::toolCall), hasMore = rows.size > limit)
    }

    /** Возвращает distinct-имена инструментов в выбранной области (T-1.14). */
    fun tools(scope: ToolCallScope): List<String> = when (scope) {
        is ToolCallScope.Run -> database.toolCallQueries.toolsByRun(scope.runId.value).executeAsList()
        is ToolCallScope.Task -> database.toolCallQueries.toolsByTask(scope.taskId.value).executeAsList()
    }

    private fun pageByRun(
        runId: RunId,
        tool: String?,
        before: ToolCallCursor?,
        probe: Int,
    ): List<dev.aide.host.store.Tool_call> = if (before == null) {
        database.toolCallQueries.firstPage(runId.value, tool, probe.toLong()).executeAsList()
    } else {
        database.toolCallQueries.pageBefore(
            runId = runId.value,
            tool = tool,
            beforeAt = before.at.toEpochMilliseconds(),
            beforeId = before.id.value,
            limit = probe.toLong(),
        ).executeAsList()
    }

    private fun pageByTask(
        taskId: TaskId,
        tool: String?,
        before: ToolCallCursor?,
        probe: Int,
    ): List<dev.aide.host.store.Tool_call> = if (before == null) {
        database.toolCallQueries.firstPageByTask(taskId.value, tool, probe.toLong()).executeAsList()
    } else {
        database.toolCallQueries.pageBeforeByTask(
            taskId = taskId.value,
            tool = tool,
            beforeAt = before.at.toEpochMilliseconds(),
            beforeId = before.id.value,
            limit = probe.toLong(),
        ).executeAsList()
    }
}
