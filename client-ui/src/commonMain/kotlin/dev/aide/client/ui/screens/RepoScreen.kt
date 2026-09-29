package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload

/**
 * Экран репозитория: дерево файлов или содержимое выбранного файла.
 *
 * Файл показывается вместо дерева, а не поверх него: на узком экране так остаётся
 * место для текста, а возврат к дереву — одна кнопка (FR-LAYOUT-5).
 */
@Composable
fun RepoScreen(
    treeState: ScreenState<FileTreePayload>,
    fileState: ScreenState<FileContentPayload>,
    onFileClick: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    if (fileState is ScreenState.Empty) {
        RepoTreeScreen(state = treeState, onFileClick = onFileClick, onRetry = onRetry)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = onBack,
                modifier = Modifier.padding(16.dp).testTag("back-to-tree"),
            ) {
                Text(Strings.text(Strings.actionBack))
            }
            FileContentScreen(state = fileState, onRetry = onRetry)
        }
    }
}
