package dev.aide.host.agent

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.ports.RunRepository
import dev.aide.client.state.HostClient
import dev.aide.client.state.decidePlan
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.PlanDecision
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import kotlin.test.assertNotNull
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Ставит задачу и подтверждает её план, как только план показан (T-1.2).
 *
 * Сквозные проверки, которым нужен ход работы, проходят стоянку плана явно: без решения
 * прогон в режимах FR-AGENT-2..4 не начинается, и ожидание финала висело бы вечно.
 * Отказ планирования сюда не попадает — там плана не появляется вовсе.
 */
suspend fun HostClient.postTaskApprovingPlan(prompt: String, mode: AutonomyMode): Result<TaskId> {
    val posted = postTask(prompt, mode)
    val taskId = posted.getOrElse { return posted }
    val planned = assertNotNull(awaitShownPlan(taskId), "план для задачи ${taskId.value} так и не показался")
    decidePlan(planned.id, PlanDecision.Approve).getOrThrow()
    return posted
}

/** Ждёт показанный план прогона этой задачи; null, если за отведённое время он не появился. */
suspend fun HostClient.awaitShownPlan(taskId: TaskId): AgentRun? {
    var planned = planOf(taskId)
    return withTimeoutOrNull(10_000) {
        while (planned == null) {
            delay(20)
            planned = planOf(taskId)
        }
        planned
    }
}

/** Стоящий прогон задачи из состояния клиента: он приходит событием (T-1.1). */
private fun HostClient.planOf(taskId: TaskId): AgentRun? =
    session.value.runs.lastOrNull { it.taskId == taskId && it.state == RunState.PLANNED }

/**
 * Выполняет одну задачу очереди, подтверждая показанный план (T-1.2).
 *
 * Для тестов, гоняющих движок напрямую, без клиента: идентификатор стоящего прогона они
 * берут из своего хранилища, а шлюз проходят явно — без этого прогон завис бы на стоянке.
 */
suspend fun AgentRunEngine.processNextApproving(
    runs: RunRepository,
    decision: PlanDecision = PlanDecision.Approve,
): Boolean {
    var processed = false
    coroutineScope {
        val worker = launch { processed = processNext() }
        val approver = launch {
            while (isActive) {
                runs.unfinished().lastOrNull { it.state == RunState.PLANNED }?.let { decidePlan(it.id, decision) }
                delay(2)
            }
        }
        awaitCompletion(worker)
        approver.cancel()
    }
    return processed
}

/** Дожидается корутины с ограничением: регрессия даёт падение, а не зависание набора. */
private suspend fun awaitCompletion(job: Job, timeoutMillis: Long = 5_000) {
    val finished = withTimeoutOrNull(timeoutMillis) {
        job.join()
        true
    }
    if (finished == null) {
        job.cancel()
        throw AssertionError("Корутина не завершилась за $timeoutMillis мс")
    }
}
