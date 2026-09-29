package dev.aide.host.server

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DecodeResult
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
 */
class ClientSession(
    private val handler: ClientMessageHandler,
    private val hostVersion: ProtocolVersion,
    private val send: suspend (ByteArray) -> Unit,
    private val dedup: RequestDedupCache = RequestDedupCache(),
) {

    private val logger: Logger = LoggerFactory.getLogger(ClientSession::class.java)

    /** Идентификатор сессии, выданный этому клиенту. */
    val sessionId: SessionId = SessionId(UUID.randomUUID().toString())

    private var greeted = false

    /** Число запросов, дошедших до обработчика; по нему проверяется идемпотентность. */
    var handledRequests: Int = 0
        private set

    /** Обрабатывает один кадр от клиента. */
    suspend fun onBytes(bytes: ByteArray) {
        when (val decoded = ProtocolCodec.decodeClientMessage(bytes)) {
            is DecodeResult.Ignored -> logger.warn(
                "Пропущено сообщение клиента: ${decoded.reason} (тип: ${decoded.rawType})",
            )

            is DecodeResult.Message -> onMessage(decoded.message)
        }
    }

    private suspend fun onMessage(message: ClientMessage) {
        val requestId = message.requestIdOrNull()
        when {
            message is ClientMessage.Hello -> greet(message)
            !greeted -> sendUnGreetedFailure(requestId)
            requestId == null -> logger.warn("Сообщение без requestId пропущено: $message")
            else -> respond(requestId, message)
        }
    }

    private suspend fun greet(hello: ClientMessage.Hello) {
        val compatibility = ProtocolCompatibility.check(hello.clientVersion, hostVersion)
        if (compatibility is ProtocolCompatibility.Incompatible) {
            send(ProtocolCodec.encode(ProtocolCompatibility.toHostMessage(compatibility, hostVersion)))
        } else {
            greeted = true
            send(ProtocolCodec.encode(HostMessage.Hello(hostVersion = hostVersion, sessionId = sessionId)))
        }
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
            val encoded = ProtocolCodec.encode(handler.handle(message))
            dedup.put(requestId, encoded)
            handledRequests += 1
            send(encoded)
        }
    }
}

/** Идентификатор запроса сообщения клиента; null для приветствия. */
internal fun ClientMessage.requestIdOrNull(): RequestId? = when (this) {
    is ClientMessage.OpenWorkspace -> requestId
    is ClientMessage.FileTree -> requestId
    is ClientMessage.FileContent -> requestId
    is ClientMessage.HostState -> requestId
    is ClientMessage.Hello -> null
}
