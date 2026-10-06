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
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.runStateResource
import dev.aide.client.ui.strings.taskFailureResource
import dev.aide.client.ui.strings.taskStatusResource
import dev.aide.domain.AgentRun
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus

/**
 * Экран агента (T-1.1): состояние прогона и задачи, постановка задачи.
 *
 * Показываются оба статуса: задача бывает `QUEUED`, пока модель думает, и `FAILED`
 * ещё до появления прогона (например, провайдер не настроен), поэтому одной строки
 * прогона мало — отказ остался бы невидимым. [requestFailed] объединяет неудачную
 * постановку задачи и неудачный снимок состояния: оба означают «связи с хостом нет».
 * Кнопки паузы и стопа — задача T-1.4; поле с кнопкой заменит чат-ввод в T-1.40.
 */
@Composable
fun AgentScreen(
    runs: List<AgentRun>,
    tasks: List<Task>,
    requestFailed: Boolean,
    onPostTask: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var prompt by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings.text(Strings.agentTitle), style = MaterialTheme.typography.titleLarge)
        RunStateLine(run = runs.lastOrNull())
        TaskStatusLine(task = tasks.lastOrNull())
        if (requestFailed) {
            Text(
                Strings.text(Strings.agentRequestFailed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agent-request-error"),
            )
        }
        OutlinedTextField(
            value = prompt,
            onValueChange = { prompt = it },
            label = { Text(Strings.text(Strings.agentTaskHint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("task-input"),
        )
        Button(
            onClick = {
                if (prompt.isNotBlank()) {
                    onPostTask(prompt)
                    prompt = ""
                }
            },
            modifier = Modifier.testTag("post-task"),
        ) {
            Text(Strings.text(Strings.agentPostTask))
        }
    }
}

/** Строка состояния прогона: знает все значения `RunState` (NFR-13). */
@Composable
fun RunStateLine(run: AgentRun?, modifier: Modifier = Modifier) {
    val text = if (run == null) {
        Strings.text(Strings.runStateNone)
    } else {
        Strings.text(runStateResource(run.state))
    }
    Text(text, modifier = modifier.testTag("run-state"), style = MaterialTheme.typography.bodyLarge)
}

/** Строка задачи: статус, причина отказа и признак отложенных правок (T-1.1, T-1.59). */
@Composable
fun TaskStatusLine(task: Task?, modifier: Modifier = Modifier) {
    Column(modifier = modifier.testTag("task-status")) {
        val status = if (task == null) {
            Strings.text(Strings.taskStatusNone)
        } else {
            Strings.text(taskStatusResource(task.status))
        }
        Text(status, style = MaterialTheme.typography.bodyLarge)
        if (task != null && task.status == TaskStatus.FAILED) {
            Text(
                Strings.text(taskFailureResource(task.failureReason)),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        // Пока ссылка на отложенное непуста, правки человека лежат в стороне и ждут возврата.
        // Признак едет в самой задаче, поэтому отдельного сообщения протокола не нужно (T-1.59).
        if (task != null && task.stashRef != null) {
            Text(Strings.text(Strings.taskStashPending), style = MaterialTheme.typography.bodySmall)
        }
    }
}
