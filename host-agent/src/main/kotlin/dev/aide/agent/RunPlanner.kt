package dev.aide.agent

import dev.aide.agent.llm.LlmCallException
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.prompt.PlannerPrompt
import dev.aide.domain.PlanStep
import dev.aide.domain.Task

/**
 * План по постановке задачи.
 *
 * Отдельный тип, а не «ещё один вызов модели»: план — не текст, и тесты подставляют
 * готовый план, не изображая модель. Реализация по умолчанию спрашивает модель.
 */
fun interface RunPlanner {

    /** Возвращает шаги плана в порядке выполнения. */
    suspend fun plan(task: Task): List<PlanStep>
}

/** Планирование через модель: запрос строит [PlannerPrompt], ответ разбирается им же. */
class LlmRunPlanner(private val llm: LlmClient) : RunPlanner {

    override suspend fun plan(task: Task): List<PlanStep> =
        when (val response = llm.complete(PlannerPrompt.request(task))) {
            is LlmResponse.Text -> PlannerPrompt.parse(response.text)
            is LlmResponse.Error -> throw LlmCallException(response)
        }
}
