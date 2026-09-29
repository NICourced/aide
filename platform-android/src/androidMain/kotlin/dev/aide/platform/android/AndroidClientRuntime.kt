package dev.aide.platform.android

import android.content.Context
import dev.aide.client.state.HostConnection
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStore
import dev.aide.client.state.settings.initKeyValueStore
import kotlinx.coroutines.CoroutineScope
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Платформенное связывание Android-клиента (T-0.15).
 *
 * `createKeyValueStore()` требует, чтобы контекст приложения был задан заранее,
 * поэтому инициализация и её первый потребитель живут в одном месте. Адрес хоста
 * по умолчанию — адрес машины разработчика для эмулятора: автоматического поиска
 * хоста в этом этапе нет (T-1.51).
 *
 * Граф Koin изолированный (`KoinApplication.init()`, а не глобальный контекст):
 * `start()` вызывается из `MainActivity.onCreate`, то есть и после пересоздания
 * активити, а глобальный контекст допускает только один запуск на процесс.
 * Созданные в графе объекты продолжают жить и после того, как ссылка на граф
 * перестала быть нужна: закрывать в них нечего.
 */
object AndroidClientRuntime {

    /** Адрес хоста по умолчанию для эмулятора: 10.0.2.2 указывает на машину-хост. */
    const val DEFAULT_ENDPOINT: String = "ws://10.0.2.2:8080/ws"

    /** Модуль Koin Android-клиента: настройки и соединение собираются в одном месте. */
    internal fun module(scope: CoroutineScope): Module = module {
        single { scope }
        single { SettingsStore(createKeyValueStore()) }
        single<HostConnection> {
            KtorHostConnection(
                endpoint = get<SettingsStore>().hostEndpoint ?: DEFAULT_ENDPOINT,
                scope = get(),
            )
        }
    }

    /** Готовит настройки и соединение; вызывается из `MainActivity` до `setContent`. */
    fun start(context: Context, scope: CoroutineScope): AndroidClientDependencies {
        initKeyValueStore(context)
        val graph = KoinApplication.init().modules(module(scope))
        return AndroidClientDependencies(
            settings = graph.koin.get<SettingsStore>(),
            connection = graph.koin.get<HostConnection>(),
        )
    }
}

/** Настройки и соединение, собранные для Android-клиента. */
data class AndroidClientDependencies(
    /** Настройки клиента: путь к репозиторию, тема, режим управления, адрес хоста. */
    val settings: SettingsStore,
    /** Соединение с хостом по адресу из настроек. */
    val connection: HostConnection,
)
