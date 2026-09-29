package dev.aide.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AI Studio") {
        App()
    }
}
