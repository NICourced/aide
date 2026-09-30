package dev.aide.agent.provider.openai

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import io.ktor.client.engine.mock.respond
import io.ktor.client.engine.mock.respondError
import io.ktor.client.engine.mock.toByteArray
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.http.headersOf
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject

/**
 * T-1.56: адаптер chat completions на записанных запросах и ответах.
 *
 * Сети здесь нет вовсе (О-11): `MockEngine` отвечает тем, что положил тест, а запрос
 * проверяется по тому, что адаптер действительно отправил. Настоящий провайдер
 * в CI не вызывается и вызываться не будет — сквозной путь проверяется на локальной
 * заглушке в `host-core`.
 */
class OpenAiCompatibleClientTest {

    private val provider = ProviderProfile(
        id = "test",
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = "https://api.example.test/v1",
        apiKeyEnv = "TEST_API_KEY",
    )

    private fun model(
        contextWindow: Int = 128_000,
        priceIn: Long? = 2_000_000,
        priceOut: Long? = 4_000_000,
        toolUse: Boolean = false,
    ): ModelProfile = ModelProfile(
        alias = "test/model",
        provider = "test",
        model = "test-model",
        contextWindow = contextWindow,
        maxOutputTokens = 512,
        toolUse = toolUse,
        pricePerMillionInMicros = priceIn,
        pricePerMillionOutMicros = priceOut,
    )

    /** Успешный ответ chat completions: один вариант, токены в `usage`. */
    private fun completion(
        text: String = "готово",
        promptTokens: Int = 500,
        completionTokens: Int = 1_000,
    ): String = """
        {
          "choices": [{"index": 0, "message": {"role": "assistant", "content": "$text"}}],
          "usage": {"prompt_tokens": $promptTokens, "completion_tokens": $completionTokens}
        }
    """.trimIndent()

    private fun request(
        apiKey: String? = "секрет-в-окружении",
        model: ModelProfile = model(),
        body: String = completion(),
        engine: MockEngine = jsonEngine(body),
    ): Pair<OpenAiCompatibleClient, MockEngine> =
        OpenAiCompatibleClient(provider, model, apiKey, HttpClient(engine)) to engine

    private fun jsonEngine(body: String, status: HttpStatusCode = HttpStatusCode.OK): MockEngine =
        MockEngine { respond(body, status, headersOf(HttpHeaders.ContentType, "application/json")) }

    private val tools = listOf(
        LlmToolDefinition(
            name = "read_file",
            description = "Прочитать файл",
            argumentsSchema = Json.parseToJsonElement(
                """{"type":"object","properties":{"path":{"type":"string"}}}""",
            ) as JsonObject,
        ),
    )

    private val ask = LlmRequest(
        messages = listOf(LlmMessage.system("Ты — агент"), LlmMessage.user("Почини сборку")),
    )

    @Test
    fun `запрос несёт модель, предел вывода и роли сообщений`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask)

        val sent = engine.requestHistory.single()
        val body = sent.body.toByteArray().decodeToString()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("https://api.example.test/v1/chat/completions", sent.url.toString())
        assertTrue(body.contains(""""model":"test-model""""), "модель обязана уехать на сервер: $body")
        assertTrue(body.contains(""""max_tokens":512"""), "предел вывода обязателен: $body")
        assertTrue(body.contains(""""role":"system""""), "системная часть едет отдельной репликой: $body")
        assertTrue(body.contains(""""role":"user""""), "реплика пользователя обязана уехать: $body")
        assertTrue(body.contains("Ты — агент"), "текст системной части потерян: $body")
    }

    @Test
    fun `ключ уходит заголовком авторизации, а свои заголовки провайдера тоже`() = runBlocking {
        val withHeaders = provider.copy(customHeaders = mapOf("X-Gateway" to "aide"))
        val engine = jsonEngine(completion())
        val client = OpenAiCompatibleClient(withHeaders, model(), "секрет-в-окружении", HttpClient(engine))

        client.complete(ask)

        val headers = engine.requestHistory.single().headers
        assertEquals("Bearer секрет-в-окружении", headers[HttpHeaders.Authorization])
        assertEquals("aide", headers["X-Gateway"])
    }

    @Test
    fun `без ключа заголовок авторизации не отправляется`() = runBlocking {
        // Локальным серверам ключ не нужен: пустой заголовок «Bearer » они приняли бы
        // за неверный ключ, а не за его отсутствие.
        val (client, engine) = request(apiKey = null)

        client.complete(ask)

        assertNull(engine.requestHistory.single().headers[HttpHeaders.Authorization])
    }

    @Test
    fun `ответ ассистента с вызовом и результат инструмента уезжают своими ролями`() = runBlocking {
        // T-1.7: после вызова инструмента разговор продолжается, а не начинается заново.
        // Роли у этих двух реплик разные, и одна роль на двоих сломала бы провайдеру
        // разбор диалога: он не нашёл бы, к какому вызову относится результат.
        val (client, engine) = request()
        val continuation = ask.copy(messages = ask.messages + continuationMessages())

        client.complete(continuation)

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""role":"assistant""""), "роль ассистента обязана уехать: $body")
        assertTrue(body.contains(""""tool_calls""""), "вызовы едут в ответе ассистента: $body")
        assertTrue(body.contains(""""type":"function""""), "вызов описан функцией этого протокола: $body")
        assertTrue(body.contains(""""role":"tool""""), "результат инструмента едет ролью tool: $body")
        assertTrue(body.contains(""""tool_call_id":"call_1""""), "результат обязан ссылаться на вызов: $body")
        assertTrue(body.contains("fun main() = Unit"), "текст результата обязан уехать: $body")
    }

    private fun continuationMessages(): List<LlmMessage> = listOf(
        LlmMessage.assistant(
            text = "",
            toolCalls = listOf(LlmToolCall(id = "call_1", name = "read_file", arguments = ARGUMENTS)),
        ),
        LlmMessage.tool(callId = "call_1", text = FILE_TEXT),
    )

    private companion object {

        /** Аргументы вызова так, как их прислал провайдер: строкой JSON. */
        const val ARGUMENTS: String = """{"path":"a.kt"}"""

        /** Содержимое, которое инструмент вернул модели. */
        const val FILE_TEXT: String = "fun main() = Unit"
    }

    @Test
    fun `без инструментов поля tools в запросе нет`() = runBlocking {
        // Пустой список инструментов — это «вызывать нечего», и незачем отправлять его
        // серверу: поле появляется только вместе с определениями (T-1.57).
        val (client, engine) = request(model = model(toolUse = false))

        client.complete(ask)

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""messages""""), "тело запроса обязано быть непустым: $body")
        assertTrue(""""tools"""" !in body, "поля tools в запросе быть не должно: $body")
    }

    @Test
    fun `запрос с инструментами несёт tools в формате chat completions`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask.copy(tools = tools))

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""tools""""), "определения инструментов обязаны уехать: $body")
        assertTrue(body.contains(""""type":"function""""), "элемент списка описывает функцию: $body")
        assertTrue(body.contains(""""name":"read_file""""), "имя инструмента потеряно: $body")
        assertTrue(body.contains(""""description":"Прочитать файл""""), "описание потеряно: $body")
        assertTrue(body.contains(""""parameters""""), "схема аргументов eдет полем parameters: $body")
        assertTrue(""""path"""" in body, "схема аргументов обязана уехать целиком: $body")
    }

    @Test
    fun `tool_calls ответа превращаются в вызовы инструментов`() = runBlocking {
        val body = """
            {
              "choices": [{
                "index": 0,
                "finish_reason": "tool_calls",
                "message": {
                  "role": "assistant",
                  "content": null,
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "read_file", "arguments": "{\"path\":\"a.kt\"}"}
                  }]
                }
              }],
              "usage": {"prompt_tokens": 10, "completion_tokens": 20}
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals("", text.text, "ответ одним вызовом инструмента — это не ответ текстом")
        assertEquals(
            listOf(LlmToolCall(id = "call_1", name = "read_file", arguments = """{"path":"a.kt"}""")),
            text.toolCalls,
        )
    }

    @Test
    fun `вызов инструмента без аргументов делает ответ неразбираемым`() = runBlocking {
        // Пустая строка не является строкой JSON: отдать её значило бы перенести отказ
        // разбора ответа в разбор аргументов у вызывающего.
        val body = """
            {
              "choices": [{
                "index": 0,
                "message": {
                  "role": "assistant",
                  "tool_calls": [{
                    "id": "call_1",
                    "type": "function",
                    "function": {"name": "read_file"}
                  }]
                }
              }]
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `обрезанный по пределу вывода ответ виден по finish_reason`() = runBlocking {
        val body = """
            {
              "choices": [{
                "index": 0,
                "finish_reason": "length",
                "message": {"role": "assistant", "content": "не докон"}
              }]
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertTrue(text.truncated, "обрезанный ответ обязан быть виден вызывающему, а не выглядеть полным")
    }

    @Test
    fun `полный ответ не помечается обрезанным`() = runBlocking {
        val body = """
            {
              "choices": [{"index": 0, "finish_reason": "stop", "message": {"content": "готово"}}]
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertFalse(text.truncated)
    }

    @Test
    fun `ответ разбирается в текст, и стоимость считается по ставкам`() = runBlocking {
        val (client, _) = request(model = model(priceIn = 2_000_000, priceOut = 4_000_000))

        val response = client.complete(ask)

        val text = assertIs<LlmResponse.Text>(response)
        assertEquals("готово", text.text)
        // 500 входных по 2 микро/токен и 1000 выходных по 4 микро/токен.
        assertEquals(Cost(amountMicros = 5_000, known = true), text.cost)
    }

    @Test
    fun `без ставок стоимость неизвестна, а не нулевая`() = runBlocking {
        val (client, _) = request(model = model(priceIn = null, priceOut = null))

        val response = client.complete(ask)

        assertEquals(Cost(amountMicros = 0, known = false), assertIs<LlmResponse.Text>(response).cost)
    }

    @Test
    fun `одна отсутствующая ставка делает стоимость неизвестной`() = runBlocking {
        val (client, _) = request(model = model(priceIn = 2_000_000, priceOut = null))

        val response = client.complete(ask)

        assertEquals(Cost(amountMicros = 0, known = false), assertIs<LlmResponse.Text>(response).cost)
    }

    @Test
    fun `ответ без usage даёт неизвестную стоимость`() = runBlocking {
        val body = """{"choices":[{"message":{"role":"assistant","content":"готово"}}]}"""
        val (client, _) = request(body = body)

        val response = client.complete(ask)

        assertEquals(Cost(amountMicros = 0, known = false), assertIs<LlmResponse.Text>(response).cost)
    }

    @Test
    fun `отказ по ключу даёт unauthorized, а не пустой ответ`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "no key") }
        val client = OpenAiCompatibleClient(provider, model(), "плохой", HttpClient(engine))

        val response = client.complete(ask)

        val error = assertIs<LlmResponse.Error>(response)
        assertEquals(LlmErrorKind.UNAUTHORIZED, error.kind)
    }

    @Test
    fun `превышение лимита даёт rateLimited`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests, "slow down") }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RATE_LIMITED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `ошибка сервера даёт requestFailed`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.REQUEST_FAILED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `разрыв соединения даёт requestFailed, а не исключение наружу`() = runBlocking {
        val engine = MockEngine { throw IOException("соединение разорвано") }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.REQUEST_FAILED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `неразбираемый ответ даёт responseUnreadable`() = runBlocking {
        val engine = MockEngine {
            respond("не json вовсе", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `ответ без текста модели даёт responseUnreadable`() = runBlocking {
        val (client, _) = request(body = """{"choices":[]}""")

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `проверка доступа запрашивает список моделей без токенов пользователя`() = runBlocking {
        val engine = jsonEngine("""{"data":[{"id":"test-model"}]}""")
        val client = OpenAiCompatibleClient(provider, model(), "секрет", HttpClient(engine))

        val failure = client.checkAccess()

        assertNull(failure, "ключ принят — отказа нет")
        val sent = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, sent.method)
        assertEquals("https://api.example.test/v1/models", sent.url.toString())
        assertEquals("Bearer секрет", sent.headers[HttpHeaders.Authorization])
    }

    @Test
    fun `проверка доступа на неверный ключ даёт unauthorized`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "bad key") }
        val client = OpenAiCompatibleClient(provider, model(), "плохой", HttpClient(engine))

        assertEquals(ModelCheckFailure.Unauthorized, client.checkAccess())
    }

    @Test
    fun `проверка доступа на лимит даёт rateLimited`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests, "slow") }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        assertEquals(ModelCheckFailure.RateLimited, client.checkAccess())
    }

    @Test
    fun `провайдер без списка моделей получает unsupported, а не ложный ответ о ключе`() = runBlocking {
        // 404/405 — «такого запроса у меня нет»: сказать «ключ верен» или «ключ неверен»
        // здесь одинаково неверно (решение 5).
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "no such endpoint") }
        val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

        assertEquals(ModelCheckFailure.Unsupported, client.checkAccess())
    }

    @Test
    fun `ошибка сервера при проверке доступа даёт requestFailed`() {
        runBlocking {
            val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
            val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

            assertIs<ModelCheckFailure.RequestFailed>(client.checkAccess())
        }
    }

    @Test
    fun `разрыв соединения при проверке доступа даёт requestFailed`() {
        runBlocking {
            val engine = MockEngine { throw IOException("соединение разорвано") }
            val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

            assertIs<ModelCheckFailure.RequestFailed>(client.checkAccess())
        }
    }

    @Test
    fun `разбираемый, но неожиданный ответ проверки даёт responseUnreadable`() {
        runBlocking {
            val engine = MockEngine {
                respond("не json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            val client = OpenAiCompatibleClient(provider, model(), "ключ", HttpClient(engine))

            assertIs<ModelCheckFailure.ResponseUnreadable>(client.checkAccess())
        }
    }

    @Test
    fun `базовый адрес с завершающим слэшем не удваивает разделитель`() = runBlocking {
        val engine = jsonEngine(completion())
        val trailing = provider.copy(baseUrl = "https://api.example.test/v1/")
        val client = OpenAiCompatibleClient(trailing, model(), "ключ", HttpClient(engine))

        client.complete(ask)

        assertEquals("https://api.example.test/v1/chat/completions", engine.requestHistory.single().url.toString())
    }
}
