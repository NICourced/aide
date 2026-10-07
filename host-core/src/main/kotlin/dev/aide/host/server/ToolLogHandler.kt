package dev.aide.host.server

import dev.aide.host.store.TOOL_CALL_PAGE_DEFAULT_LIMIT
import dev.aide.host.store.TOOL_CALL_PAGE_MAX_LIMIT
import dev.aide.host.store.ToolCallStore
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallSummary
import dev.aide.protocol.requestIdOrNull

/**
 * Обработчик журнала вызовов (T-1.3).
 *
 * Отдельная группа, а не строки в `AgentRunHandler`: у журнала свой повод меняться —
 * он читается страницами по курсору и отдаёт полное содержимое отдельным запросом, тогда
 * как обработчик агента отвечает за постановку задач и состояния прогонов.
 */
class ToolLogHandler(private val store: ToolCallStore) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.ToolCalls -> page(message)
        is ClientMessage.ToolCallDetail -> detail(message)

        // «Чужое» сообщение сюда попадает только из-за ошибки маршрутизации; отвечаем
        // его же идентификатором, иначе клиент ждал бы ответа до таймаута.
        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("tool-log"),
            error = ProtocolError.Internal("ToolLogHandler получил сообщение другого вида"),
        )
    }

    /**
     * Отдаёт страницу превью от новых к старым.
     *
     * Размер ограничен сверху ([TOOL_CALL_PAGE_MAX_LIMIT]): клиент не может попросить весь
     * журнал одним кадром. Значение вне диапазона не отвергается, а зажимается — это просьба
     * о размере, а не о содержимом, и отказ здесь ничего не добавил бы пользователю.
     */
    private fun page(message: ClientMessage.ToolCalls): HostMessage.ToolCallPage {
        val limit = (message.limit ?: TOOL_CALL_PAGE_DEFAULT_LIMIT).coerceIn(1, TOOL_CALL_PAGE_MAX_LIMIT)
        val page = store.page(message.runId, message.cursor, limit)
        val calls = page.calls.map { ToolCallSummary.of(it) }
        return HostMessage.ToolCallPage(
            requestId = message.requestId,
            runId = message.runId,
            calls = calls,
            nextCursor = if (page.hasMore) calls.last().let { ToolCallCursor(it.at, it.id) } else null,
            hasMore = page.hasMore,
        )
    }

    /** Отдаёт запись целиком — когда страница показала только превью. */
    private fun detail(message: ClientMessage.ToolCallDetail): HostMessage {
        val call = store.byId(message.callId)
            ?: return HostMessage.Failure(
                message.requestId,
                ProtocolError.NotFound("вызов ${message.callId.value}"),
            )
        return HostMessage.ToolCallContent(message.requestId, call)
    }
}
