package dev.aide.client.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileContentPayload

/**
 * Просмотр содержимого файла.
 *
 * Горизонтальная прокрутка здесь допустима и не противоречит FR-DIFF-9: запрет
 * горизонтального скролла относится к diff-вьюеру, а не к просмотру файла целиком.
 */
@Composable
fun FileContentScreen(
    state: ScreenState<FileContentPayload>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Empty -> EmptyState(Strings.text(Strings.stateEmptyTree))

            ScreenState.Loading -> LoadingState(rows = 6)

            is ScreenState.Failed -> ErrorState(message = state.detail, onRetry = onRetry)

            is ScreenState.NoPermission -> NoPermissionState(path = state.path, reason = state.reason)

            is ScreenState.Offline -> {
                OfflineBanner()
                FileBody(state.cached)
            }

            is ScreenState.Loaded -> FileBody(state.data)
        }
    }
}

@Composable
private fun FileBody(content: FileContentPayload) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = Strings.text(Strings.repoFileTitle, content.path),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (content.truncated) {
            Text(
                text = Strings.text(Strings.repoFileTruncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            text = content.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("file-content"),
        )
    }
}
