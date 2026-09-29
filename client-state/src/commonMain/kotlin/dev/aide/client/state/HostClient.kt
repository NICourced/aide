package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import kotlin.random.Random
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
 *
 * @param requestIdPrefix префикс идентификаторов запросов этой сессии клиента. Счётчик
 *   внутри одного запуска обеспечивает уникальность запросов, а префикс — уникальность
 *   между запусками: кэш ответов живёт столько же, сколько хост, и без префикса новый
 *   процесс клиента повторил бы чужой `req-1` и получил чужой ответ. По умолчанию
 *   случайный; в тестах задаётся явно.
 */
class HostClient(
    private val connection: HostConnection,
    private val scope: CoroutineScope,
    private val requestIdPrefix: String = newRequestIdPrefix(),
) {

    private val _session = MutableStateFlow(HostSession())
    val session: StateFlow<HostSession> = _session.asStateFlow()

    private val _hostShuttingDown = MutableStateFlow(false)

    /** true, если хост прислал [HostEvent.HostShuttingDown]; сбрасывается после реконнекта. */
    val hostShuttingDown: StateFlow<Boolean> = _hostShuttingDown.asStateFlow()

    private var sequence = 0

    /**
     * Подписывается на состояние соединения и события хоста.
     *
     * Событие [HostEvent.WorkspaceChanged] заставляет перезапросить состояние открытого
     * воркспейса; [HostEvent.HostShuttingDown] сразу помечает сессию, чтобы UI перешёл
     * в состояние «нет связи», не дожидаясь закрытия сокета.
     */
    fun start() {
        connection.start()
        scope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected && state.reconnected) {
                    refreshWorkspace()
                }
            }
        }
        scope.launch {
            connection.events.collect { message ->
                val event = (message as? HostMessage.Event)?.event ?: return@collect
                when (event) {
                    is HostEvent.WorkspaceChanged -> {
                        if (event.workspaceId == _session.value.workspaceId) refreshWorkspace()
                    }

                    HostEvent.HostShuttingDown -> _hostShuttingDown.value = true
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
            ?: return Result.failure(HostCallException(ProtocolError.Internal("воркспейс не открыт")))
        return block(workspaceId).onFailure { error ->
            if (error is HostCallException) _session.value = _session.value.copy(lastError = error.error)
        }
    }

    /**
     * Дозапрашивает состояние хоста, дерево и открытый файл.
     *
     * Нужно и после реконнекта, и по событию [HostEvent.WorkspaceChanged]: пока связи
     * не было или воркспейс менялся, дерево и содержимое открытого файла могли устареть,
     * и экран показал бы старый кэш (T-0.10).
     */
    private suspend fun refreshWorkspace() {
        if (_session.value.workspaceId == null) return
        _hostShuttingDown.value = false
        hostState()
        fileTree()
        _session.value.openFile?.path?.let { path -> fileContent(path) }
    }

    private fun nextRequestId(): RequestId = RequestId("$requestIdPrefix-${++sequence}")

    companion object {
        /** Основание системы счисления для короткого префикса. */
        private const val HEX_RADIX = 16

        /**
         * Случайный префикс запуска клиента: делает идентификаторы запросов уникальными
         * между перезапусками процесса, пока живёт кэш ответов на хосте.
         */
        private fun newRequestIdPrefix(): String = "req-" + Random.nextLong().toULong().toString(HEX_RADIX)
    }
}

/** Ошибка вызова хоста, несущая типизированную причину из протокола. */
class HostCallException(val error: ProtocolError) : Exception(error.toString())
