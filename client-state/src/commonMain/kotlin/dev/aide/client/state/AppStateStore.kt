package dev.aide.client.state

import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние экранов приложения, независимое от UI.
 *
 * Отдельный слой нужен по двум причинам: состояния можно тестировать без
 * Compose, и правила переходов («ошибка важнее данных», «нет связи не стирает
 * кэш») описаны в одном месте, а не разбросаны по composable-функциям.
 */
class AppStateStore {

    private val _treeState = MutableStateFlow<ScreenState<FileTreePayload>>(ScreenState.NoRepository)

    /** Состояние дерева файлов. */
    val treeState: StateFlow<ScreenState<FileTreePayload>> = _treeState.asStateFlow()

    private val _fileState = MutableStateFlow<ScreenState<FileContentPayload>>(ScreenState.Empty)

    /** Состояние просмотра файла. */
    val fileState: StateFlow<ScreenState<FileContentPayload>> = _fileState.asStateFlow()

    private val _hostState = MutableStateFlow<HostStatePayload?>(null)

    /** Состояние хоста для шапки: ветка, корень, режим. */
    val hostState: StateFlow<HostStatePayload?> = _hostState.asStateFlow()

    private val _selectedFile = MutableStateFlow<String?>(null)

    /** Путь выбранного файла; null, если файл не выбран. */
    val selectedFile: StateFlow<String?> = _selectedFile.asStateFlow()

    /** Последнее достоверное состояние связи; нужно, чтобы вернуться из [ScreenState.Offline]. */
    private var lastConnection: ConnectionState = ConnectionState.Idle

    /** Что из сессии уже показано: сравнение по ссылке отличает новый ответ от повторной публикации. */
    private var appliedTree: FileTreePayload? = null
    private var appliedFile: FileContentPayload? = null
    private var appliedHostState: HostStatePayload? = null

    /** Показывает загрузку дерева. */
    fun onTreeLoading() {
        _treeState.value = ScreenState.Loading
    }

    /** Принимает загруженное дерево; пустое дерево становится состоянием «пусто». */
    fun onTreeLoaded(tree: FileTreePayload) {
        _treeState.value = if (tree.entries.isEmpty()) ScreenState.Empty else ScreenState.Loaded(tree)
    }

    /** Превращает ошибку хоста в состояние экрана. */
    fun onTreeFailed(error: ProtocolError) {
        _treeState.value = error.toScreenState()
    }

    /** Выбирает файл и переводит просмотр в состояние загрузки; null снимает выбор. */
    fun selectFile(path: String?) {
        _selectedFile.value = path
        _fileState.value = if (path == null) ScreenState.Empty else ScreenState.Loading
    }

    /** Принимает содержимое файла. */
    fun onFileLoaded(content: FileContentPayload) {
        _fileState.value = ScreenState.Loaded(content)
    }

    /** Превращает ошибку чтения файла в состояние экрана. */
    fun onFileFailed(error: ProtocolError) {
        _fileState.value = error.toScreenState()
    }

    /** Сохраняет состояние хоста для шапки. */
    fun onHostState(state: HostStatePayload) {
        _hostState.value = state
    }

    /**
     * Принимает состояние сессии клиента целиком.
     *
     * Нужно после реконнекта: [HostClient] сам перезапрашивает дерево и открытый файл,
     * и без этой подписки обновлённые данные не дошли бы до экранов (T-0.10).
     *
     * Сравнение по ссылке, а не по значению, отличает новый ответ от повторной
     * публикации того же объекта: сессия меняется и по другим поводам (например,
     * обновилось только состояние хоста), и тогда перезаписывать экран нельзя —
     * иначе уже показанная ошибка затиралась бы старыми данными.
     */
    fun onHostSession(session: HostSession) {
        val tree = session.tree
        if (tree != null && tree !== appliedTree) {
            appliedTree = tree
            onTreeLoaded(tree)
        }
        val file = session.openFile
        if (file != null && file !== appliedFile) {
            appliedFile = file
            onFileLoaded(file)
        }
        val host = session.hostState
        if (host != null && host !== appliedHostState) {
            appliedHostState = host
            onHostState(host)
        }
    }

    /**
     * Реагирует на изменение связи.
     *
     * Правила: потеря связи переводит загруженные данные в [ScreenState.Offline],
     * сохраняя кэш; несовместимость версий перекрывает всё остальное, потому что
     * работать в этом состоянии нельзя; восстановление связи возвращает
     * загруженные данные из кэша, чтобы экран не мигал пустотой.
     */
    fun onConnectionState(state: ConnectionState) {
        val previous = lastConnection
        lastConnection = state

        when (state) {
            is ConnectionState.Incompatible -> {
                _treeState.value = ScreenState.Failed(
                    kind = ScreenState.ErrorKind.INCOMPATIBLE,
                    arguments = listOf(
                        state.reason.name,
                        state.clientVersion.toString(),
                        state.hostVersion.toString(),
                    ),
                )
            }

            is ConnectionState.Reconnecting -> {
                _treeState.value = _treeState.value.toOffline()
                _fileState.value = _fileState.value.toOffline()
            }

            is ConnectionState.Connected -> {
                if (previous is ConnectionState.Reconnecting) {
                    _treeState.value = _treeState.value.fromOffline()
                    _fileState.value = _fileState.value.fromOffline()
                }
            }

            is ConnectionState.Closed -> Unit
            ConnectionState.Idle, ConnectionState.Connecting -> Unit
        }
    }

    /**
     * Переводит загруженные данные в [ScreenState.Offline] по событию [HostEvent.HostShuttingDown].
     *
     * Сокет закроется и сам, но событие приходит раньше закрытия: так пользователь видит,
     * что связи нет, не дожидаясь таймаута транспорта.
     */
    fun onHostShuttingDown() {
        _treeState.value = _treeState.value.toOffline()
        _fileState.value = _fileState.value.toOffline()
    }
}

/**
 * Помечает загруженные данные как устаревшие, не теряя их: экран показывает кэш
 * с пометкой «нет связи», а не пустоту.
 */
private fun <T> ScreenState<T>.toOffline(): ScreenState<T> = when (this) {
    is ScreenState.Loaded -> ScreenState.Offline(data)
    is ScreenState.Offline -> this
    else -> this
}

/** Возвращает данные из кэша в обычное состояние после восстановления связи. */
private fun <T> ScreenState<T>.fromOffline(): ScreenState<T> = when (this) {
    is ScreenState.Offline -> ScreenState.Loaded(cached)
    else -> this
}

/**
 * Отображает ошибку хоста на состояние экрана.
 *
 * Тип ошибки задан протоколом, поэтому разбирать текст не нужно: «нет прав»,
 * «не git-репозиторий» и «путь не существует» приходят как разные типы, а не
 * как одно сообщение, в котором пришлось бы искать подстроки.
 *
 * Слои ниже передают только код и параметры (путь, причина): понятный пользователю
 * текст строит UI из ресурсов (NFR-13). Исключение — [ScreenState.ErrorKind.OTHER]:
 * там параметром идёт текст, пришедший от хоста, потому что такую ошибку
 * классифицировать нельзя.
 */
private fun ProtocolError.toScreenState(): ScreenState<Nothing> = when (this) {
    is ProtocolError.NotFound -> ScreenState.Failed(ScreenState.ErrorKind.PATH_MISSING, listOf(what))

    is ProtocolError.NotAGitRepository ->
        ScreenState.Failed(ScreenState.ErrorKind.NOT_A_REPOSITORY, listOf(path))

    is ProtocolError.AccessDenied -> ScreenState.NoPermission(path = path, reason = reason)

    is ProtocolError.WorkspaceClosed -> ScreenState.Failed(ScreenState.ErrorKind.WORKSPACE_CLOSED)

    is ProtocolError.NotImplemented -> ScreenState.Failed(ScreenState.ErrorKind.OTHER, listOf(what))

    is ProtocolError.Internal -> ScreenState.Failed(ScreenState.ErrorKind.OTHER, listOf(message), detail)
}
