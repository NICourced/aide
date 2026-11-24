package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DiffContextPayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId

/**
 * Запрос диапазона строк файла для раскрытия скрытого контекста diff (T-1.23).
 *
 * Отдельный объект, а не метод [HostClient]: у diff-вьюера свой набор запросов, и держать
 * его в классе доступа к хосту значило бы растить его до порога `TooManyFunctions`.
 */
class DiffContextClient(
    private val connection: HostConnection,
    private val nextRequestId: suspend () -> RequestId,
) {

    /** Запрашивает [lineCount] строк файла, начиная со [startLine]. */
    suspend fun context(
        workspaceId: WorkspaceId,
        path: String,
        startLine: Int,
        lineCount: Int,
    ): Result<DiffContextPayload> {
        val requestId = nextRequestId()
        val message = ClientMessage.DiffContextRequest(requestId, workspaceId, path, startLine, lineCount)
        return when (val response = connection.request(message)) {
            is HostMessage.DiffContextResponse -> Result.success(response.context)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос контекста")))
        }
    }
}
