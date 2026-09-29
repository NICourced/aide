package dev.aide.host

import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.bind
import org.koin.dsl.module

/**
 * Composition root хоста: единственное место, где известно, из чего состоит хост.
 *
 * Модуль параметризован режимом, портом и версией протокола: на этапе 0 эти значения
 * отличают локальный хост от удалённого, разделяемым состоянием они не являются.
 *
 * Граф изолированный ([KoinApplication.init], а не глобальный контекст): хост поднимается
 * и в приложении, и в тестах — `EmbeddedHostTest` открывает его несколько раз за прогон,
 * а глобальный контекст Koin допускает только один запуск на процесс.
 */
object HostApp {

    /** Квалификатор режима: значение нужно и обработчику, и серверу. */
    val modeQualifier: Qualifier = named("hostMode")

    /** Квалификатор порта: сервер слушает именно его. */
    val portQualifier: Qualifier = named("hostPort")

    /** Квалификатор версии протокола, которую хост объявляет клиенту. */
    val versionQualifier: Qualifier = named("hostVersion")

    /**
     * Собирает части хоста: обработчик сообщений и сервер.
     *
     * `StageZeroHandler` регистрируется дополнительно как [ClientMessageHandler]: сервер
     * зависит от интерфейса, а Koin сопоставляет определение по объявленному типу, поэтому
     * без `bind` вызов `get<ClientMessageHandler>()` не нашёл бы определение.
     */
    fun module(
        mode: HostMode,
        port: Int,
        protocolVersion: ProtocolVersion,
    ): Module = module {
        single(modeQualifier) { mode }
        single(portQualifier) { port }
        single(versionQualifier) { protocolVersion }
        single { StageZeroHandler(mode = get(modeQualifier)) } bind ClientMessageHandler::class
        single {
            ProtocolServer(
                handler = get(),
                hostVersion = get(versionQualifier),
                mode = get(modeQualifier),
                port = get(portQualifier),
            )
        }
    }

    /**
     * Поднимает хост на свободном порту loopback, если порт не задан явно.
     *
     * Возвращает [EmbeddedHost] вместе с графом: `close()` освобождает порт, закрывает
     * обработчик и закрывает сам граф.
     */
    fun open(
        mode: HostMode = HostMode.LOCAL,
        port: Int = freeLoopbackPort(),
        protocolVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    ): EmbeddedHost {
        val graph = KoinApplication.init()
            .modules(module(mode = mode, port = port, protocolVersion = protocolVersion))
        val server = graph.koin.get<ProtocolServer>()
        server.start()
        return EmbeddedHost(server = server, handler = graph.koin.get(), graph = graph)
    }
}
