package dev.aide.client.state

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.PlanDecision
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

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
    /** Прогоны хоста в порядке запуска; обновляются событиями и запросом состояния (T-1.1). */
    val runs: List<AgentRun> = emptyList(),
    /** Задачи хоста в порядке постановки; статус приходит событиями и запросом (T-1.1). */
    val tasks: List<Task> = emptyList(),
    /** Конфигурация моделей хоста; null, пока её не запросили (T-1.56). */
    val agentConfig: AgentConfig? = null,
    /** Каталог заготовок провайдеров (T-1.56). */
    val providerCatalog: List<ProviderCatalogEntry> = emptyList(),
    /** Последний результат проверки модели (T-1.56); null, если проверку не запускали. */
    val modelCheck: ModelCheckOutcome? = null,
    /** Состояние ключей провайдеров по идентификатору (T-1.58); значений ключей здесь нет. */
    val modelSecrets: Map<String, ModelSecretStatus> = emptyMap(),
    /** Последний отказ настроек моделей (T-1.56); null, если всё прошло. */
    val modelError: ModelConfigError? = null,
    /** Последняя ошибка запроса; null, если ошибок нет. */
    val lastError: ProtocolError? = null,
)

/**
 * Типизированный доступ к хосту поверх [HostConnection].
 *
 * Отдельно решает задачу «после реконнекта клиент получает актуальное состояние»:
 * при переходе соединения в [ConnectionState.Connected] с признаком `reconnected`
 * клиент заново запрашивает состояние открытого воркспейса, дерево, открытый файл
 * и состояние прогонов, а не полагается на данные, полученные до обрыва.
 *
 * Запросы клиент шлёт из нескольких корутин сразу — обработчика событий, экрана,
 * восстановления после обрыва, — поэтому общее состояние защищено: идентификаторы
 * запросов выдаются под замком, а сессия обновляется атомарно.
 *
 * @param requestIdPrefix префикс идентификаторов запросов этой сессии клиента. Счётчик
 *   внутри одного запуска обеспечивает уникальность запросов, а префикс — уникальность
 *   между запусками: кэш ответов живёт столько же, сколько хост, и без префикса новый
 *   процесс клиента повторил бы чужой `req-1` и получил чужой ответ. По умолчанию
 *   случайный; в тестах задаётся явно.
 */
class HostClient(
    internal val connection: HostConnection,
    private val scope: CoroutineScope,
    requestIdPrefix: String = newRequestIdPrefix(),
) {

    private val _session = MutableStateFlow(HostSession())
    val session: StateFlow<HostSession> = _session.asStateFlow()

    private val _hostShuttingDown = MutableStateFlow(false)

    /** true, если хост прислал [HostEvent.HostShuttingDown]; сбрасывается после реконнекта. */
    val hostShuttingDown: StateFlow<Boolean> = _hostShuttingDown.asStateFlow()

    /**
     * Живые записи журнала вызовов (T-1.3).
     *
     * Событие хоста без запроса: клиент не спрашивал про этот вызов, но обязан показать его
     * в логе сразу (не позже 500 мс). Состояние лога — отдельное от [HostSession] (страницы,
     * курсор, «есть ещё»), поэтому записи уходят потоком, а не в снимок сессии.
     */
    private val _toolCallEvents = MutableSharedFlow<ToolCall>(extraBufferCapacity = TOOL_CALL_EVENT_BUFFER)

    /** Записи журнала, приходящие событием; подписчик — состояние лога на клиенте. */
    val toolCallEvents: SharedFlow<ToolCall> = _toolCallEvents.asSharedFlow()

    /**
     * Выдача идентификаторов запросов. Запросы уходят из нескольких корутин одновременно,
     * и без замка `++sequence` теряет инкременты: два запроса получают один [RequestId],
     * ответ на первый достаётся второму, а первый вызывающий ждёт до таймаута. Хост при
     * этом считает второй запрос повтором и отдаёт чужой ответ.
     *
     * `internal`, а не `private`: решение по плану (T-1.2) живёт расширением в этом же файле,
     * потому что одиннадцатый метод класса перешагнул бы предел `TooManyFunctions`, который
     * проект ослаблять не разрешает.
     */
    internal val requestIds = RequestIdSequence(requestIdPrefix)

    /**
     * Замок согласования первого обновления после открытия воркспейса.
     *
     * Хост отправляет ответ на `OpenWorkspace` и рассылает [HostEvent.WorkspaceChanged]
     * подряд, а клиент разбирает их в разных корутинах — порядка между ними нет. Если событие
     * обработается раньше продолжения [openWorkspace], открытого воркспейса клиент ещё не
     * знает, и событие потерялось бы вместе с обновлением: дерево и состояние хоста остались
     * бы незапрошенными до следующего реконнекта. Поэтому событие, пришедшее без открытого
     * воркспейса, запоминается в [pendingWorkspaceChange] и исполняется тем из двоих, кто
     * окажется вторым.
     */
    private val workspaceChangeLock = Mutex()

    /** Воркспейс из [HostEvent.WorkspaceChanged], пришедшего до того, как клиент его узнал. */
    private var pendingWorkspaceChange: WorkspaceId? = null

    /**
     * Подписывается на состояние соединения и события хоста.
     *
     * Событие [HostEvent.WorkspaceChanged] заставляет перезапросить состояние открытого
     * воркспейса; [HostEvent.RunStateChanged] обновляет прогон в сессии; [HostEvent.HostShuttingDown]
     * сразу помечает сессию, чтобы UI перешёл в состояние «нет связи», не дожидаясь закрытия сокета.
     */
    fun start() {
        connection.start()
        scope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected && state.reconnected) {
                    refreshWorkspace()
                    agentStatus()
                }
            }
        }
        scope.launch {
            connection.events.collect { message ->
                val event = (message as? HostMessage.Event)?.event ?: return@collect
                when (event) {
                    is HostEvent.WorkspaceChanged -> {
                        // Событие могло прийти раньше ответа на открытие: тогда воркспейса клиент
                        // ещё не знает, и обновление обязан выполнить открывающий — см. openWorkspace.
                        val aboutOpenWorkspace = workspaceChangeLock.withLock {
                            val open = _session.value.workspaceId
                            if (open == null) {
                                pendingWorkspaceChange = event.workspaceId
                                false
                            } else {
                                open == event.workspaceId
                            }
                        }
                        if (aboutOpenWorkspace) refreshWorkspace()
                    }

                    is HostEvent.RunStateChanged -> _session.update { it.withRun(event.run) }

                    is HostEvent.TaskStateChanged -> _session.update { it.withTask(event.task) }

                    is HostEvent.ToolCallRecorded -> _toolCallEvents.emit(event.call)

                    HostEvent.HostShuttingDown -> _hostShuttingDown.value = true
                }
            }
        }
    }

    /** Открывает репозиторий по пути; возвращает идентификатор воркспейса или null при ошибке. */
    suspend fun openWorkspace(path: String): WorkspaceId? {
        val requestId = requestIds.next()
        val response = connection.request(ClientMessage.OpenWorkspace(requestId, path))
        return when (response) {
            is HostMessage.WorkspaceOpened -> {
                val announced = workspaceChangeLock.withLock {
                    _session.update { it.copy(workspaceId = response.workspaceId, lastError = null) }
                    val pending = pendingWorkspaceChange
                    pendingWorkspaceChange = null
                    pending
                }
                // Событие об этом же открытии могло прийти раньше ответа: тогда обновление,
                // обещанное обработчиком события, обязан выполнить именно открывающий — и ровно
                // один раз, иначе обновление либо потеряется, либо случится дважды. В фон, а не
                // здесь: вызывающий сразу после открытия сам запрашивает дерево и состояние
                // (`openRepository` в UI), и ожидание внутри открытия удлинило бы его вчетверо.
                if (announced == response.workspaceId) scope.launch { refreshWorkspace() }
                response.workspaceId
            }

            is HostMessage.Failure -> {
                _session.update { it.copy(lastError = response.error) }
                null
            }

            else -> null
        }
    }

    /** Запрашивает дерево файлов открытого воркспейса; результат сохраняется в [session]. */
    suspend fun fileTree(): Result<FileTreePayload> = _session.call { workspaceId ->
        val requestId = requestIds.next()
        when (val response = connection.request(ClientMessage.FileTree(requestId, workspaceId))) {
            is HostMessage.Tree -> Result.success(response.tree)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос дерева")))
        }
    }.onSuccess { tree -> _session.update { it.copy(tree = tree) } }

    /** Запрашивает содержимое файла; результат сохраняется в [session] как открытый файл. */
    suspend fun fileContent(path: String): Result<FileContentPayload> = _session.call { workspaceId ->
        val requestId = requestIds.next()
        when (val response = connection.request(ClientMessage.FileContent(requestId, workspaceId, path))) {
            is HostMessage.Content -> Result.success(response.content)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос файла")))
        }
    }.onSuccess { content -> _session.update { it.copy(openFile = content) } }

    /** Закрывает выбранный файл: после этого реконнект его уже не перезапрашивает. */
    fun closeFile() {
        _session.update { it.copy(openFile = null) }
    }

    /** Запрашивает состояние хоста: ветку, корень, режим; результат сохраняется в [session]. */
    suspend fun hostState(): Result<HostStatePayload> = _session.call { workspaceId ->
        val requestId = requestIds.next()
        when (val response = connection.request(ClientMessage.HostState(requestId, workspaceId))) {
            is HostMessage.State -> Result.success(response.state)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос состояния")))
        }
    }.onSuccess { state -> _session.update { it.copy(hostState = state, lastError = null) } }

    /** Ставит задачу в очередь на выполнение агентом (T-1.1). */
    suspend fun postTask(prompt: String, mode: AutonomyMode): Result<TaskId> {
        val requestId = requestIds.next()
        return when (val response = connection.request(ClientMessage.PostTask(requestId, prompt, mode))) {
            is HostMessage.TaskPosted -> Result.success(response.taskId)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на постановку задачи")))
        }
    }

    /**
     * Запрашивает состояние агента: прогоны и задачи; результат сохраняется в [session].
     *
     * Воркспейс не нужен: прогоны и задачи принадлежат хосту целиком, и подключившийся
     * позже клиент узнаёт текущее состояние именно так, а не из истории событий (T-1.1).
     */
    suspend fun agentStatus(): Result<Unit> {
        val requestId = requestIds.next()
        return when (val response = connection.request(ClientMessage.AgentStatus(requestId))) {
            is HostMessage.AgentSnapshot -> {
                _session.update { it.copy(runs = response.runs, tasks = response.tasks) }
                Result.success(Unit)
            }

            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос агента")))
        }
    }

    /** Пауза, продолжение или остановка прогона (T-1.1; действия в UI — T-1.4). */
    suspend fun controlRun(runId: RunId, command: RunCommand): Result<Unit> {
        val requestId = requestIds.next()
        return when (val response = connection.request(ClientMessage.RunControl(requestId, runId, command))) {
            is HostMessage.RunControlled -> Result.success(Unit)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на управление прогоном")))
        }
    }

    /**
     * Настройки моделей хоста (T-1.56).
     *
     * Отдельный объект, а не методы этого класса: доступ к хосту — про репозиторий и
     * прогоны, а настройка моделей — про конфигурацию, которую правят руками. Общий у них
     * только транспорт, поэтому идентификаторы запросов и сессия передаются сюда.
     */
    val models: ModelConfigClient = ModelConfigClient(connection, requestIds::next, _session)

    /**
     * Запросы журнала вызовов (T-1.3): страницы и полное содержимое.
     *
     * Отдельный объект по той же причине, что и [models]: держать запросы журнала здесь
     * значило бы вывести класс доступа к хосту за порог `TooManyFunctions`.
     */
    val toolLogClient: ToolCallLogClient = ToolCallLogClient(connection, requestIds::next)

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

    companion object {
        /** Основание системы счисления для короткого префикса. */
        private const val HEX_RADIX = 16

        /**
         * Буфер живых записей журнала. Подписчик (состояние лога) есть всегда, пока открыт
         * экран агента; запас нужен на всплеск вызовов, чтобы `emit` не подвешивал обработчик
         * событий соединения.
         */
        private const val TOOL_CALL_EVENT_BUFFER = 64

        /**
         * Случайный префикс запуска клиента: делает идентификаторы запросов уникальными
         * между перезапусками процесса, пока живёт кэш ответов на хосте.
         */
        private fun newRequestIdPrefix(): String = "req-" + Random.nextLong().toULong().toString(HEX_RADIX)
    }
}

/** Обновляет прогон в сессии, сохраняя порядок по времени старта. */
private fun HostSession.withRun(run: AgentRun): HostSession =
    copy(runs = (runs.filterNot { it.id == run.id } + run).sortedBy { it.startedAt })

/** Обновляет задачу в сессии, сохраняя порядок постановки. */
private fun HostSession.withTask(task: Task): HostSession =
    copy(tasks = (tasks.filterNot { it.id == task.id } + task).sortedBy { it.createdAt })

/**
 * Выполняет запрос по открытому воркспейсу.
 *
 * Ошибка запоминается в [HostSession.lastError], но возвращается и вызывающему: экран
 * показывает состояние, а вызывающий решает, как на него реагировать.
 */
private suspend fun <T> MutableStateFlow<HostSession>.call(
    block: suspend (WorkspaceId) -> Result<T>,
): Result<T> {
    val workspaceId = value.workspaceId
        ?: return Result.failure(HostCallException(ProtocolError.Internal("воркспейс не открыт")))
    return block(workspaceId).onFailure { error ->
        if (error is HostCallException) update { it.copy(lastError = error.error) }
    }
}

/** Ошибка вызова хоста, несущая типизированную причину из протокола. */
class HostCallException(val error: ProtocolError) : Exception(error.toString())

/**
 * Решение по показанному плану прогона (T-1.2): подтвердить или переделать с комментарием.
 *
 * Расширение, а не метод [HostClient]: одиннадцатый метод класса перешагнул бы предел
 * `TooManyFunctions`, который проект ослаблять не разрешает, — по той же причине рядом
 * живут отдельные объекты настроек моделей и журнала. Вызов на месте остаётся прежним:
 * `client.decidePlan(…)`. Успешный ответ — только подтверждение приёма; новое состояние
 * прогона приходит событием [HostEvent.RunStateChanged], как и у остальных переходов (О-8).
 */
suspend fun HostClient.decidePlan(runId: RunId, decision: PlanDecision): Result<Unit> {
    val requestId = requestIds.next()
    return when (val response = connection.request(ClientMessage.PlanDecision(requestId, runId, decision))) {
        is HostMessage.PlanDecided -> Result.success(Unit)
        is HostMessage.Failure -> Result.failure(HostCallException(response.error))
        else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на решение по плану")))
    }
}

/**
 * Выдача идентификаторов запросов под замком.
 *
 * Отдельный тип, а не поле [HostClient]: счётчик с замком — самостоятельная забота,
 * и вынесение её держит класс доступа к хосту в пределах числа функций, которое
 * проверяет линтер.
 */
internal class RequestIdSequence(private val prefix: String) {

    private var sequence = 0
    private val lock = Mutex()

    /** Следующий уникальный идентификатор запроса. */
    suspend fun next(): RequestId = lock.withLock { RequestId("$prefix-${++sequence}") }
}
