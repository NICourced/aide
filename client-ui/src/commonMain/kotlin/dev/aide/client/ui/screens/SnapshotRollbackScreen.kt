package dev.aide.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.state.SnapshotRollbackState
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.failureMessage
import dev.aide.client.ui.strings.stateMessageText
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import org.jetbrains.compose.resources.StringResource

/**
 * Bottom sheet отката к снапшоту (T-1.6).
 *
 * Показывает список снапшотов; выбранная строка раскрывается в предпросмотр изменений
 * между снапшотом и текущим HEAD. Подтверждение отката использует хеш HEAD из
 * предпросмотра, чтобы хост мог отклонить устаревший запрос. После успеха шторка
 * остаётся открытой: пользователь видит хеш обратного коммита и закрывает её сам.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SnapshotRollbackBottomSheet(
    state: SnapshotRollbackState,
    actions: SnapshotRollbackActions,
    modifier: Modifier = Modifier,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = actions.onDismiss,
        sheetState = sheetState,
    ) {
        Column(
            modifier = modifier.fillMaxWidth().padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                Strings.text(Strings.snapshotTitle),
                style = MaterialTheme.typography.titleLarge,
            )
            when (val list = state.list) {
                ScreenState.Empty -> EmptyState(Strings.text(Strings.snapshotEmpty))
                ScreenState.Loading -> LoadingState()
                is ScreenState.Loaded -> SnapshotList(list.data, state, actions)
                is ScreenState.Offline -> {
                    OfflineBanner()
                    SnapshotList(list.cached, state, actions)
                }

                is ScreenState.Failed -> ErrorState(
                    message = stateMessageText(failureMessage(list.kind, list.arguments)),
                    onRetry = actions.onLoadList,
                )

                ScreenState.NoRepository -> AccessDeniedState(Strings.text(Strings.stateNoRepository))
                is ScreenState.NoPermission -> AccessDeniedState(
                    Strings.text(Strings.stateNoPermissionTitle),
                    Strings.text(Strings.stateNoPermissionBody, list.path, list.reason),
                )
            }
        }
    }
}

@Composable
private fun SnapshotList(
    snapshots: List<Snapshot>,
    state: SnapshotRollbackState,
    actions: SnapshotRollbackActions,
) {
    if (snapshots.isEmpty()) {
        EmptyState(Strings.text(Strings.snapshotEmpty))
        return
    }
    LazyColumn(
        modifier = Modifier.fillMaxWidth().testTag("snapshot-list"),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(snapshots, key = { it.ref.value }) { snapshot ->
            val selected = state.selected == snapshot.ref
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable(enabled = state.reverting == null) { actions.onSelect(snapshot.ref) }
                    .testTag("snapshot-row-${snapshot.ref.value}"),
            ) {
                Text(
                    snapshot.label,
                    style = MaterialTheme.typography.bodyLarge,
                )
                Text(
                    snapshot.createdAt.toString(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (selected) {
                    PreviewPanel(snapshot.ref, state, actions)
                }
            }
        }
    }
}

@Composable
private fun PreviewPanel(
    ref: SnapshotRef,
    state: SnapshotRollbackState,
    actions: SnapshotRollbackActions,
) {
    val preview = state.preview
    Column(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        when (preview) {
            ScreenState.Loading -> CircularProgressIndicator(
                modifier = Modifier.testTag("snapshot-preview-loading"),
            )

            is ScreenState.Loaded -> LoadedPreview(
                preview = preview.data,
                reverting = state.reverting == ref,
                completedCommit = state.completedCommit,
                onRevert = { actions.onRevert(ref, preview.data.headCommit) },
                onClose = actions.onDismiss,
            )

            is ScreenState.Failed -> ErrorState(
                message = stateMessageText(failureMessage(preview.kind, preview.arguments)),
                onRetry = { actions.onSelect(ref) },
                modifier = Modifier.testTag("snapshot-preview-error"),
            )

            null -> Unit
            else -> Unit
        }
        val revertError = state.revertError
        if (revertError is ScreenState.Failed) {
            Text(
                stateMessageText(failureMessage(revertError.kind, revertError.arguments)),
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@Composable
private fun LoadedPreview(
    preview: SnapshotPreview,
    reverting: Boolean,
    completedCommit: String?,
    onRevert: () -> Unit,
    onClose: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            Strings.text(Strings.snapshotPreviewFiles),
            style = MaterialTheme.typography.titleMedium,
        )
        preview.files.forEach { change ->
            FileChangeRow(change)
        }
        val commit = completedCommit
        if (commit != null) {
            Text(
                Strings.text(Strings.snapshotRevertDone, commit),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("snapshot-revert-done"),
            )
            Button(
                onClick = onClose,
                modifier = Modifier.testTag("snapshot-close"),
            ) {
                Text(Strings.text(Strings.snapshotClose))
            }
        } else {
            Text(
                Strings.text(Strings.snapshotRevertConfirm),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag("snapshot-revert-confirm"),
            )
            Button(
                onClick = onRevert,
                enabled = !reverting,
                modifier = Modifier.testTag("snapshot-revert-${preview.snapshot.ref.value}"),
            ) {
                if (reverting) {
                    CircularProgressIndicator(
                        modifier = Modifier.padding(end = 8.dp).testTag("snapshot-reverting"),
                        strokeWidth = 2.dp,
                    )
                }
                Text(
                    if (reverting) {
                        Strings.text(Strings.snapshotReverting)
                    } else {
                        Strings.text(Strings.snapshotRevert)
                    },
                )
            }
        }
    }
}

@Composable
private fun FileChangeRow(change: SnapshotFileChange) {
    Column(modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
        Text(
            change.path,
            style = MaterialTheme.typography.bodyMedium,
        )
        val previousPath = change.previousPath
        if (previousPath != null) {
            Text(
                Strings.text(Strings.snapshotChangeRenamedFrom, previousPath),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                Strings.text(changeKindResource(change.changeKind)),
                style = MaterialTheme.typography.bodySmall,
            )
            if (change.addedLines > 0) {
                Text(
                    Strings.text(Strings.snapshotPreviewAdded, change.addedLines),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
            if (change.removedLines > 0) {
                Text(
                    Strings.text(Strings.snapshotPreviewRemoved, change.removedLines),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

/** Состояние без доступа: честный текст вместо пустого списка. */
@Composable
private fun AccessDeniedState(title: String, body: String = "") {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(
            title,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("snapshot-access-denied-title"),
        )
        if (body.isNotBlank()) {
            Text(
                body,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("snapshot-access-denied-body"),
            )
        }
    }
}

private fun changeKindResource(kind: FileChangeKind): StringResource = when (kind) {
    FileChangeKind.ADDED -> Strings.snapshotChangeAdded
    FileChangeKind.DELETED -> Strings.snapshotChangeDeleted
    FileChangeKind.MODIFIED -> Strings.snapshotChangeModified
    FileChangeKind.RENAMED -> Strings.snapshotChangeRenamed
}
