package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.WebSocketSession
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("dev.aide.client.state.KtorHostConnection")

/**
 * Соединение по WebSocket с автопереподключением.
 *
 * Переподключение — внешний цикл: любая ошибка чтения или записи завершает текущую
 * попытку, состояние переходит в [ConnectionState.Reconnecting], и через задержку
 * с экспоненциальным ростом цикл начинается заново. Приложение при этом не
 * перезапускается: очередь ожидающих запросов и подписчики живут дольше соединения.
 *
 * @param endpoint адрес вида `ws://127.0.0.1:8080/ws`. Отличается у локального и
 *   удалённого хоста только значением — кода это не касается.
 */
class KtorHostConnection(
    private val endpoint: String,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val clientVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    private val initialRetryMillis: Long = INITIAL_RETRY_MILLIS,
    private val maxRetryMillis: Long = MAX_RETRY_MILLIS,
) : HostConnection {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HostMessage>(extraBufferCapacity = EVENT_BUFFER_CAPACITY)
    override val events: SharedFlow<HostMessage> = _events.asSharedFlow()

    private val outgoing = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val pending = mutableMapOf<RequestId, CompletableDeferred<HostMessage>>()

    private var loop: Job? = null
    private var everConnected = false

    override fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var attempt = 0
            while (isActive) {
                attempt = runConnectionAttempt(attempt) ?: return@launch
            }
        }
    }

    override suspend fun stop() {
        loop?.cancel()
        loop = null
        httpClient.close()
        _state.value = ConnectionState.Closed("Соединение остановлено пользователем")
    }

    override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage? {
        val requestId = message.requestIdOrNull()
            ?: return null // Приветствие отправляет цикл соединения; отдельного ответа здесь нет.

        // Если соединение уже установлено, отправляем сразу; иначе кладём в очередь —
        // цикл соединения вышлет накопленное после подключения.
        val deferred = CompletableDeferred<HostMessage>()
        pending[requestId] = deferred
        outgoing.trySend(ProtocolCodec.encode(message))
        return withTimeoutOrNull(timeoutMillis) { deferred.await() }.also { pending.remove(requestId) }
    }

    /**
     * Одна попытка соединения. Возвращает номер следующей попытки либо null,
     * если цикл должен остановиться: соединение закрыто, либо хост сообщил
     * о несовместимости версий. Проверка нужна в обоих исходах попытки — и когда
     * `connectOnce` бросил исключение, и когда вернулся нормально (хост сам закрыл
     * соединение по протоколу), иначе `Incompatible` перетёрлось бы на `Reconnecting`.
     *
     * Ошибка ловится широко намеренно: транспорт бросает разные типы (обрыв, таймаут,
     * закрытие прокси), и любая из них означает лишь «попытка не удалась». Отмена
     * корутины через `ensureActive` не превращается в повторную попытку.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun runConnectionAttempt(previousAttempt: Int): Int? {
        _state.value = if (everConnected) {
            ConnectionState.Reconnecting(attempt = previousAttempt + 1, nextRetryMillis = 0)
        } else {
            ConnectionState.Connecting
        }
        return try {
            connectOnce()
            if (sessionIsFinished()) null else 0
        } catch (error: Throwable) {
            currentCoroutineContext().ensureActive()
            if (sessionIsFinished()) return null
            val attempt = previousAttempt + 1
            val wait = backoffMillis(attempt)
            logger.warn("Попытка подключения №$attempt не удалась: ${error.message}")
            _state.value = ConnectionState.Reconnecting(attempt = attempt, nextRetryMillis = wait)
            delay(wait)
            attempt
        }
    }

    /** true, если сессия завершена по протоколу и переподключаться не нужно. */
    private fun sessionIsFinished(): Boolean =
        _state.value is ConnectionState.Incompatible || _state.value is ConnectionState.Closed

    private suspend fun connectOnce() {
        val session = httpClient.webSocketSession(endpoint)
        try {
            session.send(Frame.Binary(true, ProtocolCodec.encode(ClientMessage.Hello(clientVersion))))

            // Отправляем накопленные запросы, включая те, что клиент поставил в очередь до подключения.
            val pump = scope.launch { pumpOutgoing(session) }
            try {
                readLoop(session)
            } finally {
                pump.cancel()
            }
        } finally {
            session.close(CloseReason(CloseReason.Codes.NORMAL, "Соединение закрыто"))
        }
    }

    private suspend fun pumpOutgoing(session: WebSocketSession) {
        for (bytes in outgoing) session.send(Frame.Binary(true, bytes))
    }

    private suspend fun readLoop(session: WebSocketSession) {
        for (frame in session.incoming) {
            if (frame is Frame.Binary) handleFrame(frame.readBytes())
        }
    }

    private suspend fun handleFrame(bytes: ByteArray) {
        when (val decoded = ProtocolCodec.decodeHostMessage(bytes)) {
            is DecodeResult.Ignored -> {
                // Неизвестное сообщение не роняет соединение: оно логируется и пропускается (T-0.8).
                logger.warn("Пропущено сообщение хоста: ${decoded.reason} (тип: ${decoded.rawType})")
            }

            is DecodeResult.Message -> {
                val message = decoded.message
                when (message) {
                    is HostMessage.Hello -> {
                        val reconnected = everConnected
                        everConnected = true
                        _state.value = ConnectionState.Connected(
                            sessionId = message.sessionId,
                            reconnected = reconnected,
                        )
                    }

                    is HostMessage.Incompatible -> {
                        _state.value = ConnectionState.Incompatible(
                            reason = message.reason,
                            clientVersion = clientVersion,
                            hostVersion = message.hostVersion,
                        )
                    }

                    else -> {
                        val requestId = message.requestIdOrNull()
                        val waiter = requestId?.let { pending.remove(it) }
                        if (waiter != null) waiter.complete(message) else _events.tryEmit(message)
                    }
                }
            }
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        var millis = initialRetryMillis
        repeat(attempt - 1) { millis = min(millis * 2, maxRetryMillis) }
        return min(millis, maxRetryMillis)
    }

    companion object {
        /** Клиент по умолчанию: движок websockets. */
        fun defaultHttpClient(): HttpClient = HttpClient { install(WebSockets) }

        /** Задержка перед первой повторной попыткой. */
        const val INITIAL_RETRY_MILLIS: Long = 250

        /** Потолок экспоненциальной задержки между попытками. */
        const val MAX_RETRY_MILLIS: Long = 5_000

        /** Буфер событий хоста до подписки; события сверх него теряются, не блокируя чтение. */
        private const val EVENT_BUFFER_CAPACITY: Int = 64
    }
}

/** Идентификатор запроса, если сообщение является ответом; null для событий. */
internal fun HostMessage.requestIdOrNull(): RequestId? = when (this) {
    is HostMessage.WorkspaceOpened -> requestId
    is HostMessage.Tree -> requestId
    is HostMessage.Content -> requestId
    is HostMessage.State -> requestId
    is HostMessage.Failure -> requestId
    else -> null
}

/** Идентификатор запроса, если сообщение клиента является запросом; null для приветствия. */
internal fun ClientMessage.requestIdOrNull(): RequestId? = when (this) {
    is ClientMessage.OpenWorkspace -> requestId
    is ClientMessage.FileTree -> requestId
    is ClientMessage.FileContent -> requestId
    is ClientMessage.HostState -> requestId
    is ClientMessage.Hello -> null
}
