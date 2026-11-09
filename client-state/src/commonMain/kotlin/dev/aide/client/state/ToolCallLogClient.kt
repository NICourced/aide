package dev.aide.client.state

import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallScope

/**
 * Запросы журнала вызовов к хосту (T-1.3, T-1.14).
 *
 * Отдельный объект, а не методы [HostClient]: у журнала свой повод меняться — он читается
 * страницами, фильтруется по задаче и инструменту, и отдаёт полное содержимое отдельным
 * запросом, — а держать всё это в классе доступа к хосту значило бы растить его до порога
 * `TooManyFunctions`. Общий у них только транспорт, поэтому идентификаторы запросов приходят
 * сюда функцией.
 */
class ToolCallLogClient(
    private val connection: HostConnection,
    private val nextRequestId: suspend () -> RequestId,
) {

    /**
     * Запрашивает страницу журнала в выбранной области.
     *
     * Каждая страница уходит с новым `RequestId`: кэш ответов на хосте делает повтор
     * запроса идемпотентным и вернул бы прежнюю страницу, а не следующую.
     * [cursor] = null — первая (самая новая) страница; следующий курсор берётся из ответа.
     * [tool] = null — все инструменты.
     */
    suspend fun toolCalls(
        scope: ToolCallScope,
        tool: String? = null,
        cursor: ToolCallCursor? = null,
        limit: Int? = null,
    ): Result<HostMessage.ToolCallPage> {
        val requestId = nextRequestId()
        val message = ClientMessage.ToolCalls(requestId, scope, tool, cursor, limit)
        return when (val response = connection.request(message)) {
            is HostMessage.ToolCallPage -> Result.success(response)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос журнала")))
        }
    }

    /** Запрашивает полное содержимое вызова: страница несёт только превью. */
    suspend fun toolCallDetail(callId: ToolCallId): Result<ToolCall> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.ToolCallDetail(requestId, callId))) {
            is HostMessage.ToolCallContent -> Result.success(response.call)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос вызова")))
        }
    }
}
