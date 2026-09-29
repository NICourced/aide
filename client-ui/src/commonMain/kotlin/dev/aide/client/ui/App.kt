package dev.aide.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import dev.aide.client.state.AppStateStore
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostCallException
import dev.aide.client.state.HostClient
import dev.aide.client.state.HostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.ui.screens.RepoScreen
import dev.aide.client.ui.screens.SettingsScreen
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.theme.AideTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Куда приложение может перейти. Один экран — одна задача (FR-LAYOUT-5). */
private enum class Destination { REPOSITORY, SETTINGS }

@Composable
fun App(
    connection: HostConnection,
    settings: SettingsStore,
    scope: CoroutineScope,
    state: AppStateStore = remember { AppStateStore() },
) {
    val client = remember(connection, scope) { HostClient(connection, scope) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { client.start() }

    val connectionState by connection.state.collectAsState()
    LaunchedEffect(connectionState) { state.onConnectionState(connectionState) }

    val tree by state.treeState.collectAsState()
    val file by state.fileState.collectAsState()
    val hostState by state.hostState.collectAsState()

    var destination by remember { mutableStateOf(Destination.REPOSITORY) }

    AideTheme(preference = settings.theme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(hostState?.branch, connectionState)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(onClick = { destination = Destination.REPOSITORY }) {
                        Text(Strings.text(Strings.repoTreeTitle))
                    }
                    Button(onClick = { destination = Destination.SETTINGS }) {
                        Text(Strings.text(Strings.actionSettings))
                    }
                }

                when (destination) {
                    Destination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onOpenRepository = { path ->
                            // Открытие пути уводит на экран репозитория: иначе нажатие
                            // «Открыть» выглядит не сделавшим ничего — дерево показывается
                            // на другом экране, а пользователь остаётся в настройках.
                            if (path.isNotBlank()) destination = Destination.REPOSITORY
                            coroutineScope.launch { openRepository(client, state, path) }
                        },
                    )

                    Destination.REPOSITORY -> RepoScreen(
                        treeState = tree,
                        fileState = file,
                        onFileClick = { path ->
                            state.selectFile(path)
                            coroutineScope.launch { loadFile(client, state, path) }
                        },
                        onRetry = {
                            coroutineScope.launch { openRepository(client, state, settings.repositoryPath.orEmpty()) }
                        },
                        onBack = { state.selectFile(null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(branch: String?, connectionState: ConnectionState) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(Strings.text(Strings.appName), style = MaterialTheme.typography.titleLarge)
        if (branch != null) {
            Text(Strings.text(Strings.repoHeaderBranch, branch), style = MaterialTheme.typography.bodyMedium)
        }
        val status = when (connectionState) {
            ConnectionState.Idle, ConnectionState.Connecting -> Strings.text(Strings.connectionConnecting)
            is ConnectionState.Connected -> null
            is ConnectionState.Reconnecting ->
                Strings.text(Strings.connectionReconnecting, connectionState.attempt)

            is ConnectionState.Incompatible ->
                Strings.text(Strings.connectionIncompatible, connectionState.userMessage)

            is ConnectionState.Closed -> Strings.text(Strings.connectionClosed, connectionState.reason)
        }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Открывает репозиторий: переводит дерево в загрузку, открывает воркспейс и запрашивает дерево и состояние хоста. */
private suspend fun openRepository(client: HostClient, state: AppStateStore, path: String) {
    if (path.isBlank()) return
    state.onTreeLoading()

    if (client.openWorkspace(path) == null) {
        // Хост отверг путь: причина лежит в последней ошибке сессии, а не в ответе на отдельный запрос.
        client.session.value.lastError?.let(state::onTreeFailed)
        return
    }

    client.fileTree()
        .onSuccess { state.onTreeLoaded(it) }
        .onFailure { error ->
            val protocolError = (error as? HostCallException)?.error
            if (protocolError != null) state.onTreeFailed(protocolError)
        }
    client.hostState().onSuccess { state.onHostState(it) }
}

/** Читает файл: на успехе кладёт содержимое в состояние, на ошибке — типизированную причину. */
private suspend fun loadFile(client: HostClient, state: AppStateStore, path: String) {
    client.fileContent(path)
        .onSuccess { state.onFileLoaded(it) }
        .onFailure { error ->
            val protocolError = (error as? HostCallException)?.error
            if (protocolError != null) state.onFileFailed(protocolError)
        }
}
