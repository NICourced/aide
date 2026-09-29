package dev.aide.host

import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.protocol.HostMode
import org.koin.core.KoinApplication

/**
 * Хост, поднятый в том же процессе, что и приложение (§ 3.3).
 *
 * Смысл этого класса в том, что локальный режим не имеет собственного кода:
 * он поднимает тот же [ProtocolServer], что и удалённый, и отличается только
 * адресом. Поэтому клиент не может «заметить», что хост локальный, — ему это
 * и не нужно.
 *
 * Остановка приложения должна вызывать [close]: иначе порт остаётся занятым,
 * а открытые git-репозитории — не закрытыми.
 */
class EmbeddedHost internal constructor(
    private val server: ProtocolServer,
    private val handler: StageZeroHandler,
    private val graph: KoinApplication,
) : AutoCloseable {

    /** Порт, на котором слушает хост. */
    val port: Int get() = server.boundPort

    /** Адрес для клиента. */
    val endpoint: String get() = server.endpoint

    /** Режим, который хост объявляет клиенту; на клиентское поведение не влияет. */
    val mode: HostMode get() = server.hostMode

    override fun close() {
        server.stop()
        handler.close()
        graph.close()
    }

    companion object {
        /**
         * Поднимает хост на свободном порту loopback.
         *
         * Путь к репозиторию в подписи отсутствует намеренно: воркспейс открывает клиент
         * сообщением `OpenWorkspace`, и хост не решает за него, что открывать, — ровно так
         * же ведёт себя удалённый хост. Сборка зависимостей остаётся в [HostApp].
         *
         * @param port конкретный порт; по умолчанию берётся свободный, чтобы два запуска
         *   приложения на одной машине не конфликтовали.
         */
        fun open(port: Int = freeLoopbackPort()): EmbeddedHost = HostApp.open(port = port)
    }
}
