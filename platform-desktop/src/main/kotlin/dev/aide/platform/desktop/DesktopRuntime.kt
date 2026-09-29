package dev.aide.platform.desktop

import dev.aide.client.state.HostConnection
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStore
import dev.aide.host.EmbeddedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Платформенное связывание десктоп-клиента (T-0.13).
 *
 * Единственное место, где известно, что хост на десктопе живёт в том же процессе:
 * приложение получает готовые настройки, соединение и область корутин и не отличает
 * локальный хост от удалённого (§ 3.3). Закрытие останавливает хост и отменяет
 * корутины, поэтому порт освобождается вместе с окном.
 *
 * Граф собирается на Koin (DI-фреймворк стека) и изолированно: `KoinApplication.init()`,
 * а не глобальный контекст — `DesktopRuntime.start()` вызывается и из тестов, и повторный
 * вызов не должен падать.
 */
class DesktopRuntime internal constructor(
    /** Настройки клиента, переживающие перезапуск. */
    val settings: SettingsStore,
    /** Соединение с локально поднятым хостом. */
    val connection: HostConnection,
    /** Область корутин приложения: живёт до закрытия окна. */
    val scope: CoroutineScope,
    private val host: EmbeddedHost,
    private val graph: KoinApplication,
) : AutoCloseable {

    override fun close() {
        host.close()
        scope.cancel()
        graph.close()
    }

    companion object {
        /** Модуль Koin десктоп-клиента: единственное место, где перечислены его части. */
        internal fun module(): Module = module {
            single { EmbeddedHost.open() }
            single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            single { SettingsStore(createKeyValueStore()) }
            single<HostConnection> { KtorHostConnection(endpoint = get<EmbeddedHost>().endpoint, scope = get()) }
        }

        /** Поднимает хост на свободном порту loopback, читает настройки и создаёт соединение. */
        fun start(): DesktopRuntime {
            val graph = KoinApplication.init().modules(module())
            val host = graph.koin.get<EmbeddedHost>()
            val scope = graph.koin.get<CoroutineScope>()
            val settings = graph.koin.get<SettingsStore>()
            val connection = graph.koin.get<HostConnection>()
            return DesktopRuntime(
                settings = settings,
                connection = connection,
                scope = scope,
                host = host,
                graph = graph,
            )
        }
    }
}
