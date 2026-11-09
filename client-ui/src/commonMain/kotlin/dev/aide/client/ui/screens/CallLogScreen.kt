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
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ToolCallLog
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.callLogApprovalResource
import dev.aide.client.ui.strings.callLogOutcomeResource
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.ToolCallSummary

/** Доля высоты, отданная списку журнала: кнопка догрузки всегда видна под ним. */
private const val LOG_LIST_WEIGHT = 1f

/** Маркер «все инструменты» в выпадающем списке; null передаётся в колбэк. */
private const val ALL_TOOLS_LABEL = "__all__"

/**
 * Экран журнала вызовов (T-1.3, T-1.14, FR-AGENT-8, FR-TOOLS-15).
 *
 * Список — [LazyColumn] с самыми новыми сверху и догрузкой старого по кнопке: при 500
 * вызовах на экране всегда не больше подгруженной страницы, поэтому интерфейс не строится
 * целиком и не блокируется. Состояния — общие (§ 6.1): пусто, загрузка, ошибка, нет связи.
 *
 * Сверху — панель фильтров: выбор задачи (из [tasks]) и инструмента (из [ToolCallLog.availableTools]).
 * «Все инструменты» сбрасывает фильтр в null. Смена фильтра сбрасывает записи и показывает
 * загрузку, а не старый список.
 *
 * Строка раскрывается тапом: страница несёт только превью, а полное содержимое приходит
 * отдельным запросом ([CallLogActions.onToggleRow]); раскрыта не более одной записи — иначе
 * список из больших текстов перестал бы читаться.
 */
@Composable
fun CallLogScreen(
    log: ToolCallLog,
    tasks: List<Task>,
    actions: CallLogActions,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = actions.onBack, modifier = Modifier.testTag("call-log-back")) {
                Text(Strings.text(Strings.actionBack))
            }
            Text(Strings.text(Strings.callLogTitle), style = MaterialTheme.typography.titleLarge)
        }
        FilterBar(log = log, tasks = tasks, actions = actions)
        // Пометка «нет связи» — над любым состоянием, а не только над непустым списком:
        // потеря связи объясняет, почему записи не обновляются, и при пустом журнале тоже.
        if (log.offline) OfflineBanner()
        when {
            log.scope == null -> EmptyState(Strings.text(Strings.callLogNoRun))
            log.failed && log.calls.isEmpty() -> ErrorState(Strings.text(Strings.callLogError), actions.onLoadPage)
            log.calls.isNotEmpty() -> LogContent(log, actions)
            // `loaded` отличает «страница пришла и журнал пуст» от «ещё не загружали»:
            // первое — пустое состояние, второе — загрузка, а не пустой экран на первый кадр.
            log.loaded -> EmptyState(Strings.text(Strings.callLogEmpty))
            else -> LoadingState()
        }
    }
}

/** Панель фильтров: выбор задачи и инструмента. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun FilterBar(log: ToolCallLog, tasks: List<Task>, actions: CallLogActions) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val taskScope = log.scope as? dev.aide.protocol.ToolCallScope.Task
        TaskDropdown(
            tasks = tasks,
            selected = taskScope?.taskId,
            onSelect = actions.onSelectTask,
            modifier = Modifier.weight(1f),
        )
        ToolDropdown(
            available = log.availableTools,
            selected = log.tool,
            onSelect = actions.onSelectTool,
            modifier = Modifier.weight(1f),
        )
    }
}

/** Выпадающий список задач. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun TaskDropdown(
    tasks: List<Task>,
    selected: TaskId?,
    onSelect: (TaskId) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val selectedTask = tasks.find { it.id == selected }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.testTag("call-log-task-dropdown"),
    ) {
        OutlinedTextField(
            value = selectedTask?.title ?: "",
            onValueChange = {},
            readOnly = true,
            label = { Text(Strings.text(Strings.callLogTaskLabel)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            tasks.forEach { task ->
                DropdownMenuItem(
                    text = { Text(task.title) },
                    onClick = {
                        onSelect(task.id)
                        expanded = false
                    },
                    modifier = Modifier.testTag("call-log-task-${task.id.value}"),
                )
            }
        }
    }
}

/** Выпадающий список инструментов; первый пункт — «все инструменты». */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ToolDropdown(
    available: List<String>,
    selected: String?,
    onSelect: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var expanded by remember { mutableStateOf(false) }
    val items = listOf(ALL_TOOLS_LABEL) + available
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it },
        modifier = modifier.testTag("call-log-tool-dropdown"),
    ) {
        OutlinedTextField(
            value = selected ?: Strings.text(Strings.callLogToolAll),
            onValueChange = {},
            readOnly = true,
            label = { Text(Strings.text(Strings.callLogToolLabel)) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
        ) {
            items.forEach { item ->
                val label = if (item == ALL_TOOLS_LABEL) {
                    Strings.text(Strings.callLogToolAll)
                } else {
                    item
                }
                DropdownMenuItem(
                    text = { Text(label) },
                    onClick = {
                        onSelect(item.takeUnless { it == ALL_TOOLS_LABEL })
                        expanded = false
                    },
                    modifier = Modifier.testTag("call-log-tool-${item}"),
                )
            }
        }
    }
}

/** Загруженные записи, ошибка догрузки и кнопка «показать более старые». */
@Composable
private fun LogContent(log: ToolCallLog, actions: CallLogActions) {
    Column(modifier = Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        // Ошибка догрузки не должна прятать уже показанные записи: экран показывает их
        // вместе с предложением повторить, а не пустоту.
        if (log.failed) ErrorState(Strings.text(Strings.callLogError), actions.onLoadPage)
        LazyColumn(
            modifier = Modifier.fillMaxWidth().weight(LOG_LIST_WEIGHT).testTag("call-log-list"),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(log.calls, key = { it.id.value }) { call ->
                CallLogRow(call = call, log = log, onToggle = { actions.onToggleRow(call.id) })
            }
        }
        if (log.hasMore) {
            Button(
                onClick = actions.onLoadPage,
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
            val approval = call.approval
            val approvalText = if (approval != null) {
                Strings.text(callLogApprovalResource(approval))
            } else {
                Strings.text(Strings.callLogApproval)
            }
            Text(
                text = approvalText,
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

/** Свернутый вид: превью аргументов и результата с пометками обрезки. */
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
