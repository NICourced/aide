package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Что клиент знает о хосте прямо сейчас. */
data class HostSession(
    /** Открытый воркспейс; null, если ни один не открыт. */
    val workspaceId: WorkspaceId? = null,
    /** Состояние хоста: ветка, корень, режим. */
    val hostState: HostStatePayload? = null,
    /** Последняя ошибка запроса; null, если ошибок нет. */
    val lastError: ProtocolError? = null,
)

/**
 * Типизированный доступ к хосту поверх [HostConnection].
 *
 * Отдельно решает задачу «после реконнекта клиент получает актуальное состояние»:
 * при переходе соединения в [ConnectionState.Connected] с признаком `reconnected`
 * клиент заново запрашивает состояние открытого воркспейса, а не полагается на
 * данные, полученные до обрыва.
 */
class HostClient(
    private val connection: HostConnection,
    private val scope: CoroutineScope,
) {

    private val _session = MutableStateFlow(HostSession())
    val session: StateFlow<HostSession> = _session.asStateFlow()

    private var sequence = 0

    /** Подписывается на состояние соединения и выполняет дозапрос после реконнекта. */
    fun start() {
        connection.start()
        scope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected && state.reconnected) {
                    refreshAfterReconnect()
                }
            }
        }
    }

    /** Открывает репозиторий по пути; возвращает идентификатор воркспейса или null при ошибке. */
    suspend fun openWorkspace(path: String): WorkspaceId? {
        val requestId = nextRequestId()
        val response = connection.request(ClientMessage.OpenWorkspace(requestId, path))
        return when (response) {
            is HostMessage.WorkspaceOpened -> {
                _session.value = _session.value.copy(workspaceId = response.workspaceId, lastError = null)
                response.workspaceId
            }

            is HostMessage.Failure -> {
                _session.value = _session.value.copy(lastError = response.error)
                null
            }

            else -> null
        }
    }

    /** Запрашивает дерево файлов открытого воркспейса. */
    suspend fun fileTree(): Result<FileTreePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileTree(requestId, workspaceId))) {
            is HostMessage.Tree -> Result.success(response.tree)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос дерева")))
        }
    }

    /** Запрашивает содержимое файла. */
    suspend fun fileContent(path: String): Result<FileContentPayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileContent(requestId, workspaceId, path))) {
            is HostMessage.Content -> Result.success(response.content)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос файла")))
        }
    }

    /** Запрашивает состояние хоста: ветку, корень, режим. */
    suspend fun hostState(): Result<HostStatePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.HostState(requestId, workspaceId))) {
            is HostMessage.State -> Result.success(response.state)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос состояния")))
        }
    }

    private suspend fun <T> call(block: suspend (WorkspaceId) -> Result<T>): Result<T> {
        val workspaceId = _session.value.workspaceId
            ?: return Result.failure(HostCallException(ProtocolError.NotFound("воркспейс не открыт")))
        return block(workspaceId).onFailure { error ->
            if (error is HostCallException) _session.value = _session.value.copy(lastError = error.error)
        }
    }

    private suspend fun refreshAfterReconnect() {
        val workspaceId = _session.value.workspaceId ?: return
        hostState().onSuccess { _session.value = _session.value.copy(hostState = it, lastError = null) }
    }

    private fun nextRequestId(): RequestId = RequestId("req-${++sequence}")
}

/** Ошибка вызова хоста, несущая типизированную причину из протокола. */
class HostCallException(val error: ProtocolError) : Exception(error.toString())
