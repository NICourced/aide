package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import dev.aide.client.ui.strings.Strings
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolKind

/**
 * Диалог подтверждения вызова инструмента (T-1.13).
 *
 * Показывает имя инструмента, аргументы и «что произойдёт» — текст строится из ресурсов
 * по оси и признаку изменения репозитория (NFR-13). Три кнопки: один раз, на сессию,
 * запретить. Закрыть без решения нельзя: это либо разрешение, либо отказ.
 */
@Composable
fun ToolApprovalDialog(
    request: ToolApprovalRequest,
    onDecide: (ApprovalDecision) -> Unit,
) {
    Dialog(onDismissRequest = { }) {
        Surface(shape = MaterialTheme.shapes.extraLarge) {
            Column(modifier = Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Text(
                    Strings.text(Strings.toolApprovalTitle),
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    Strings.text(Strings.toolApprovalTool, request.tool),
                    style = MaterialTheme.typography.bodyLarge,
                    modifier = Modifier.testTag("approval-tool"),
                )
                Text(
                    Strings.text(Strings.toolApprovalArguments, request.arguments),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier
                        .heightIn(max = 160.dp)
                        .verticalScroll(rememberScrollState())
                        .testTag("approval-arguments"),
                )
                Text(
                    Strings.text(whatResource(request)),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag("approval-what"),
                )
                if (request.changesRepository) {
                    Text(
                        Strings.text(Strings.toolApprovalRollback),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.testTag("approval-rollback"),
                    )
                }
                Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = { onDecide(ApprovalDecision.ALLOW_ONCE) },
                        modifier = Modifier.weight(1f).testTag("approval-allow-once"),
                    ) {
                        Text(Strings.text(Strings.toolApprovalAllowOnce))
                    }
                    Button(
                        onClick = { onDecide(ApprovalDecision.ALLOW_SESSION) },
                        modifier = Modifier.weight(1f).testTag("approval-allow-session"),
                    ) {
                        Text(Strings.text(Strings.toolApprovalAllowSession))
                    }
                }
                Button(
                    onClick = { onDecide(ApprovalDecision.DENY) },
                    modifier = Modifier.fillMaxWidth().testTag("approval-deny"),
                ) {
                    Text(Strings.text(Strings.toolApprovalDeny))
                }
            }
        }
    }
}

/** Ресурс «что произойдёт» по оси и признаку изменения репозитория. */
private fun whatResource(request: ToolApprovalRequest) = when {
    request.kind == ToolKind.READ -> Strings.toolApprovalWhatRead
    request.changesRepository -> Strings.toolApprovalWhatWrite
    else -> Strings.toolApprovalWhatExecute
}
