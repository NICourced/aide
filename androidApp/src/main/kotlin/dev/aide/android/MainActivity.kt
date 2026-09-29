package dev.aide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aide.client.state.KtorHostConnection
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
 * Настроек ещё нет, поэтому адрес берётся из константы; связывание переедет
 * в `AndroidClientRuntime`, когда появится хранилище настроек.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connection = KtorHostConnection(endpoint = DEFAULT_ENDPOINT, scope = scope)

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
