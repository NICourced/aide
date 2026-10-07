package dev.aide.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ToolCallLog
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.callLogOutcomeResource
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.ToolCallSummary

/** Доля высоты, отданная списку журнала: кнопка догрузки всегда видна под ним. */
private const val LOG_LIST_WEIGHT = 1f

/**
 * Экран журнала вызовов (T-1.3, FR-AGENT-8, FR-TOOLS-15).
 *
 * Список — [LazyColumn] с самыми новыми сверху и догрузкой старого по кнопке: при 500
 * вызовах на экране всегда не больше подгруженной страницы, поэтому интерфейс не строится
 * целиком и не блокируется. Состояния — общие (§ 6.1): пусто, загрузка, ошибка, нет связи.
 *
 * Строка раскрывается тапом: страница несёт только превью, а полное содержимое приходит
 * отдельным запросом ([onToggleRow]); раскрыта не более одной записи — иначе список из
 * больших текстов перестал бы читаться.
 *
 * @param onLoadPage загрузить страницу по текущему курсору: и «показать более старые»,
 *   и повтор после ошибки — одно и то же действие, потому что курсор после неудачи не
 *   сдвигается, а после успеха указывает на следующую страницу.
 */
@Composable
fun CallLogScreen(
    log: ToolCallLog,
    onLoadPage: () -> Unit,
    onBack: () -> Unit,
    onToggleRow: (ToolCallId) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = onBack, modifier = Modifier.testTag("call-log-back")) {
                Text(Strings.text(Strings.actionBack))
            }
            Text(Strings.text(Strings.callLogTitle), style = MaterialTheme.typography.titleLarge)
        }
        // Пометка «нет связи» — над любым состоянием, а не только над непустым списком:
        // потеря связи объясняет, почему записи не обновляются, и при пустом журнале тоже.
        if (log.offline) OfflineBanner()
        when {
            log.runId == null -> EmptyState(Strings.text(Strings.callLogNoRun))
            log.failed && log.calls.isEmpty() -> ErrorState(Strings.text(Strings.callLogError), onLoadPage)
            log.calls.isNotEmpty() -> LogContent(log, onLoadPage, onToggleRow)
            // `loaded` отличает «страница пришла и журнал пуст» от «ещё не загружали»:
            // первое — пустое состояние, второе — загрузка, а не пустой экран на первый кадр.
            log.loaded -> EmptyState(Strings.text(Strings.callLogEmpty))
            else -> LoadingState()
        }
    }
}

/** Загруженные записи, ошибка догрузки и кнопка «показать более старые». */
@Composable
private fun LogContent(log: ToolCallLog, onLoadPage: () -> Unit, onToggleRow: (ToolCallId) -> Unit) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Ошибка догрузки не должна прятать уже показанные записи: экран показывает их
        // вместе с предложением повторить, а не пустоту.
        if (log.failed) ErrorState(Strings.text(Strings.callLogError), onLoadPage)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(LOG_LIST_WEIGHT).testTag("call-log-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(log.calls, key = { it.id.value }) { call ->
                CallLogRow(call = call, log = log, onToggle = { onToggleRow(call.id) })
            }
        }
        if (log.hasMore) {
            Button(
                onClick = onLoadPage,
                enabled = !log.loading,
                modifier = Modifier.testTag("call-log-load-older"),
            ) {
                Text(Strings.text(Strings.callLogLoadOlder))
            }
        }
    }
}

/**
 * Строка списка: поля записи и превью или полное содержимое (FR-AGENT-9).
 *
 * Признак «требовал подтверждения» показывается отдельной строкой: это поле записи,
 * а не деталь раскрытия, и по нему видно, спрашивали ли человека. В свёрнутом виде
 * превью помечается как обрезанное — обрезанное не должно выглядеть полным.
 *
 * Состояние берётся из [log] целиком, а не пятью отдельными флагами: раскрытие, деталь
 * и её загрузка описывают одну запись, и разносить их по параметрам значило бы позволить
 * им разойтись.
 */
@Composable
private fun CallLogRow(call: ToolCallSummary, log: ToolCallLog, onToggle: () -> Unit) {
    val expanded = log.expanded == call.id
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onToggle)
            .testTag("call-log-row-${call.id.value}"),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        val outcome = Strings.text(callLogOutcomeResource(call.outcome))
        val duration = Strings.text(Strings.callLogDuration, call.durationMillis)
        val cost = if (call.cost.known) {
            Strings.text(Strings.callLogCost, call.cost.amountMicros)
        } else {
            Strings.text(Strings.callLogCostUnknown)
        }
        Text(Strings.text(Strings.callLogSummary, call.tool, outcome, duration, cost))
        if (call.requiredApproval) {
            Text(
                text = Strings.text(Strings.callLogApproval),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.tertiary,
                modifier = Modifier.testTag("call-log-approval-${call.id.value}"),
            )
        }
        if (expanded) {
            DetailContent(
                detail = log.detail?.takeIf { log.expanded == call.id },
                loading = log.detailLoading,
                failed = log.detailFailed,
            )
        } else {
            Summaries(call)
        }
    }
}

/** Свёрнутый вид: превью аргументов и результата с пометками обрезки. */
@Composable
private fun Summaries(call: ToolCallSummary) {
    Text(Strings.text(Strings.callLogArguments), style = MaterialTheme.typography.labelSmall)
    PreviewText(call.argumentsPreview, call.argumentsTruncated)
    Text(Strings.text(Strings.callLogResult), style = MaterialTheme.typography.labelSmall)
    val result = call.resultPreview
    if (result == null) {
        Text(Strings.text(Strings.callLogNoResult), style = MaterialTheme.typography.bodySmall)
    } else {
        PreviewText(result, call.resultTruncated)
    }
}

/** Раскрытый вид: загрузка, ошибка или полное содержимое — с собственным состоянием. */
@Composable
private fun DetailContent(detail: ToolCall?, loading: Boolean, failed: Boolean) {
    when {
        loading -> Text(
            text = Strings.text(Strings.stateLoadingTitle),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.testTag("call-log-detail-loading"),
        )

        failed -> Text(
            text = Strings.text(Strings.callLogDetailError),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
            modifier = Modifier.testTag("call-log-detail-error"),
        )

        detail != null -> {
            Text(Strings.text(Strings.callLogArguments), style = MaterialTheme.typography.labelSmall)
            Text(detail.arguments, style = MaterialTheme.typography.bodySmall)
            Text(Strings.text(Strings.callLogResult), style = MaterialTheme.typography.labelSmall)
            Text(
                text = detail.result ?: Strings.text(Strings.callLogNoResult),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** Превью и пометка обрезки: обрезанное не должно выглядеть полным (T-1.3). */
@Composable
private fun PreviewText(preview: String, truncated: Boolean) {
    Text(preview, style = MaterialTheme.typography.bodySmall)
    if (truncated) {
        Text(
            text = Strings.text(Strings.callLogTruncated),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.error,
        )
    }
}
