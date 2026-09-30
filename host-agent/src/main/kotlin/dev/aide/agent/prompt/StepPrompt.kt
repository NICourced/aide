package dev.aide.agent.prompt

import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.domain.PlanStep
import dev.aide.domain.Task

/**
 * Шаг плана → запрос к модели (T-1.1, T-1.7).
 *
 * С этого запроса начинается шаг, а дальше диалог ведёт движок: модель просит
 * инструмент, движок дописывает её ответ и результат вызова к тем же репликам
 * и спрашивает снова. Поэтому здесь только начало разговора — постановка, план
 * и задание шага, — а инструменты уезжают определениями.
 */
object StepPrompt {

    private const val SYSTEM_PROMPT: String =
        "Ты — агент разработки. Выполни шаг плана и ответь одной строкой, что сделано. " +
            "Файлы проекта читай и ищи предоставленными инструментами, а не по памяти."

    /** Строит запрос на выполнение шага с учётом всего плана и доступных инструментов. */
    fun request(
        task: Task,
        step: PlanStep,
        plan: List<PlanStep>,
        tools: List<LlmToolDefinition>,
    ): LlmRequest = LlmRequest(
        messages = listOf(
            LlmMessage.system(SYSTEM_PROMPT),
            LlmMessage.user(task.prompt),
            LlmMessage.user("План:\n${plan.joinToString("\n") { it.summary }}"),
            LlmMessage.user("Выполни шаг ${step.index + 1}: ${step.summary}"),
        ),
        tools = tools,
    )
}
