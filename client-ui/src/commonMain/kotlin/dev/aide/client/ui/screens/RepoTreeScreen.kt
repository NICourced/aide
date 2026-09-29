package dev.aide.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileTreePayload

/**
 * Дерево файлов. Плоский список путей с отступом по глубине: на 20 000 файлах
 * вложенные composable-узлы съели бы всю память, а визуально результат тот же.
 */
@Composable
fun RepoTreeScreen(
    state: ScreenState<FileTreePayload>,
    onFileClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Empty -> EmptyState(Strings.text(Strings.stateEmptyRepo))

            ScreenState.Loading -> LoadingState()

            is ScreenState.Failed -> ErrorState(message = state.detail, onRetry = onRetry)

            is ScreenState.NoPermission -> NoPermissionState(path = state.path, reason = state.reason)

            is ScreenState.Offline -> {
                OfflineBanner()
                TreeList(state.cached, onFileClick)
            }

            is ScreenState.Loaded -> TreeList(state.data, onFileClick)
        }
    }
}

@Composable
private fun TreeList(tree: FileTreePayload, onFileClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = Strings.text(Strings.repoHeaderRoot, tree.rootPath),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (tree.truncated) {
            Text(
                text = Strings.text(Strings.repoTreeTruncated, tree.skippedEntries),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("tree-list"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tree.entries, key = { it.path }) { entry ->
                TreeRow(entry.path, entry.isDirectory) { onFileClick(entry.path) }
            }
        }
    }
}

@Composable
private fun TreeRow(path: String, isDirectory: Boolean, onClick: () -> Unit) {
    val depth = path.count { it == '/' }
    val label = path.substringAfterLast('/')
    Text(
        text = if (isDirectory) "$label/" else label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDirectory, onClick = onClick)
            .padding(start = (16 + depth * 12).dp, top = 8.dp, bottom = 8.dp, end = 16.dp)
            .testTag("tree-row-$path"),
    )
}
