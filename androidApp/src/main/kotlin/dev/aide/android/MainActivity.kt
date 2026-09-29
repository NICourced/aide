package dev.aide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aide.client.ui.App
import dev.aide.platform.android.AndroidClientRuntime
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
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dependencies = AndroidClientRuntime.start(context = this, scope = scope)

        setContent {
            App(
                connection = dependencies.connection,
                settings = dependencies.settings,
                scope = scope,
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
