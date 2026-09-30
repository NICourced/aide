package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolVersion
import dev.aide.domain.TaskId
import dev.aide.protocol.SessionId
import dev.aide.protocol.WorkspaceId
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first

/**
 * Соединение-заглушка: тест сам решает, что вернуть и в каком порядке.
 *
 * Настоящий WebSocket задаёт последовательность «ответ, потом событие» лишь на уровне кадров:
 * клиент разбирает их в разных корутинах, и порядок обработки не определён. Заглушка позволяет
 * зафиксировать любой из порядков явно — [beforeRespond] выполняется до того, как ответ дойдёт
 * до клиента, и именно так проверяется поведение клиента при обоих порядках.
 */
internal class FakeHostConnection : HostConnection {

    /** Воркспейс, который заглушка объявляет открытым. */
    val workspaceId: WorkspaceId = WorkspaceId("ws-1")

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HostMessage>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<HostMessage> = _events.asSharedFlow()

    /** Что вернуть на запрос; по умолчанию — успешные ответы на все виды запросов. */
    var respond: (ClientMessage) -> HostMessage = ::defaultResponse

    /** Что выполнить перед ответом: сюда тест вставляет рассылку события хоста. */
    var beforeRespond: suspend (ClientMessage) -> Unit = {}

    /** Пришедшие запросы; канал, а не список: запросы могут идти из нескольких потоков. */
    private val received = Channel<ClientMessage>(capacity = Channel.UNLIMITED)

    override fun start() {
        // Первое подключение, а не восстановление после обрыва: дозапрос состояния
        // по признаку `reconnected` проверяется сквозным тестом этапа.
        _state.value = ConnectionState.Connected(sessionId = SessionId("session-fake"), reconnected = false)
    }

    override suspend fun stop() {
        _state.value = ConnectionState.Closed("остановлено тестом")
    }

    override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage? {
        received.send(message)
        beforeRespond(message)
        return respond(message)
    }

    /**
     * Рассылает событие хоста.
     *
     * Ждёт подписчика намеренно: у потока без подписчиков событие теряется, и тест проверял бы
     * не то, что задумано. Ради этого же теста `HostClient.start()` вызывается до [emit].
     */
    suspend fun emit(event: HostEvent) {
        _events.subscriptionCount.first { subscribers -> subscribers > 0 }
        _events.emit(HostMessage.Event(event))
    }

    /** Забирает уже пришедшие запросы, не дожидаясь новых. */
    fun takeRequests(): List<ClientMessage> =
        generateSequence { received.tryReceive().getOrNull() }.toList()

    private fun defaultResponse(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.Hello -> HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("session-fake"))

        is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, workspaceId)

        is ClientMessage.FileTree -> HostMessage.Tree(
            requestId = message.requestId,
            tree = FileTreePayload(
                workspaceId = workspaceId,
                rootPath = REPO_ROOT,
                entries = emptyList(),
                truncated = false,
            ),
        )

        is ClientMessage.FileContent -> HostMessage.Content(
            requestId = message.requestId,
            content = FileContentPayload(
                workspaceId = workspaceId,
                path = message.path,
                text = "fun login() = Unit",
                sizeBytes = FILE_SIZE_BYTES,
                truncated = false,
            ),
        )

        is ClientMessage.HostState -> HostMessage.State(
            requestId = message.requestId,
            state = HostStatePayload(
                workspaceId = workspaceId,
                rootPath = REPO_ROOT,
                branch = BRANCH,
                headCommit = HEAD_COMMIT,
                uptimeMillis = 1,
                mode = HostMode.LOCAL,
            ),
        )

        is ClientMessage.PostTask -> HostMessage.TaskPosted(message.requestId, TaskId("t-task"))

        is ClientMessage.AgentStatus -> HostMessage.AgentSnapshot(message.requestId, emptyList(), emptyList())

        is ClientMessage.RunControl -> HostMessage.RunControlled(message.requestId, message.runId)
    }

    private companion object {
        /** Буфер событий до подписки: без него событие, поданное раньше подписчика, теряется. */
        const val EVENT_BUFFER_CAPACITY = 16
        const val REPO_ROOT = "/projects/aide"
        const val BRANCH = "master"
        const val HEAD_COMMIT = "abc1234"
        const val FILE_SIZE_BYTES = 17L
    }
}
