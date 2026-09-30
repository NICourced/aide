package dev.aide.agent.provider.openai

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmRole
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.agent.provider.ProviderClient
import dev.aide.agent.provider.checkFailureOf
import dev.aide.agent.provider.costOf
import dev.aide.agent.provider.elapsedMillis
import dev.aide.agent.provider.errorKindOf
import dev.aide.agent.provider.stringField
import dev.aide.agent.provider.tokenCount
import dev.aide.agent.provider.unreadableResponse
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import io.ktor.client.HttpClient
import io.ktor.client.request.HttpRequestBuilder
import io.ktor.client.request.get
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject
import org.slf4j.LoggerFactory

/** Причина остановки, означающая обрезанный ответ: модель упёрлась в предел вывода. */
private const val FINISH_LENGTH: String = "length"

/**
 * Адаптер протокола chat completions (T-1.56, О-2).
 *
 * Один адаптер обслуживает всех, кто говорит на этом формате: OpenAI, Moonshot/Kimi,
 * DeepSeek, Qwen, OpenRouter, Groq, Mistral, xAI, Gemini через совместимый эндпоинт
 * и локальные Ollama, LM Studio, vLLM. Различие между ними — адрес и ключ, а не код,
 * поэтому вендор здесь не назван ни разу.
 *
 * Запросы не потоковые (О-2): движок ждёт ответ целиком, а прогресс пользователь видит
 * по вызовам инструментов. Инструменты мапятся на `tools` и `tool_calls` этого формата
 * (T-1.57): определения приходят в запросе, вызовы возвращаются в ответе одинаково у
 * обоих протоколов, а различие форматов остаётся здесь.
 *
 * @param http клиент Ktor: в тестах — на `MockEngine` с записанными ответами, сети
 *   в автоматических тестах нет (О-11).
 */
class OpenAiCompatibleClient(
    private val provider: ProviderProfile,
    private val model: ModelProfile,
    private val apiKey: String?,
    private val http: HttpClient,
) : ProviderClient {

    private val logger = LoggerFactory.getLogger(OpenAiCompatibleClient::class.java)

    /**
     * Ошибка любого транспорта — типизированный отказ, а не исключение наружу: движок
     * обязан перевести неудачу в состояние `FAILED`, а не упасть (О-9). Отмена корутины
     * при этом проходит насквозь — её проверяет `ensureActive`.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val startedNanos = System.nanoTime()
        return try {
            val response = http.post(endpoint(CHAT_PATH)) {
                applyHeaders()
                contentType(ContentType.Application.Json)
                setBody(requestBody(request))
            }
            readCompletion(response, elapsedMillis(startedNanos))
        } catch (error: Exception) {
            currentCoroutineContext().ensureActive()
            logger.warn("Провайдер ${provider.id} не ответил: ${error.message}", error)
            LlmResponse.Error(LlmErrorKind.REQUEST_FAILED, error.message)
        }
    }

    @Suppress("TooGenericExceptionCaught")
    override suspend fun checkAccess(): ModelCheckFailure? = try {
        val response = http.get(endpoint(MODELS_PATH)) { applyHeaders() }
        checkFailureOf(response)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Проверка доступа к ${provider.id} не удалась: ${error.message}", error)
        ModelCheckFailure.RequestFailed(error.message)
    }

    /**
     * Тело запроса: модель, предел вывода, диалог с системной частью впереди и инструменты.
     *
     * Диалог едет репликами своего протокола (T-1.7): роль, текст, `tool_calls`
     * у ответа модели и `tool_call_id` у результата инструмента. Поле `tools`
     * появляется только при непустом списке: провайдеры читают его как «вот что
     * можно вызывать», и отправлять пустой список незачем.
     */
    private fun requestBody(request: LlmRequest): String = buildJsonObject {
        put("model", model.model)
        put("max_tokens", model.maxOutputTokens)
        putJsonArray("messages") {
            request.messages.forEach { add(message(it)) }
        }
        if (request.tools.isNotEmpty()) {
            putJsonArray("tools") {
                request.tools.forEach { add(tool(it)) }
            }
        }
    }.toString()

    /** Реплика диалога в формате chat completions: роль, текст и вызовы инструментов. */
    private fun message(message: LlmMessage): JsonObject = buildJsonObject {
        put("role", message.role.roleName())
        // Содержимое отправляется всегда, даже пустым: у ответа одним вызовом
        // инструмента оно пустое, а `content: null` — форма, которую не все
        // совместимые серверы принимают одинаково.
        put("content", message.content)
        message.toolCallId?.let { put("tool_call_id", it) }
        if (message.toolCalls.isNotEmpty()) {
            putJsonArray("tool_calls") {
                message.toolCalls.forEach { add(toolCall(it)) }
            }
        }
    }

    /** Вызов инструмента в ответе модели: имя и аргументы, как их примет провайдер. */
    private fun toolCall(call: LlmToolCall): JsonObject = buildJsonObject {
        put("id", call.id)
        put("type", "function")
        putJsonObject("function") {
            put("name", call.name)
            put("arguments", call.arguments)
        }
    }

    /** Определение инструмента в формате chat completions: тип `function` и её схема. */
    private fun tool(definition: LlmToolDefinition): JsonObject = buildJsonObject {
        put("type", "function")
        putJsonObject("function") {
            put("name", definition.name)
            put("description", definition.description)
            put("parameters", definition.argumentsSchema)
        }
    }

    /**
     * Разбор ответа: текст, вызовы инструментов, токены и стоимость.
     *
     * Нет `usage` — цена неизвестна, а не ноль (FR-COST-5): провайдер, не посчитавший
     * токены, не даёт права утверждать, что прогон ничего не стоил. Текст может быть
     * пустым, если модель ответила одним вызовом инструмента, — это не пустой ответ.
     */
    private suspend fun readCompletion(response: HttpResponse, elapsed: Long): LlmResponse {
        val status = response.status
        if (status != HttpStatusCode.OK) {
            return LlmResponse.Error(errorKindOf(status), "HTTP ${status.value}")
        }
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()) }.getOrNull()
        val choice = parsed?.firstChoice()
        val message = choice?.messageOf()
        // Содержимого может не быть вовсе: модель, отвечающая одним вызовом инструмента,
        // присылает `content: null`. Пустой текст и отсутствующие вызовы — вот что значит
        // «в ответе нет ничего».
        val text = message?.contentText().orEmpty()
        val calls = message?.toolCalls()
        return when {
            parsed == null -> unreadableResponse("ответ не является JSON")
            calls == null -> unreadableResponse("ответ без разбираемых вызовов инструментов")
            text.isBlank() && calls.isEmpty() -> unreadableResponse("в ответе нет текста модели")
            else -> LlmResponse.Text(
                text = text,
                cost = parsed.responseCost(model),
                elapsedMillis = elapsed,
                toolCalls = calls,
                truncated = choice?.stringField("finish_reason") == FINISH_LENGTH,
            )
        }
    }

    private fun endpoint(path: String): String = "${provider.baseUrl.trimEnd('/')}/$path"

    /** Ключ уходит только тем провайдерам, у которых он есть: локальным серверам он не нужен. */
    private fun HttpRequestBuilder.applyHeaders() {
        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        provider.customHeaders.forEach { (name, value) -> header(name, value) }
    }

    private companion object {

        /** Путь ответа модели в этом протоколе. */
        const val CHAT_PATH: String = "chat/completions"

        /** Путь списка моделей: им проверяется доступ, без вызова самой модели. */
        const val MODELS_PATH: String = "models"
    }
}

/** Первый вариант ответа: у chat completions модель отвечает в `choices[0]`. */
private fun JsonElement.firstChoice(): JsonObject? =
    ((this as? JsonObject)?.get("choices") as? JsonArray)?.firstOrNull() as? JsonObject

/**
 * Имя роли этого протокола.
 *
 * Записано явно, хотя совпадает с именами перечисления: имя роли — часть формата
 * провайдера, и связывать его с именем константы значило бы менять провод
 * переименованием в коде.
 */
private fun LlmRole.roleName(): String = when (this) {
    LlmRole.SYSTEM -> "system"
    LlmRole.USER -> "user"
    LlmRole.ASSISTANT -> "assistant"
    LlmRole.TOOL -> "tool"
}

/** Сообщение модели внутри варианта ответа. */
private fun JsonObject.messageOf(): JsonObject? = this["message"] as? JsonObject

/** Текст ответа; null — поля нет или оно не строка (содержимым может быть и список частей). */
private fun JsonObject.contentText(): String? = stringField("content")

/** Цена по токенам `usage`; нет `usage` — цена неизвестна, а не ноль (FR-COST-5). */
private fun JsonElement.responseCost(model: ModelProfile): Cost {
    val usage = ((this as? JsonObject)?.get("usage") as? JsonObject) ?: return Cost(known = false)
    return costOf(usage.tokenCount("prompt_tokens"), usage.tokenCount("completion_tokens"), model)
}

/**
 * Вызовы инструментов из сообщения; null — поле есть, но разобрать его нельзя.
 *
 * Пустое поле и отсутствие поля одинаковы: модель ответила текстом. А вот неполный
 * вызов делает неразбираемым весь ответ — выполнить его нельзя, и молча потерять
 * один из нескольких вызовов значило бы оставить работу наполовину сделанной.
 */
private fun JsonObject.toolCalls(): List<LlmToolCall>? {
    val raw = this["tool_calls"]
    val parsed = (raw as? JsonArray)?.map { (it as? JsonObject)?.functionCall() }
    return when {
        raw == null -> emptyList()
        parsed == null || parsed.any { it == null } -> null
        else -> parsed.filterNotNull()
    }
}

/**
 * Один вызов: id, имя и аргументы строкой — в этом формате они уже строка JSON.
 *
 * Отсутствующие аргументы — неполный вызов, а не вызов с пустыми: пустая строка не
 * является строкой JSON, и вызывающий получил бы ошибку разбора вместо отказа разбора
 * ответа.
 */
private fun JsonObject.functionCall(): LlmToolCall? {
    val function = this["function"] as? JsonObject
    val id = stringField("id")
    val name = function?.stringField("name")
    val arguments = function?.stringField("arguments")
    val complete = !id.isNullOrBlank() && !name.isNullOrBlank() && arguments != null
    return if (complete) {
        LlmToolCall(id = id.orEmpty(), name = name.orEmpty(), arguments = arguments.orEmpty())
    } else {
        null
    }
}
