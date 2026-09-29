package dev.aide.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.ui.App
import dev.aide.host.EmbeddedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

fun main() = application {
    // Хост поднимается вместе с приложением и завершается вместе с ним (T-0.13).
    val host = remember { EmbeddedHost.open() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val connection = remember { KtorHostConnection(endpoint = host.endpoint, scope = scope) }

    DisposableEffect(Unit) {
        connection.start()
        onDispose {
            // Соединение закрывает свой HttpClient; если только отменить scope, сокет
            // останется открытым до конца процесса.
            runBlocking { connection.stop() }
            host.close()
            scope.cancel()
        }
    }

    Window(onCloseRequest = ::exitApplication, title = "AI Studio") {
        App(connection = connection)
    }
}
