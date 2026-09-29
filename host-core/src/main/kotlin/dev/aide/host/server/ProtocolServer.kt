package dev.aide.host.server

import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import io.ktor.server.application.install
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.netty.NettyApplicationEngine
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.CloseReason
import io.ktor.websocket.Frame
import io.ktor.websocket.close
import io.ktor.websocket.readBytes
import org.slf4j.LoggerFactory

/**
 * WebSocket-сервер хоста. Один маршрут `/ws`, одна [ClientSession] на соединение.
 *
 * Сервер не знает, локальный он или удалённый: это свойство того, кто его запустил
 * (задача 13). Благодаря этому клиент не различает режимы.
 */
class ProtocolServer(
    private val handler: ClientMessageHandler,
    private val hostVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    private val mode: HostMode = HostMode.LOCAL,
    private val port: Int = freeLoopbackPort(),
    private val host: String = "127.0.0.1",
) {

    private val logger = LoggerFactory.getLogger(ProtocolServer::class.java)

    private var engine: EmbeddedServer<NettyApplicationEngine, NettyApplicationEngine.Configuration>? = null

    /** Порт, на котором фактически слушает сервер. Действителен после [start]. */
    val boundPort: Int get() = port

    /** Адрес для клиента. */
    val endpoint: String get() = "ws://$host:$port/ws"

    /** Режим, объявленный в состоянии хоста; влияет только на диагностическую надпись. */
    val hostMode: HostMode get() = mode

    /** Поднимает сервер и возвращается, не дожидаясь остановки. */
    fun start() {
        val server = embeddedServer(Netty, port = port, host = host) {
            install(WebSockets) {
                pingPeriodMillis = PING_PERIOD_MILLIS
                timeoutMillis = SESSION_TIMEOUT_MILLIS
            }
            routing {
                webSocket("/ws") {
                    val session = ClientSession(
                        handler = handler,
                        hostVersion = hostVersion,
                        send = { bytes -> send(Frame.Binary(true, bytes)) },
                    )
                    logger.info("Клиент подключился, сессия ${session.sessionId.value}, режим $mode")
                    var closeReason = CloseReason(CloseReason.Codes.NORMAL, "Сессия завершена")
                    try {
                        for (frame in incoming) {
                            // Небинарные кадры игнорируем; если сессия просит закрыть соединение
                            // (несовместимые версии), прекращаем чтение — ни один последующий
                            // кадр не должен быть обработан.
                            val closeRequested = frame is Frame.Binary && !session.onBytes(frame.readBytes())
                            if (closeRequested) {
                                closeReason = CloseReason(
                                    CloseReason.Codes.PROTOCOL_ERROR,
                                    "Несовместимая версия протокола",
                                )
                                break
                            }
                        }
                    } finally {
                        close(closeReason)
                        logger.info(
                            "Сессия ${session.sessionId.value} закрыта ($closeReason), " +
                                "обработано запросов: ${session.handledRequests}",
                        )
                    }
                }
            }
        }
        engine = server
        server.start(wait = false)
        logger.info("Хост слушает $endpoint, режим $mode")
    }

    /** Останавливает сервер и освобождает порт. */
    fun stop() {
        engine?.stop(gracePeriodMillis = SHUTDOWN_GRACE_MILLIS, timeoutMillis = SHUTDOWN_TIMEOUT_MILLIS)
        engine = null
    }

    companion object {
        /** Период ping-кадров: держит соединение живым через прокси. */
        private const val PING_PERIOD_MILLIS: Long = 15_000

        /** Таймаут молчания: после него сессия считается оборванной. */
        private const val SESSION_TIMEOUT_MILLIS: Long = 30_000

        /** Сколько ждать завершения обработки при остановке. */
        private const val SHUTDOWN_GRACE_MILLIS: Long = 100

        /** Верхняя граница остановки сервера. */
        private const val SHUTDOWN_TIMEOUT_MILLIS: Long = 1_000
    }
}
