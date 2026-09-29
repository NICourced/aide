package dev.aide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStore
import dev.aide.client.state.settings.initKeyValueStore
import dev.aide.client.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Точка входа Android-приложения.
 *
 * Хост здесь не поднимается: на телефоне приложение подключается к хосту по адресу
 * из настроек. Автоматическое обнаружение хоста и сопряжение устройств появятся
 * в T-1.51 и на этапе 5 (T-5.11) — до тех пор адрес вводится вручную.
 *
 * Настройки уже есть (T-0.14), но экран, который их меняет, появится в задаче 16.
 * Пока адрес берётся из хранилища, а если его там нет — из значения по умолчанию:
 * пустое хранилище не должно ронять запуск.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Контекст задаётся до первой фабрики хранилища, иначе она падает: молчаливая
        // потеря настроек хуже явной ошибки при старте.
        initKeyValueStore(applicationContext)
        val endpoint = SettingsStore(createKeyValueStore()).hostEndpoint ?: DEFAULT_ENDPOINT
        val connection = KtorHostConnection(endpoint = endpoint, scope = scope)

        setContent {
            App(connection = connection)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Адрес хоста по умолчанию для эмулятора: 10.0.2.2 указывает на машину-хост. */
        private const val DEFAULT_ENDPOINT: String = "ws://10.0.2.2:8080/ws"
    }
}
