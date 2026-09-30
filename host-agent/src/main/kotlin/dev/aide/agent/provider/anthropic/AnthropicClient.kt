package dev.aide.agent.provider.anthropic

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.ProviderClient
import dev.aide.agent.provider.checkFailureOf
import dev.aide.agent.provider.elapsedMillis
import dev.aide.agent.provider.errorKindOf
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
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import org.slf4j.LoggerFactory

/**
 * Адаптер протокола Anthropic Messages (T-1.57, О-2).
 *
 * Второй протокол в сборке и первая проверка того, что граница «протокол за интерфейсом»
 * выбрана верно: движок прогона видит только `LlmClient` и о формате Messages не знает.
 * Это не украшение архитектуры, а требование критерия задачи — если бы для Claude
 * понадобилась правка движка, различие протоколов протекло бы наружу.
 *
 * Отличия от chat completions живут здесь, а не в общих типах: системная часть едет
 * отдельным полем, сообщения несут только роль `user` (роли — T-1.40), инструменты
 * описываются `input_schema`, а вызовы приходят блоками `tool_use`. Учёт токенов и
 * стоимость — наоборот, общие: ставки берутся из профиля модели, а `Cost` считает
 * один и тот же код, что и для OpenAI-совместимого (решение 3).
 *
 * Запросы не потоковые (О-2): адаптер ждёт ответ целиком и отдаёт его после разбора.
 *
 * @param http клиент Ktor с таймаутами хоста (`ProviderTimeouts` в `host-core`); в тестах —
 *   на `MockEngine` с записанными ответами, сети в автоматических тестах нет (О-11).
 */
class AnthropicClient(
    private val provider: ProviderProfile,
    private val model: ModelProfile,
    private val apiKey: String?,
    private val http: HttpClient,
) : ProviderClient {

    private val logger = LoggerFactory.getLogger(AnthropicClient::class.java)

    /**
     * Ошибка любого транспорта — типизированный отказ, а не исключение наружу: движок
     * обязан перевести неудачу в состояние `FAILED`, а не упасть (О-9). Отмена корутины
     * при этом проходит насквозь — её проверяет `ensureActive`.
     */
    @Suppress("TooGenericExceptionCaught")
    override suspend fun complete(request: LlmRequest): LlmResponse {
        val startedNanos = System.nanoTime()
        return try {
            val response = http.post(endpoint(MESSAGES_PATH)) {
                applyHeaders()
                contentType(ContentType.Application.Json)
                setBody(anthropicRequestBody(model, request))
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

    /** Разбор ответа: код ошибки по статусу, разбор тела — на успешном ответе. */
    private suspend fun readCompletion(response: HttpResponse, elapsed: Long): LlmResponse {
        val status = response.status
        if (status != HttpStatusCode.OK) {
            return LlmResponse.Error(errorKindOf(status), "HTTP ${status.value}")
        }
        return anthropicCompletion(response.bodyAsText(), model, elapsed)
    }

    private fun endpoint(path: String): String = "${provider.baseUrl.trimEnd('/')}/$path"

    /**
     * Заголовки запроса: ключ и версия протокола, без которых провайдер не отвечает вовсе.
     *
     * Ключ уходит заголовком `x-api-key`, а не `Authorization`: это требование протокола,
     * и адаптер не переводит его на общий вид — иначе сервис видел бы «неверный ключ» там,
     * где ключ просто отправлен чужим заголовком.
     */
    private fun HttpRequestBuilder.applyHeaders() {
        apiKey?.let { header(API_KEY_HEADER, it) }
        header(VERSION_HEADER, ANTHROPIC_VERSION)
        provider.customHeaders.forEach { (name, value) -> header(name, value) }
    }

    private companion object {

        /** Путь запроса ответа модели; базовый адрес приходит из профиля провайдера. */
        const val MESSAGES_PATH: String = "messages"

        /** Путь списка моделей: им проверяется доступ, без вызова самой модели. */
        const val MODELS_PATH: String = "models"

        /** Заголовок ключа у Anthropic; `Authorization` этот протокол не понимает. */
        const val API_KEY_HEADER: String = "x-api-key"

        /** Заголовок версии протокола: без него запрос отвергается. */
        const val VERSION_HEADER: String = "anthropic-version"
    }
}
