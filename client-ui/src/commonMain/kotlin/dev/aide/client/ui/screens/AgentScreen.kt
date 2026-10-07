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
import dev.aide.client.state.HostSession
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.planStepStatusResource
import dev.aide.client.ui.strings.runStateResource
import dev.aide.client.ui.strings.taskFailureResource
import dev.aide.client.ui.strings.taskStatusResource
import dev.aide.client.ui.strings.testStateResource
import dev.aide.domain.AgentRun
import dev.aide.domain.PlanDecision
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import dev.aide.domain.planNeedsApproval
import dev.aide.domain.reportState

/**
 * Экран агента (T-1.1): состояние прогона и задачи, план, постановка задачи.
 *
 * Показываются оба статуса: задача бывает `QUEUED`, пока модель думает, и `FAILED`
 * ещё до появления прогона (например, провайдер не настроен), поэтому одной строки
 * прогона мало — отказ остался бы невидимым. [requestFailed] объединяет неудачную
 * постановку задачи и неудачный снимок состояния: оба означают «связи с хостом нет».
 * Кнопки паузы и стопа — задача T-1.4; поле с кнопкой заменит чат-ввод в T-1.40.
 *
 * Прогоны и задачи приходят снимком сессии, а не двумя списками: экран показывает одну
 * и ту же сессию агента, и держать её разобранной по частям значило бы позволить им
 * разойтись. Кнопка «Логи» ведёт в журнал вызовов (T-1.3): полноценные вкладки главного
 * экрана — T-1.48. Решение по плану (T-1.2) показывается, когда прогон ждёт его.
 */
@Composable
fun AgentScreen(
    session: HostSession,
    requestFailed: Boolean,
    actions: AgentActions,
    modifier: Modifier = Modifier,
) {
    var prompt by remember { mutableStateOf("") }
    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings.text(Strings.agentTitle), style = MaterialTheme.typography.titleLarge)
        RunStateLine(run = session.runs.lastOrNull())
        TestStatusLine(report = session.runs.lastOrNull()?.testReport)
        TaskStatusLine(task = session.tasks.lastOrNull())
        // План показывается до работы (T-1.2): список шагов со статусами, а в состоянии
        // ожидания — кнопки решения. Пустой план или отсутствие прогона оставляют это место пустым.
        PlanSection(run = session.runs.lastOrNull(), onDecidePlan = actions.decidePlan)
        if (requestFailed) {
            Text(
                Strings.text(Strings.agentRequestFailed),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.testTag("agent-request-error"),
            )
        }
        // Вход в журнал вызовов прогона: полноценные вкладки главного экрана — T-1.48,
        // здесь журнал открывается кнопкой с экрана агента (T-1.3).
        Button(onClick = actions.openLog, modifier = Modifier.testTag("open-log")) {
            Text(Strings.text(Strings.agentOpenLog))
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
                    actions.postTask(prompt)
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

/** Строка статуса тестов прогона: знает все значения `TestState` (T-1.10, NFR-13). */
@Composable
fun TestStatusLine(report: TestReport?, modifier: Modifier = Modifier) {
    val state = report?.reportState() ?: TestState.NOT_RUN
    val text = if (state == TestState.RED) {
        // Обрезанный список падений не должен выглядеть исчерпывающим: число и пометка
        // «показаны не все» берутся из признака обрезки, а не из размера усечённого списка.
        val resource = if (report?.failuresTruncated == true) Strings.testStatusRedTruncated else Strings.testStatusRed
        Strings.text(resource, report?.failures?.size ?: 0)
    } else {
        Strings.text(testStateResource(state))
    }
    Text(text, modifier = modifier.testTag("test-status"), style = MaterialTheme.typography.bodyMedium)
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

/**
 * План прогона: шаги по порядку и их состояния (T-1.2, FR-AGENT-7).
 *
 * Кнопки решения показываются только в состоянии ожидания `PLANNED` и только в режимах,
 * которые подтверждения требуют ([planNeedsApproval]): в «только предлагать» прогон не
 * ждёт решения, и кнопки были бы обманом. Пустой план и отсутствие прогона не рисуют ничего.
 */
@Composable
fun PlanSection(
    run: AgentRun?,
    onDecidePlan: (RunId, PlanDecision) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (run == null || run.plan.isEmpty()) return
    Column(modifier = modifier.testTag("plan"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Strings.text(Strings.agentPlanTitle), style = MaterialTheme.typography.titleMedium)
        run.plan.forEach { step -> PlanStepLine(step) }
        if (run.state == RunState.PLANNED && planNeedsApproval(run.mode)) {
            PlanDecisionControls(runId = run.id, onDecidePlan = onDecidePlan)
        }
    }
}

/** Строка шага: номер, описание и состояние — состояние тоже из ресурсов (NFR-13). */
@Composable
private fun PlanStepLine(step: PlanStep) {
    Text(
        Strings.text(
            Strings.planStepLine,
            step.index + 1,
            step.summary,
            Strings.text(planStepStatusResource(step.status)),
        ),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.testTag("plan-step"),
    )
}

/** Кнопки решения по плану: подтвердить и переделать с комментарием. */
@Composable
private fun PlanDecisionControls(
    runId: RunId,
    onDecidePlan: (RunId, PlanDecision) -> Unit,
) {
    var comment by remember { mutableStateOf("") }
    OutlinedTextField(
        value = comment,
        onValueChange = { comment = it },
        label = { Text(Strings.text(Strings.agentPlanCommentHint)) },
        modifier = Modifier.fillMaxWidth().testTag("plan-comment"),
    )
    Button(onClick = { onDecidePlan(runId, PlanDecision.Approve) }, modifier = Modifier.testTag("approve-plan")) {
        Text(Strings.text(Strings.agentPlanApprove))
    }
    // Перепланирование без комментария не отправляется: пустая реплика ничего не уточняет,
    // а на хост ушёл бы вызов планировщика впустую (T-1.2).
    Button(
        onClick = { if (comment.isNotBlank()) onDecidePlan(runId, PlanDecision.Replan(comment)) },
        modifier = Modifier.testTag("replan-plan"),
    ) {
        Text(Strings.text(Strings.agentPlanReplan))
    }
}
