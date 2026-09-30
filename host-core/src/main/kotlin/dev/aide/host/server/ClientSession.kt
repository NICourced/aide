package dev.aide.host.server

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolCompatibility
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestDedupCache
import dev.aide.protocol.RequestId
import dev.aide.protocol.SessionId
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import java.util.UUID

/** Обработчик сообщений хоста, кроме приветствия: его разбирает сама сессия. */
fun interface ClientMessageHandler {
    /** Обрабатывает запрос и возвращает ответ. */
    suspend fun handle(message: ClientMessage): HostMessage
}

/**
 * Состояние одной сессии клиента на хосте.
 *
 * Отвечает за три вещи: приветствие и проверку версий, идемпотентность по `requestId`
 * и передачу остальных сообщений обработчику. Приветствие должно прийти первым —
 * иначе сессия отвечает ошибкой, не разбирая запросы (§ 8.4).
 *
 * Несовместимость версий — это причина закрыть соединение, а не ошибка отдельного
 * запроса: [onBytes] возвращает false, вызывающий код (см. [ProtocolServer]) закрывает
 * WebSocket, и дальнейшие кадры не обрабатываются вовсе — § 8.4 требует явной ошибки
 * вместо частично работающего соединения.
 *
 * [dedup] передаётся снаружи и не имеет значения по умолчанию: кэш обязан жить
 * дольше сессии, иначе реконнект его теряет. Владелец кэша — [ProtocolServer].
 */
class ClientSession(
    private val handler: ClientMessageHandler,
    private val hostVersion: ProtocolVersion,
    private val send: suspend (ByteArray) -> Unit,
    private val dedup: RequestDedupCache,
    /** Разослать событие всем сессиям; используется после открытия воркспейса. */
    private val broadcast: suspend (HostEvent) -> Unit,
) {

    private val logger: Logger = LoggerFactory.getLogger(ClientSession::class.java)

    /** Идентификатор сессии, выданный этому клиенту. */
    val sessionId: SessionId = SessionId(UUID.randomUUID().toString())

    private var greeted = false

    /** true после решения закрыть сессию: новые кадры не разбираются. */
    private var closeRequested = false

    /** Число запросов, дошедших до обработчика; по нему проверяется идемпотентность. */
    var handledRequests: Int = 0
        private set

    /**
     * Обрабатывает один кадр от клиента.
     *
     * @return true, если сессия продолжает работу; false, если её пора закрыть
     *   (в текущей реализации — только несовместимость версий, § 8.4).
     */
    suspend fun onBytes(bytes: ByteArray): Boolean {
        if (closeRequested) return false
        return when (val decoded = ProtocolCodec.decodeClientMessage(bytes)) {
            is DecodeResult.Ignored -> {
                logger.warn("Пропущено сообщение клиента: ${decoded.reason} (тип: ${decoded.rawType})")
                true
            }

            is DecodeResult.Message -> onMessage(decoded.message)
        }
    }

    private suspend fun onMessage(message: ClientMessage): Boolean {
        val requestId = message.requestIdOrNull()
        return when {
            message is ClientMessage.Hello -> greet(message)
            !greeted -> {
                sendUnGreetedFailure(requestId)
                true
            }

            requestId == null -> {
                logger.warn("Сообщение без requestId пропущено: $message")
                true
            }

            else -> {
                respond(requestId, message)
                true
            }
        }
    }

    private suspend fun greet(hello: ClientMessage.Hello): Boolean {
        val compatibility = ProtocolCompatibility.check(hello.clientVersion, hostVersion)
        return if (compatibility is ProtocolCompatibility.Incompatible) {
            logger.warn(
                "Несовместимые версии протокола: клиент ${hello.clientVersion}, хост $hostVersion — " +
                    "закрываю сессию ${sessionId.value}",
            )
            closeRequested = true
            send(ProtocolCodec.encode(ProtocolCompatibility.toHostMessage(compatibility, hostVersion)))
            false
        } else {
            greeted = true
            send(ProtocolCodec.encode(HostMessage.Hello(hostVersion = hostVersion, sessionId = sessionId)))
            true
        }
    }

    /** Отправляет уже закодированное сообщение этой сессии; используется для событий хоста. */
    suspend fun deliver(bytes: ByteArray) {
        if (!closeRequested) send(bytes)
    }

    private suspend fun sendUnGreetedFailure(requestId: RequestId?) {
        send(
            ProtocolCodec.encode(
                HostMessage.Failure(
                    requestId = requestId ?: RequestId("unknown"),
                    error = ProtocolError.Internal("Первым сообщением должно быть приветствие"),
                ),
            ),
        )
    }

    private suspend fun respond(requestId: RequestId, message: ClientMessage) {
        // Идемпотентность: повтор запроса возвращает прежний ответ и не выполняет операцию снова.
        val cached = dedup.get(requestId)
        if (cached != null) {
            logger.info("Повтор запроса $requestId — отдаю сохранённый ответ")
            send(cached)
        } else {
            val response = handler.handle(message)
            val encoded = ProtocolCodec.encode(response)
            dedup.put(requestId, encoded)
            handledRequests += 1
            send(encoded)
            // Открытие воркспейса меняет то, что видит клиент: остальные сессии (в том числе
            // эта) получают событие и перезапрашивают состояние (T-0.8, события хоста).
            if (response is HostMessage.WorkspaceOpened) {
                broadcast(HostEvent.WorkspaceChanged(response.workspaceId))
            }
        }
    }
}

/** Идентификатор запроса сообщения клиента; null для приветствия. */
internal fun ClientMessage.requestIdOrNull(): RequestId? = when (this) {
    is ClientMessage.OpenWorkspace -> requestId
    is ClientMessage.FileTree -> requestId
    is ClientMessage.FileContent -> requestId
    is ClientMessage.HostState -> requestId
    is ClientMessage.PostTask -> requestId
    is ClientMessage.AgentStatus -> requestId
    is ClientMessage.RunControl -> requestId
    is ClientMessage.AgentConfigRequest -> requestId
    is ClientMessage.SaveAgentConfig -> requestId
    is ClientMessage.CheckModel -> requestId
    is ClientMessage.Hello -> null
}
