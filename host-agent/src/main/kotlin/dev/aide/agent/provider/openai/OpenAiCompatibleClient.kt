package dev.aide.agent.provider.openai

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.ProviderClient
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
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.longOrNull
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import org.slf4j.LoggerFactory

/** Микроединиц стоимости в единице тарификации провайдера. */
private const val MICROS_PER_MILLION: Long = 1_000_000

/** Наносекунд в миллисекунде: длительность вызова записывается в миллисекундах. */
private const val NANOS_PER_MILLI: Long = 1_000_000

/**
 * Адаптер протокола chat completions (T-1.56, О-2).
 *
 * Один адаптер обслуживает всех, кто говорит на этом формате: OpenAI, Moonshot/Kimi,
 * DeepSeek, Qwen, OpenRouter, Groq, Mistral, xAI, Gemini через совместимый эндпоинт
 * и локальные Ollama, LM Studio, vLLM. Различие между ними — адрес и ключ, а не код,
 * поэтому вендор здесь не назван ни разу.
 *
 * Запросы не потоковые (О-2): движок ждёт ответ целиком, а прогресс пользователь видит
 * по вызовам инструментов. Вызовов инструментов в запросе пока нет (T-1.7), поэтому
 * `toolUse` профиля описывает модель, а не то, что эта сборка уже умеет: поле появится
 * в запросе вместе с первым инструментом.
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
            val response = http.post(endpoint("chat/completions")) {
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
        val response = http.get(endpoint("models")) { applyHeaders() }
        accessFailure(response)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Проверка доступа к ${provider.id} не удалась: ${error.message}", error)
        ModelCheckFailure.RequestFailed(error.message)
    }

    /** Тело запроса: модель, предел вывода и диалог с системной частью впереди. */
    private fun requestBody(request: LlmRequest): String = buildJsonObject {
        put("model", model.model)
        put("max_tokens", model.maxOutputTokens)
        putJsonArray("messages") {
            add(message("system", request.system))
            request.messages.forEach { add(message("user", it)) }
        }
    }.toString()

    private fun message(role: String, content: String): JsonObject = buildJsonObject {
        put("role", role)
        put("content", content)
    }

    /**
     * Разбор ответа: текст, токены и стоимость.
     *
     * Нет `usage` — цена неизвестна, а не ноль (FR-COST-5): провайдер, не посчитавший
     * токены, не даёт права утверждать, что прогон ничего не стоил.
     */
    private suspend fun readCompletion(response: HttpResponse, elapsedMillis: Long): LlmResponse {
        val status = response.status
        if (status != HttpStatusCode.OK) {
            return LlmResponse.Error(kindOf(status), "HTTP ${status.value}")
        }
        val parsed = runCatching { Json.parseToJsonElement(response.bodyAsText()) }.getOrNull()
        val text = parsed?.choicesText()
        return when {
            parsed == null -> LlmResponse.Error(LlmErrorKind.RESPONSE_UNREADABLE, "ответ не является JSON")
            text == null -> LlmResponse.Error(LlmErrorKind.RESPONSE_UNREADABLE, "в ответе нет текста модели")
            else -> LlmResponse.Text(text = text, cost = parsed.cost(model), elapsedMillis = elapsedMillis)
        }
    }

    /** Ответ на `GET /models`: 401/403 — ключ, 429 — лимит, 404/405 — запрос не поддерживается. */
    private suspend fun accessFailure(response: HttpResponse): ModelCheckFailure? = when (response.status.value) {
        HttpStatusCode.OK.value ->
            if (response.isJson()) null else ModelCheckFailure.ResponseUnreadable("ответ не JSON")

        HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> ModelCheckFailure.Unauthorized
        HttpStatusCode.TooManyRequests.value -> ModelCheckFailure.RateLimited
        HttpStatusCode.NotFound.value, HttpStatusCode.MethodNotAllowed.value -> ModelCheckFailure.Unsupported
        else -> ModelCheckFailure.RequestFailed("HTTP ${response.status.value}")
    }

    private suspend fun HttpResponse.isJson(): Boolean =
        runCatching { Json.parseToJsonElement(bodyAsText()) }.isSuccess

    private fun endpoint(path: String): String = "${provider.baseUrl.trimEnd('/')}/$path"

    /** Ключ уходит только тем провайдерам, у которых он есть: локальным серверам он не нужен. */
    private fun HttpRequestBuilder.applyHeaders() {
        apiKey?.let { header(HttpHeaders.Authorization, "Bearer $it") }
        provider.customHeaders.forEach { (name, value) -> header(name, value) }
    }
}

/** Цена по ставкам профиля; отсутствие любой из ставок делает итог неизвестным. */
private fun JsonElement.cost(model: ModelProfile): Cost {
    val usage = ((this as? JsonObject)?.get("usage") as? JsonObject)
    val inRate = model.pricePerMillionInMicros
    val outRate = model.pricePerMillionOutMicros
    if (usage == null || inRate == null || outRate == null) {
        // Провайдер, не посчитавший токены, не даёт права считать прогон бесплатным (FR-COST-5).
        return Cost(known = false)
    }
    val inTokens = usage.tokenCount("prompt_tokens")
    val outTokens = usage.tokenCount("completion_tokens")
    return Cost(
        amountMicros = inTokens * inRate / MICROS_PER_MILLION + outTokens * outRate / MICROS_PER_MILLION,
        known = true,
    )
}

/** Текст первого ответа модели: у chat completions это `choices[0].message.content`. */
private fun JsonElement.choicesText(): String? {
    val choice = (this as? JsonObject)?.get("choices")?.let { it as? JsonArray }?.firstOrNull() as? JsonObject
    val content = (choice?.get("message") as? JsonObject)?.get("content") as? JsonPrimitive
    return content?.contentOrNull?.takeIf { it.isNotBlank() }
}

/** Число токенов в `usage`; отсутствие поля считается нулём — иначе отказ был бы непонятен. */
private fun JsonObject.tokenCount(field: String): Long =
    (this[field] as? JsonPrimitive)?.longOrNull ?: 0

/** Длительность вызова в миллисекундах. */
private fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

/** Как ошибка HTTP отображается в код причины отказа модели (решение 7). */
private fun kindOf(status: HttpStatusCode): LlmErrorKind = when (status.value) {
    HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> LlmErrorKind.UNAUTHORIZED
    HttpStatusCode.TooManyRequests.value -> LlmErrorKind.RATE_LIMITED
    else -> LlmErrorKind.REQUEST_FAILED
}
