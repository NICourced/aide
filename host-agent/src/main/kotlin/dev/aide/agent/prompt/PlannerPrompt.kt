package dev.aide.agent.prompt

import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.domain.PlanStep
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/** Ответ модели не содержит разбираемого плана. */
class PlanFormatException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * Постановка задачи → план (FR-AGENT-7).
 *
 * Формат плана — часть контракта движка: модель возвращает объект
 * `{"steps": [{"summary": "…"}, …]}`, и разбор не полагается на то, что ответ
 * пришёл «чистым» JSON-ом. Мусор даёт внятный отказ, а не пустой план: пустой
 * план означал бы «делать нечего» и молча завершил бы прогон.
 */
object PlannerPrompt {

    /** Просьба к модели вернуть план в ожидаемом формате. */
    private const val SYSTEM_PROMPT: String =
        "Ты — агент разработки. Разбей задачу на короткие шаги и ответь строго JSON-ом " +
            "вида {\"steps\":[{\"summary\":\"что сделать\"}]}. Без пояснений вокруг JSON."

    /** Обрамление JSON-ом в markdown: модель часто заворачивает ответ в ```json. */
    private val FENCE = Regex("```(?:json)?\\s*(.*?)```", RegexOption.DOT_MATCHES_ALL)

    /** Строит запрос на планирование по поставленной задаче. */
    fun request(task: Task): LlmRequest = LlmRequest(
        messages = listOf(LlmMessage.system(SYSTEM_PROMPT), LlmMessage.user(task.prompt)),
    )

    /**
     * Разбирает ответ модели в шаги плана.
     *
     * @throws PlanFormatException если ответ не JSON, не содержит объект плана или в нём нет шагов.
     */
    fun parse(text: String): List<PlanStep> {
        val steps = readJson(text).objectOrNull()?.get("steps")?.arrayOrNull()
        if (steps == null || steps.isEmpty()) {
            throw PlanFormatException("В ответе модели нет шагов плана")
        }
        return steps.mapIndexed { index, step -> PlanStep(index, summary(step, index), StepStatus.PENDING) }
    }

    private fun readJson(text: String): JsonElement = runCatching { Json.parseToJsonElement(extractJson(text)) }
        .getOrElse { throw PlanFormatException("Ответ модели не является JSON", it) }

    /** Достаёт JSON-объект из ответа: из обрамления, а если его нет — по крайним скобкам. */
    private fun extractJson(text: String): String {
        val fenced = FENCE.find(text)?.groupValues?.get(1)?.trim()
        if (!fenced.isNullOrEmpty()) return fenced
        val start = text.indexOf('{')
        val end = text.lastIndexOf('}')
        if (start < 0 || end <= start) {
            throw PlanFormatException("В ответе модели нет JSON-объекта плана")
        }
        return text.substring(start, end + 1)
    }

    private fun summary(step: JsonElement, index: Int): String {
        val value = step.objectOrNull()?.get("summary")?.stringOrNull()
        return value?.takeIf { it.isNotBlank() }
            ?: throw PlanFormatException("Шаг ${index + 1} плана без описания")
    }
}

private fun JsonElement.objectOrNull(): JsonObject? = this as? JsonObject

private fun JsonElement.arrayOrNull(): JsonArray? = this as? JsonArray

private fun JsonElement.stringOrNull(): String? = (this as? JsonPrimitive)?.takeIf { it.isString }?.content
