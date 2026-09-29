package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.settings.ControlMode
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.ThemePreference
import dev.aide.client.ui.strings.Strings

/**
 * Настройки: путь к репозиторию, адрес хоста, тема, режим управления.
 *
 * Режим управления здесь только сохраняется: различие в поведении кнопок и жестов
 * появляется в этапе 1 (T-1.43). Хранить его уже сейчас нужно, иначе перезапуск
 * приложения будет терять выбор пользователя.
 *
 * Адрес хоста — тоже только настройка: соединение создаётся один раз при старте
 * приложения, поэтому новый адрес действует после перезапуска (T-1.51). Экран
 * говорит об этом прямо, иначе введённый адрес выглядит неработающим.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenRepository: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf(settings.repositoryPath.orEmpty()) }
    var hostEndpoint by remember { mutableStateOf(settings.hostEndpoint.orEmpty()) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings.text(Strings.settingsTitle), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(Strings.text(Strings.settingsRepositoryPath)) },
            placeholder = { Text(Strings.text(Strings.settingsRepositoryPathHint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings-repository-path"),
        )
        Button(
            onClick = {
                settings.repositoryPath = path
                onOpenRepository(path)
            },
            modifier = Modifier.testTag("settings-open"),
        ) {
            Text(Strings.text(Strings.settingsRepositoryApply))
        }

        OutlinedTextField(
            value = hostEndpoint,
            onValueChange = { hostEndpoint = it },
            label = { Text(Strings.text(Strings.settingsHostEndpoint)) },
            placeholder = { Text(Strings.text(Strings.settingsHostEndpointHint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings-host-endpoint"),
        )
        Button(
            onClick = { settings.hostEndpoint = hostEndpoint.takeIf { it.isNotBlank() } },
            modifier = Modifier.testTag("settings-host-endpoint-apply"),
        ) {
            Text(Strings.text(Strings.settingsHostEndpointApply))
        }
        Text(
            Strings.text(Strings.settingsHostEndpointRestart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(Strings.text(Strings.settingsTheme), style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            options = ThemePreference.entries.map { it to themeLabel(it) },
            selected = settings.theme,
            onSelect = { settings.theme = it },
        )

        Text(Strings.text(Strings.settingsControlMode), style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            options = ControlMode.entries.map { it to controlLabel(it) },
            selected = settings.controlMode,
            onSelect = { settings.controlMode = it },
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            Button(
                onClick = { onSelect(value) },
                enabled = value != selected,
                modifier = Modifier.fillMaxWidth().testTag("choice-$label"),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun themeLabel(preference: ThemePreference): String = when (preference) {
    ThemePreference.SYSTEM -> Strings.text(Strings.settingsThemeSystem)
    ThemePreference.LIGHT -> Strings.text(Strings.settingsThemeLight)
    ThemePreference.DARK -> Strings.text(Strings.settingsThemeDark)
}

@Composable
private fun controlLabel(mode: ControlMode): String = when (mode) {
    ControlMode.GESTURES -> Strings.text(Strings.settingsControlModeGestures)
    ControlMode.BUTTONS -> Strings.text(Strings.settingsControlModeButtons)
    ControlMode.HYBRID -> Strings.text(Strings.settingsControlModeHybrid)
}
