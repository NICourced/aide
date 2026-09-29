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
    /** Последнее загруженное дерево воркспейса; null, пока дерево не запрошено. */
    val tree: FileTreePayload? = null,
    /** Последний открытый файл; null, если файл не выбран. */
    val openFile: FileContentPayload? = null,
    /** Последняя ошибка запроса; null, если ошибок нет. */
    val lastError: ProtocolError? = null,
)

/**
 * Типизированный доступ к хосту поверх [HostConnection].
 *
 * Отдельно решает задачу «после реконнекта клиент получает актуальное состояние»:
 * при переходе соединения в [ConnectionState.Connected] с признаком `reconnected`
 * клиент заново запрашивает состояние открытого воркспейса, дерево и открытый файл,
 * а не полагается на данные, полученные до обрыва.
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

    /** Запрашивает дерево файлов открытого воркспейса; результат сохраняется в [session]. */
    suspend fun fileTree(): Result<FileTreePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileTree(requestId, workspaceId))) {
            is HostMessage.Tree -> Result.success(response.tree)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос дерева")))
        }
    }.onSuccess { tree -> _session.value = _session.value.copy(tree = tree) }

    /** Запрашивает содержимое файла; результат сохраняется в [session] как открытый файл. */
    suspend fun fileContent(path: String): Result<FileContentPayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileContent(requestId, workspaceId, path))) {
            is HostMessage.Content -> Result.success(response.content)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос файла")))
        }
    }.onSuccess { content -> _session.value = _session.value.copy(openFile = content) }

    /** Закрывает выбранный файл: после этого реконнект его уже не перезапрашивает. */
    fun closeFile() {
        _session.value = _session.value.copy(openFile = null)
    }

    /** Запрашивает состояние хоста: ветку, корень, режим; результат сохраняется в [session]. */
    suspend fun hostState(): Result<HostStatePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.HostState(requestId, workspaceId))) {
            is HostMessage.State -> Result.success(response.state)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос состояния")))
        }
    }.onSuccess { state -> _session.value = _session.value.copy(hostState = state, lastError = null) }

    private suspend fun <T> call(block: suspend (WorkspaceId) -> Result<T>): Result<T> {
        val workspaceId = _session.value.workspaceId
            ?: return Result.failure(HostCallException(ProtocolError.NotFound("воркспейс не открыт")))
        return block(workspaceId).onFailure { error ->
            if (error is HostCallException) _session.value = _session.value.copy(lastError = error.error)
        }
    }

    /**
     * Дозапрашивает состояние хоста, дерево и открытый файл после реконнекта.
     *
     * Одного состояния хоста мало: пока связи не было, дерево и содержимое открытого
     * файла могли устареть, и экран показал бы старый кэш (T-0.10).
     */
    private suspend fun refreshAfterReconnect() {
        if (_session.value.workspaceId == null) return
        hostState()
        fileTree()
        _session.value.openFile?.path?.let { path -> fileContent(path) }
    }

    private fun nextRequestId(): RequestId = RequestId("req-${++sequence}")
}

/** Ошибка вызова хоста, несущая типизированную причину из протокола. */
class HostCallException(val error: ProtocolError) : Exception(error.toString())
