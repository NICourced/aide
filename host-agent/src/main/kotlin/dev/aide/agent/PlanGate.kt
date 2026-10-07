package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.domain.AgentRun
import dev.aide.domain.PlanDecision
import dev.aide.domain.Task
import dev.aide.domain.planNeedsApproval
import kotlinx.coroutines.CancellationException

/**
 * Стоянка между планом и работой (T-1.2, FR-AGENT-7).
 *
 * Пока план не решён, работа не начинается: прогон остаётся в `PLANNED`, а ждёт его
 * корутина прогона. Отдельный тип, а не метод движка: ожидание с перепланированием —
 * самостоятельный шаг, и движок от него только читается.
 *
 * Требует ли режим подтверждения, решает [planNeedsApproval] — одно правило на хост и на
 * клиент (в домене), чтобы интерфейс не показывал кнопки там, где движок не ждёт.
 *
 * Перепланирование идёт тем же планировщиком с комментарием и **снова** приводит к стоянке:
 * число попыток не ограничено, потому что решает человек, а не счётчик в коде.
 */
internal class PlanGate(
    private val planner: RunPlanner,
    private val writer: RunStateWriter,
) {

    /**
     * Ждёт решения по плану, перепланируя по требованию; возвращает прогон для работы.
     *
     * Если режим подтверждения не требует, стоянки нет и прогон возвращается как есть —
     * работа начинается сразу. Перепланированный план сохраняется сразу (и уходит событием),
     * чтобы клиент увидел его до следующего решения, а не только после начала работы.
     */
    suspend fun await(task: Task, run: AgentRun, control: RunControl, llm: LlmClient): AgentRun {
        if (!planNeedsApproval(run.mode)) return run
        var current = run
        while (true) {
            // Стоп мог прийти до начала стоянки: корутина ещё не была привязана, и отменять
            // было нечего. Тогда ожидание обязано прерваться здесь, а не висеть вечно.
            if (control.stopRequested) throw CancellationException(STOP_MESSAGE)
            when (val decision = control.awaitDecision()) {
                PlanDecision.Approve -> {
                    control.discardPendingCommands()
                    return current
                }

                is PlanDecision.Replan ->
                    current = writer.persist(current.copy(plan = planner.plan(task, llm, decision.comment)))
            }
        }
    }
}
