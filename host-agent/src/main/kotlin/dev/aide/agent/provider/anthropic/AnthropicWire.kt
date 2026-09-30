package dev.aide.agent.provider.anthropic

import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.agent.provider.costOf
import dev.aide.agent.provider.stringField
import dev.aide.agent.provider.tokenCount
import dev.aide.agent.provider.unreadableResponse
import dev.aide.domain.Cost
import dev.aide.domain.ModelProfile
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray

/**
 * Формат Messages API: тело запроса и разбор ответа (T-1.57).
 *
 * Сборка тела и разбор вынесены из клиента, потому что это чистые преобразования:
 * их проверяют на записанных запросах и ответах без сети, а сам клиент остаётся
 * тонким — транспорт, заголовки и перевод отказов.
 *
 * Разбор ответа строгий настолько, насколько это возможно без потери данных: блок,
 * объявленный текстовым или вызовом инструмента, но неполный, делает неразбираемым
 * **весь** ответ. Молча пропустить половину вызовов нельзя — агент выполнил бы часть
 * работы и не узнал об этом; а вот незнакомые типы блоков (например, рассуждения)
 * пропускаются: кода, который бы их понял, в этапе 1 нет.
 */

/** Версия протокола Messages, под которую написан адаптер. */
internal const val ANTHROPIC_VERSION: String = "2023-06-01"

/** Причина остановки «упёрлись в предел вывода»: ответ обрезан провайдером. */
private const val STOP_MAX_TOKENS: String = "max_tokens"

/** Типы блоков ответа, которые этап 1 умеет прочитать. */
private const val BLOCK_TEXT: String = "text"
private const val BLOCK_TOOL_USE: String = "tool_use"

/**
 * Тело запроса: модель, предел вывода, системная часть, реплики и инструменты.
 *
 * Системная часть — отдельное поле, а не реплика с ролью `system` (как у chat
 * completions): так устроен протокол. Поле `tools` появляется только тогда, когда
 * инструменты переданы: пустой список провайдер читает как «инструментов нет», но
 * отличать «нет вовсе» от «пусто» в запросе незачем.
 */
internal fun anthropicRequestBody(model: ModelProfile, request: LlmRequest): String = buildJsonObject {
    put("model", model.model)
    put("max_tokens", model.maxOutputTokens)
    put("system", request.system)
    putJsonArray("messages") { add(conversation(request.messages)) }
    if (request.tools.isNotEmpty()) {
        putJsonArray("tools") {
            request.tools.forEach { add(toolDefinition(it)) }
        }
    }
}.toString()

/**
 * Диалог одним сообщением: соседние реплики одной роли склеиваются переводом строки.
 *
 * Предосторожность, а не требование протокола из документации: проверить правило
 * чередования ролей по документации не удалось (она недоступна из окружения), а реплик
 * подряд у нас много — `StepPrompt` шлёт постановку, план и шаг тремя сообщениями. Если
 * сервис требует чередования, склейка обязательна; если он склеивает подряд идущие
 * сообщения сам, склейка ничего не меняет. Неверно только третье чтение — что сервис
 * различает границы реплик внутри одной роли, — но его не подтверждает ни один источник.
 */
private fun conversation(messages: List<String>): JsonObject = userMessage(messages.joinToString("\n"))

/** Реплика пользователя: в этапе 1 диалог состоит только из них (роли — T-1.40). */
private fun userMessage(content: String): JsonObject = buildJsonObject {
    put("role", "user")
    put("content", content)
}

/** Определение инструмента в формате Messages: схема аргументов называется `input_schema`. */
private fun toolDefinition(tool: LlmToolDefinition): JsonObject = buildJsonObject {
    put("name", tool.name)
    put("description", tool.description)
    put("input_schema", tool.argumentsSchema)
}

/**
 * Разбор ответа: text / tool_use, токены, признак обрезанного ответа.
 *
 * `usage` отсутствует — цена неизвестна, а не ноль (FR-COST-5). Текст может быть
 * пустым: ответ, состоящий из вызовов инструментов, — обычное дело, и делать его
 * неразбираемым значило бы запретить модели вызывать инструмент вместо ответа словами.
 */
internal fun anthropicCompletion(body: String, model: ModelProfile, elapsedMillis: Long): LlmResponse {
    val parsed = runCatching { Json.parseToJsonElement(body) }.getOrNull() as? JsonObject
    val blocks = parsed?.get("content") as? JsonArray
    val text = blocks?.textOf()
    val calls = blocks?.toolCallsOf()
    return when {
        parsed == null -> unreadableResponse("ответ не является JSON-объектом")
        blocks == null || text == null || calls == null ->
            unreadableResponse("в ответе нет разбираемых блоков content")

        text.isBlank() && calls.isEmpty() -> unreadableResponse("в ответе нет ни текста, ни вызова инструментов")
        else -> LlmResponse.Text(
            text = text,
            cost = parsed.costOf(model),
            elapsedMillis = elapsedMillis,
            toolCalls = calls,
            truncated = parsed.stopReason() == STOP_MAX_TOKENS,
        )
    }
}

/** Цена по токенам `usage`; нет `usage` — цена неизвестна, а не ноль (FR-COST-5). */
private fun JsonObject.costOf(model: ModelProfile): Cost {
    val usage = this["usage"] as? JsonObject ?: return Cost(known = false)
    return costOf(usage.tokenCount("input_tokens"), usage.tokenCount("output_tokens"), model)
}

/** Причина остановки: по ней видно, оборвал ли провайдер ответ на пределе вывода. */
private fun JsonObject.stopReason(): String? = stringField("stop_reason")

/**
 * Склейка текстовых блоков; null — блок не объект или объявлен текстовым без текста.
 *
 * Ровно как в примерах Anthropic, ответ несёт список блоков; в этапе 1 блок текста
 * бывает один, но склейка не полагается на это: два текстовых блока — это тоже
 * законный ответ.
 */
private fun JsonArray.textOf(): String? {
    val parts = mutableListOf<String>()
    var broken = false
    for (element in this) {
        val block = element as? JsonObject
        val value = block?.stringField("text")
        when {
            block == null || (block.typeOf() == BLOCK_TEXT && value == null) -> broken = true
            block.typeOf() == BLOCK_TEXT -> parts += value.orEmpty()
            else -> Unit
        }
    }
    return if (broken) null else parts.joinToString("")
}

/** Вызовы инструментов; null — блок не объект или объявлен вызовом, но имени или id в нём нет. */
private fun JsonArray.toolCallsOf(): List<LlmToolCall>? {
    val calls = mutableListOf<LlmToolCall>()
    var broken = false
    for (element in this) {
        val block = element as? JsonObject
        val call = block?.toolUse()
        when {
            block == null || (block.typeOf() == BLOCK_TOOL_USE && call == null) -> broken = true
            call != null -> calls += call
            else -> Unit
        }
    }
    return if (broken) null else calls
}

/**
 * Блок `tool_use`: id, имя и аргументы объектом; null — блок не вызов инструмента или неполон.
 *
 * Аргументов в списке неполноты не меньше, чем имени: контракт `LlmToolCall.arguments` —
 * строка JSON, и пустая строка ею не является. Отдать её значило бы заменить внятное
 * «провайдер прислал неполный вызов» на ошибку разбора аргументов у вызывающего.
 */
private fun JsonObject.toolUse(): LlmToolCall? {
    val id = stringField("id")
    val name = stringField("name")
    val arguments = argumentsText()
    val complete = typeOf() == BLOCK_TOOL_USE && !id.isNullOrBlank() && !name.isNullOrBlank() && arguments != null
    return if (complete) {
        LlmToolCall(id = id.orEmpty(), name = name.orEmpty(), arguments = arguments.orEmpty())
    } else {
        null
    }
}

/** Аргументы вызова строкой JSON; null — поля `input` нет или оно не объект: вызов неполон. */
private fun JsonObject.argumentsText(): String? = (this["input"] as? JsonObject)?.toString()

/** Тип блока ответа. */
private fun JsonObject.typeOf(): String? = stringField("type")
