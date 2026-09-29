package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aide.client.state.HostConnection
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.theme.AideTheme

// Соединение принимается уже сейчас, хотя экраны появятся в задаче 16: подпись
// точки входа иначе пришлось бы менять дважды. Параметр осознанно не используется —
// детектор про это и предупреждает.
@Suppress("UnusedParameter")
@Composable
fun App(connection: HostConnection) {
    // Значение по умолчанию — системная тема (FR-EDITOR-11); выбор пользователя
    // подставится из SettingsStore, когда появится экран настроек (задача 16).
    AideTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text(Strings.text(Strings.appName))
            }
        }
    }
}
