package dev.aide.host

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.openai.OpenAiCompatibleClient
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.pluginOrNull
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * T-1.56: таймауты запроса к провайдеру заданы явно, а не умолчанием движка.
 *
 * Умолчание CIO — пятнадцать секунд на запрос целиком; ответ на `max_tokens` в 16 тысяч
 * у медленного провайдера идёт дольше, и такой запрос молча обрывался бы, превращаясь
 * в «провайдер не ответил». Проверяется сборка, которой пользуется хост: тест подставляет
 * движок, но конфигурацию таймаутов берёт у неё, а не у себя.
 */
class ProviderHttpTest {

    private val provider = ProviderProfile(
        id = "stub",
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://api.example.test/v1",
        apiKeyEnv = null,
    )

    private val model = ModelProfile(
        alias = "stub/model",
        provider = "stub",
        model = "stub-model",
        contextWindow = 32_000,
        maxOutputTokens = 16_384,
        toolUse = false,
    )

    private val request = LlmRequest(
        messages = listOf(LlmMessage.system("Ты — агент"), LlmMessage.user("Почини сборку")),
    )

    @Test
    fun `умолчание таймаутов щедрое и согласовано между собой`() {
        // Умолчание CIO — 15 секунд на запрос целиком. Ответ на 16 тысяч токенов у
        // медленного провайдера идёт дольше, поэтому числа заданы здесь и связаны:
        // ждать ответ целиком не меньше, чем паузу в соединении, а её — не меньше,
        // чем установление соединения.
        val timeouts = ProviderTimeouts()

        assertTrue(
            timeouts.requestMillis > CIO_DEFAULT_REQUEST_MILLIS,
            "умолчание библиотеки мало для не-потокового запроса агента",
        )
        assertTrue(
            timeouts.requestMillis >= timeouts.socketMillis,
            "ответ целиком ждут не меньше, чем паузу в соединении",
        )
        assertTrue(
            timeouts.socketMillis >= timeouts.connectMillis,
            "пауза в соединении не короче установления соединения",
        )
    }

    @Test
    fun `сборка ставит плагин таймаутов`() {
        // Числа внутри плагина Ktor наружу не отдаёт, поэтому проверяется установка плагина
        // и его поведение (две проверки ниже), а то, что хост передаёт именно умолчания
        // `ProviderTimeouts`, видно по единственному вызову `providerHttpClient()` без
        // аргументов — и проверено выше связью самих чисел.
        val http = providerHttpClient(MockEngine { respond(completion(), HttpStatusCode.OK) })

        assertNotNull(http.pluginOrNull(HttpTimeout), "плагин таймаутов обязан быть установлен")
    }

    @Test
    fun `ответ дольше таймаута даёт типизированный отказ, а не зависание`() {
        runBlocking {
            val engine = MockEngine {
                delay(SLOW_RESPONSE_MILLIS)
                respond(completion(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = providerHttpClient(engine, shortTimeouts())
            val client = OpenAiCompatibleClient(provider, model, null, http)

            // Внешний предел страхует от зависания теста: если плагина нет, ответ придёт
            // через SLOW_RESPONSE_MILLIS и утверждение ниже не сойдётся — тест не повиснет.
            val response = withTimeout(SLOW_RESPONSE_MILLIS * 4) { client.complete(request) }

            assertEquals(LlmErrorKind.REQUEST_FAILED, assertIs<LlmResponse.Error>(response).kind)
        }
    }

    @Test
    fun `быстрый ответ укладывается в тот же короткий таймаут`() {
        // Контроль к предыдущей проверке: короткий таймаут не должен обрывать нормальный
        // ответ, иначе «отказ» доказывал бы только то, что клиент сломан.
        runBlocking {
            val engine = MockEngine {
                respond(completion(), HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "application/json"))
            }
            val http = providerHttpClient(engine, shortTimeouts())
            val client = OpenAiCompatibleClient(provider, model, null, http)

            val response = client.complete(request)

            assertEquals("готово", assertIs<LlmResponse.Text>(response).text)
        }
    }

    /** Таймауты, короткие для теста: свой срок вместо минут ожидания. */
    private fun shortTimeouts(): ProviderTimeouts = ProviderTimeouts(
        connectMillis = TEST_TIMEOUT_MILLIS,
        socketMillis = TEST_TIMEOUT_MILLIS,
        requestMillis = TEST_TIMEOUT_MILLIS,
    )

    private fun completion(): String = """
        {
          "choices": [{"index": 0, "message": {"role": "assistant", "content": "готово"}}],
          "usage": {"prompt_tokens": 10, "completion_tokens": 20}
        }
    """.trimIndent()

    private companion object {
        /** Срок в тесте: секунды ожидания в тесте — потеря времени, а не проверка. */
        const val TEST_TIMEOUT_MILLIS = 150L

        /** Насколько «медленный» провайдер: заметно дольше тестового таймаута. */
        const val SLOW_RESPONSE_MILLIS = 800L

        /** Умолчание движка CIO, которое не подходит не-потоковому запросу агента. */
        const val CIO_DEFAULT_REQUEST_MILLIS = 15_000L
    }
}
