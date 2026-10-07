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
 * готовый план, не изображая модель. Клиент приходит параметром, а не полем: модель
 * выбирается один раз на старте прогона (T-1.56), и планирование обязано идти той же
 * моделью, что и шаги, — иначе половина прогона шла бы одной моделью, а половина другой.
 */
fun interface RunPlanner {

    /**
     * Возвращает шаги плана в порядке выполнения.
     *
     * [comment] — уточнение пользователя при перепланировании; null при первом плане.
     * Отдельный параметр, а не часть задачи: комментарий уходит своей репликой (T-1.2).
     */
    suspend fun plan(task: Task, llm: LlmClient, comment: String?): List<PlanStep>
}

/** Планирование через модель: запрос строит [PlannerPrompt], ответ разбирается им же. */
class LlmRunPlanner : RunPlanner {

    override suspend fun plan(task: Task, llm: LlmClient, comment: String?): List<PlanStep> =
        when (val response = llm.complete(PlannerPrompt.request(task, comment))) {
            is LlmResponse.Text -> PlannerPrompt.parse(response.text)
            is LlmResponse.Error -> throw LlmCallException(response)
        }
}
