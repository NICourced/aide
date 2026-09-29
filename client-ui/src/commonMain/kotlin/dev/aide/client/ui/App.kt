package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aide.client.state.HostConnection

// Соединение принимается уже сейчас, хотя экраны появятся в задачах 14 и 16: подпись
// точки входа иначе пришлось бы менять дважды. Параметр осознанно не используется —
// детектор про это и предупреждает.
@Suppress("UnusedParameter")
@Composable
fun App(connection: HostConnection) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("AI Studio")
            }
        }
    }
}
