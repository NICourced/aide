package dev.aide.agent.provider.anthropic

import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmRole
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
import kotlinx.serialization.json.JsonElement
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

/** Тип блока результата инструмента: результат отвечает реплика пользователя, а не ассистента. */
private const val BLOCK_TOOL_RESULT: String = "tool_result"

/** Имена ролей этого протокола. */
private const val ROLE_USER: String = "user"
private const val ROLE_ASSISTANT: String = "assistant"

/**
 * Тело запроса: модель, предел вывода, системная часть, реплики и инструменты.
 *
 * Системная часть — отдельное поле, а не реплика с ролью `system` (как у chat
 * completions): так устроен протокол. Диалог собирается репликами протокола (T-1.7):
 * у ответа модели вызовы инструментов становятся блоками `tool_use`, а их результаты
 * едут блоками `tool_result` в реплике **пользователя** — так требует Messages API.
 * Поле `tools` появляется только тогда, когда инструменты переданы: пустой список
 * провайдер читает как «инструментов нет», но отличать «нет вовсе» от «пусто»
 * в запросе незачем.
 */
internal fun anthropicRequestBody(model: ModelProfile, request: LlmRequest): String = buildJsonObject {
    put("model", model.model)
    put("max_tokens", model.maxOutputTokens)
    val system = request.messages.systemText()
    if (system.isNotEmpty()) put("system", system)
    putJsonArray("messages") { anthropicTurns(request.messages).forEach { add(it) } }
    if (request.tools.isNotEmpty()) {
        putJsonArray("tools") {
            request.tools.forEach { add(toolDefinition(it)) }
        }
    }
}.toString()

/** Системная часть: у Messages она отдельным полем, а не репликой диалога. */
private fun List<LlmMessage>.systemText(): String =
    filter { it.role == LlmRole.SYSTEM }.joinToString("\n") { it.content }

/**
 * Диалог репликами протокола: соседние реплики одной роли склеиваются.
 *
 * Предосторожность, а не требование протокола из документации: проверить правило
 * чередования ролей по документации не удалось (она недоступна из окружения), а реплик
 * подряд у нас много — `StepPrompt` шлёт постановку, план и шаг тремя сообщениями. Если
 * сервис требует чередования, склейка обязательна; если он склеивает подряд идущие
 * сообщения сам, склейка ничего не меняет. Неверно только третье чтение — что сервис
 * различает границы реплик внутри одной роли, — но его не подтверждает ни один источник.
 */
private fun anthropicTurns(messages: List<LlmMessage>): List<JsonObject> {
    val turns = mutableListOf<Turn>()
    messages.filter { it.role != LlmRole.SYSTEM }.forEach { message ->
        val role = wireRole(message.role)
        val turn = turns.lastOrNull()?.takeIf { it.role == role } ?: Turn(role).also { turns += it }
        turn.blocks += blocksOf(message)
    }
    return turns.map(Turn::toJson)
}

/** Реплика протокола с содержимым в виде блоков: текст и вызовы инструментов. */
private class Turn(val role: String) {

    val blocks: MutableList<JsonObject> = mutableListOf()

    fun toJson(): JsonObject = buildJsonObject {
        put("role", role)
        put("content", JsonArray(blocks))
    }
}

/**
 * Результат инструмента отвечает пользователь, а не ассистент: `tool_result` —
 * блок реплики `user`, как того требует Messages API.
 */
private fun wireRole(role: LlmRole): String = if (role == LlmRole.ASSISTANT) ROLE_ASSISTANT else ROLE_USER

/** Содержимое реплики блоками: текст, вызовы инструмента или результат вызова. */
private fun blocksOf(message: LlmMessage): List<JsonObject> = when (message.role) {
    LlmRole.SYSTEM -> emptyList()
    LlmRole.USER -> textBlocks(message.content)
    LlmRole.TOOL -> listOf(toolResultBlock(message))
    LlmRole.ASSISTANT -> textBlocks(message.content) + message.toolCalls.map(::toolUseBlock)
}

/** Текстовый блок; пустой текст блоком не становится — такого блока протокол не принимает. */
private fun textBlocks(text: String): List<JsonObject> =
    if (text.isBlank()) emptyList() else listOf(textBlock(text))

private fun textBlock(text: String): JsonObject = buildJsonObject {
    put("type", BLOCK_TEXT)
    put("text", text)
}

/** Вызов инструмента блоком `tool_use`: аргументы едут объектом, а не строкой. */
private fun toolUseBlock(call: LlmToolCall): JsonObject = buildJsonObject {
    put("type", BLOCK_TOOL_USE)
    put("id", call.id)
    put("name", call.name)
    put("input", inputOf(call.arguments))
}

/** Результат вызова блоком `tool_result`: привязка к вызову идёт по его идентификатору. */
private fun toolResultBlock(message: LlmMessage): JsonObject = buildJsonObject {
    put("type", BLOCK_TOOL_RESULT)
    put("tool_use_id", message.toolCallId.orEmpty())
    put("content", message.content)
}

/**
 * Аргументы вызова объектом.
 *
 * В общий тип они приходят строкой (у chat completions это формат провода), а здесь
 * нужен объект. Неразобранная строка даёт пустой объект, а не исключение: строка
 * получена от адаптера другого протокола, и «вызов без аргументов» — это ровно то,
 * что она означает.
 */
private fun inputOf(arguments: String): JsonElement =
    runCatching { Json.parseToJsonElement(arguments) }.getOrDefault(JsonObject(emptyMap()))

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
