package dev.aide.agent.prompt

import dev.aide.agent.llm.LlmRequest
import dev.aide.domain.PlanStep
import dev.aide.domain.Task

/**
 * Шаг плана → запрос к модели (T-1.1).
 *
 * В этой задаче шаг — один вызов модели: инструментов ещё нет, и единственная работа
 * шага — спросить, что агент собирается делать, и отметить шаг выполненным. В T-1.7
 * сюда добавятся инструменты, и запрос расширится, а движок останется прежним.
 */
object StepPrompt {

    private const val SYSTEM_PROMPT: String =
        "Ты — агент разработки. Выполни шаг плана и ответь одной строкой, что сделано."

    /** Строит запрос на выполнение шага с учётом всего плана. */
    fun request(task: Task, step: PlanStep, plan: List<PlanStep>): LlmRequest = LlmRequest(
        system = SYSTEM_PROMPT,
        messages = listOf(
            task.prompt,
            "План:\n${plan.joinToString("\n") { it.summary }}",
            "Выполни шаг ${step.index + 1}: ${step.summary}",
        ),
    )
}
