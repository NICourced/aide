package dev.aide.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.ui.App
import dev.aide.client.ui.strings.Strings
import dev.aide.platform.desktop.DesktopRuntime

fun main() = application {
    // Настройки, хост, соединение и область корутин приходят из платформенного
    // связывания: точка входа о том, что хост локальный, больше не знает.
    val runtime = remember { DesktopRuntime.start() }

    DisposableEffect(Unit) {
        onDispose { runtime.close() }
    }

    Window(onCloseRequest = ::exitApplication, title = Strings.text(Strings.appName)) {
        App(connection = runtime.connection, settings = runtime.settings, scope = runtime.scope)
    }
}
