package dev.aide.client.state

import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId

/**
 * Запросы отката к снапшоту: список, предпросмотр и сам откат (T-1.6).
 *
 * Отдельный объект, а не методы [HostClient]: у отката свой повод меняться — он читается
 * и изменяет репозиторий, — а держать это в классе доступа к хосту значило бы растить его
 * до порога `TooManyFunctions`. Общий у них только транспорт, поэтому идентификаторы
 * запросов приходят сюда функцией.
 */
class SnapshotRollbackClient(
    private val connection: HostConnection,
    private val nextRequestId: suspend () -> RequestId,
) {

    /** Запрашивает список снапшотов воркспейса. */
    suspend fun list(workspaceId: WorkspaceId): Result<List<Snapshot>> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.SnapshotList(requestId, workspaceId))) {
            is HostMessage.SnapshotListResponse -> Result.success(response.snapshots)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на список снапшотов")))
        }
    }

    /** Запрашивает предпросмотр отката к снапшоту. */
    suspend fun preview(workspaceId: WorkspaceId, ref: SnapshotRef): Result<SnapshotPreview> {
        val requestId = nextRequestId()
        val message = ClientMessage.SnapshotPreviewRequest(requestId, workspaceId, ref)
        return when (val response = connection.request(message)) {
            is HostMessage.SnapshotPreviewResponse -> Result.success(response.preview)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(
                HostCallException(ProtocolError.Internal("Хост не ответил на предпросмотр снапшота")),
            )
        }
    }

    /**
     * Откатывает рабочее дерево к снапшоту.
     *
     * [expectedHead] — хеш HEAD на момент предпросмотра; хост отклоняет откат, если HEAD
     * изменился, чтобы пользователь не подтверждал устаревший предпросмотр.
     *
     * При успехе возвращает [SnapshotRevertResult] с исходной ссылкой и хешем созданного
     * обратного коммита, чтобы UI мог показать пользователю результат.
     */
    suspend fun revert(
        workspaceId: WorkspaceId,
        ref: SnapshotRef,
        expectedHead: String,
    ): Result<SnapshotRevertResult> {
        val requestId = nextRequestId()
        val message = ClientMessage.SnapshotRevert(requestId, workspaceId, ref, expectedHead)
        return when (val response = connection.request(message)) {
            is HostMessage.SnapshotReverted -> Result.success(
                SnapshotRevertResult(ref = response.ref, commit = response.commit),
            )

            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(
                HostCallException(ProtocolError.Internal("Хост не ответил на откат к снапшоту")),
            )
        }
    }
}

/**
 * Результат успешного отката к снапшоту (T-1.6).
 *
 * [ref] — снапшот, к которому откатывались; [commit] — полный хеш обратного коммита,
 * который теперь является HEAD.
 */
data class SnapshotRevertResult(
    val ref: SnapshotRef,
    val commit: String,
)
