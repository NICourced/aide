package dev.aide.agent.provider.anthropic

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmMessage
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.agent.provider.ProviderCatalog
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
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * T-1.57: адаптер Anthropic Messages на записанных запросах и ответах.
 *
 * Сети здесь нет вовсе (О-11): `MockEngine` отвечает тем, что положил тест, а запрос
 * проверяется по тому, что адаптер действительно отправил. Формат Messages отличается
 * от chat completions всем: заголовком ключа, версией протокола, местом системной части
 * и способом вызова инструментов, — поэтому проверяется каждый из этих пунктов.
 */
class AnthropicClientTest {

    private val provider = ProviderProfile(
        id = "test",
        type = ProviderType.ANTHROPIC,
        baseUrl = "https://api.example.test/v1",
        apiKeyEnv = "TEST_API_KEY",
    )

    private fun model(
        contextWindow: Int = 200_000,
        priceIn: Long? = 2_000_000,
        priceOut: Long? = 4_000_000,
        toolUse: Boolean = true,
    ): ModelProfile = ModelProfile(
        alias = "test/claude",
        provider = "test",
        model = "test-model",
        contextWindow = contextWindow,
        maxOutputTokens = 512,
        toolUse = toolUse,
        pricePerMillionInMicros = priceIn,
        pricePerMillionOutMicros = priceOut,
    )

    /** Ответ Messages: один текстовый блок и токены в `usage`. */
    private fun message(
        text: String = "готово",
        stopReason: String = "end_turn",
        inputTokens: Int = 500,
        outputTokens: Int = 1_000,
    ): String = """
        {
          "id": "msg_1",
          "type": "message",
          "role": "assistant",
          "content": [{"type": "text", "text": "$text"}],
          "stop_reason": "$stopReason",
          "usage": {"input_tokens": $inputTokens, "output_tokens": $outputTokens}
        }
    """.trimIndent()

    private fun request(
        apiKey: String? = "секрет-в-окружении",
        model: ModelProfile = model(),
        body: String = message(),
        engine: MockEngine = jsonEngine(body),
    ): Pair<AnthropicClient, MockEngine> =
        AnthropicClient(provider, model, apiKey, HttpClient(engine)) to engine

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

    /** Текстовые блоки реплик отправленного запроса: проверка формы `messages`, а не её отсутствия. */
    private suspend fun sentMessages(engine: MockEngine): List<List<String>> {
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        val messages = (Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray
        return messages.map { message ->
            val blocks = (message as JsonObject)["content"] as JsonArray
            blocks.mapNotNull { block -> ((block as? JsonObject)?.get("text") as? JsonPrimitive)?.content }
        }
    }

    @Test
    fun `запрос уходит на messages и несёт модель, предел вывода, систему и реплики`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask)

        val sent = engine.requestHistory.single()
        val body = sent.body.toByteArray().decodeToString()
        assertEquals(HttpMethod.Post, sent.method)
        assertEquals("https://api.example.test/v1/messages", sent.url.toString())
        assertTrue(body.contains(""""model":"test-model""""), "модель обязана уехать на сервер: $body")
        assertTrue(body.contains(""""max_tokens":512"""), "предел вывода обязателен: $body")
        assertTrue(body.contains(""""system":"Ты — агент""""), "системная часть едет своим полем: $body")
        assertTrue(body.contains(""""role":"user""""), "реплика пользователя обязана уехать: $body")
        assertTrue(""""role":"system"""" !in body, "системы среди реплик у этого протокола нет: $body")
    }

    @Test
    fun `подряд идущие реплики уезжают одним сообщением`() = runBlocking {
        // Реплик подряд у нас много (постановка, план, шаг), а Messages API — про
        // чередование ролей; склейка соседних реплик одной роли верна при обоих чтениях.
        // Границы реплик при этом сохраняются: каждая остаётся отдельным текстовым блоком,
        // иначе модель прочитала бы три задания одной слипшейся строкой.
        val (client, engine) = request()
        val three = ask.copy(
            messages = listOf(LlmMessage.user("Постановка"), LlmMessage.user("План"), LlmMessage.user("Шаг")),
        )

        client.complete(three)

        val messages = sentMessages(engine)
        assertEquals(1, messages.size, "три реплики одной роли обязаны уехать одним сообщением")
        assertEquals(listOf("Постановка", "План", "Шаг"), messages.single())
    }

    @Test
    fun `ключ уходит заголовком x-api-key, и версия протокола тоже`() = runBlocking {
        val withHeaders = provider.copy(customHeaders = mapOf("X-Gateway" to "aide"))
        val engine = jsonEngine(message())
        val client = AnthropicClient(withHeaders, model(), "секрет-в-окружении", HttpClient(engine))

        client.complete(ask)

        val headers = engine.requestHistory.single().headers
        assertEquals("секрет-в-окружении", headers["x-api-key"])
        assertEquals(ANTHROPIC_VERSION, headers["anthropic-version"])
        assertEquals("aide", headers["X-Gateway"])
        assertNull(headers[HttpHeaders.Authorization], "у этого протокола ключ идёт не в Authorization")
    }

    @Test
    fun `без ключа заголовок ключа не отправляется`() = runBlocking {
        val (client, engine) = request(apiKey = null)

        client.complete(ask)

        assertNull(engine.requestHistory.single().headers["x-api-key"])
    }

    @Test
    fun `запрос с инструментами несёт tools в формате Anthropic`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask.copy(tools = tools))

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""tools""""), "определения инструментов обязаны уехать: $body")
        assertTrue(body.contains(""""name":"read_file""""), "имя инструмента потеряно: $body")
        assertTrue(body.contains(""""description":"Прочитать файл""""), "описание потеряно: $body")
        assertTrue(body.contains(""""input_schema""""), "схема аргументов называется input_schema: $body")
        assertTrue(""""path"""" in body, "схема аргументов обязана уехать целиком: $body")
    }

    @Test
    fun `запрос без инструментов поля tools не несёт`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask)

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""messages""""), "тело запроса обязано быть непустым: $body")
        assertTrue(""""tools"""" !in body, "поля tools в запросе быть не должно: $body")
    }

    @Test
    fun `вызов инструмента и его результат уезжают блоками протокола`() = runBlocking {
        // T-1.7: разговор продолжается после вызова инструмента. У этого протокола
        // вызов — блок `tool_use` в реплике ассистента, результат — блок `tool_result`
        // в реплике пользователя, и склеить их в один блок значило бы потерять связь
        // результата с вызовом.
        val (client, engine) = request()
        val continuation = ask.copy(
            messages = ask.messages + listOf(
                LlmMessage.assistant(text = "сейчас посмотрю", toolCalls = listOf(toolCall)),
                LlmMessage.tool(callId = TOOL_CALL_ID, text = FILE_TEXT),
            ),
        )

        client.complete(continuation)

        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        assertTrue(body.contains(""""type":"tool_use""""), "вызов едет блоком tool_use: $body")
        assertTrue(body.contains(""""name":"read_file""""), "имя инструмента потеряно: $body")
        assertTrue(body.contains(""""input":{"path":"a.kt""""), "аргументы едут объектом, а не строкой: $body")
        assertTrue(body.contains(""""type":"tool_result""""), "результат едет блоком tool_result: $body")
        assertTrue(body.contains(""""tool_use_id":"$TOOL_CALL_ID""""), "результат обязан ссылаться на вызов: $body")
        assertTrue(body.contains(FILE_TEXT), "текст результата обязан уехать: $body")
    }

    @Test
    fun `результат инструмента уезжает репликой пользователя`() = runBlocking {
        val (client, engine) = request()
        val continuation = ask.copy(
            messages = ask.messages + listOf(
                LlmMessage.assistant(text = "", toolCalls = listOf(toolCall)),
                LlmMessage.tool(callId = TOOL_CALL_ID, text = FILE_TEXT),
            ),
        )

        client.complete(continuation)

        assertEquals(listOf("user", "assistant", "user"), sentRoles(engine))
    }

    @Test
    fun `системная часть репликой не уезжает — у неё своё поле`() = runBlocking {
        val (client, engine) = request()

        client.complete(ask)

        assertEquals(listOf("user"), sentRoles(engine), "системная реплика в диалог не попадает")
    }

    /** Имена ролей реплик отправленного запроса. */
    private suspend fun sentRoles(engine: MockEngine): List<String> {
        val body = engine.requestHistory.single().body.toByteArray().decodeToString()
        val messages = (Json.parseToJsonElement(body) as JsonObject)["messages"] as JsonArray
        return messages.map { ((it as JsonObject)["role"] as JsonPrimitive).content }
    }

    private companion object {

        /** Идентификатор вызова: по нему результат находит свой вызов. */
        const val TOOL_CALL_ID: String = "toolu_1"

        /** Содержимое, которое инструмент вернул модели. */
        const val FILE_TEXT: String = "fun main() = Unit"
    }

    /** Вызов, который просит модель: тот же инструмент чтения, что и в реестре хоста. */
    private val toolCall = LlmToolCall(id = TOOL_CALL_ID, name = "read_file", arguments = """{"path":"a.kt"}""")

    @Test
    fun `блок tool_use превращается в вызов инструмента с именем и аргументами`() = runBlocking {
        val body = """
            {
              "content": [
                {"type": "tool_use", "id": "toolu_1", "name": "read_file", "input": {"path": "a.kt"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 10, "output_tokens": 20}
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals("", text.text, "ответ одним вызовом инструмента — это не ответ текстом")
        assertEquals(
            listOf(LlmToolCall(id = "toolu_1", name = "read_file", arguments = """{"path":"a.kt"}""")),
            text.toolCalls,
        )
        assertFalse(text.truncated, "модель не обрывала ответ, а ждёт результат инструмента")
    }

    @Test
    fun `текст и вызов инструмента в одном ответе не теряются`() = runBlocking {
        val body = """
            {
              "content": [
                {"type": "text", "text": "Сейчас посмотрю."},
                {"type": "tool_use", "id": "toolu_2", "name": "read_file", "input": {"path": "b.kt"}}
              ],
              "stop_reason": "tool_use",
              "usage": {"input_tokens": 1, "output_tokens": 2}
            }
        """.trimIndent()
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals("Сейчас посмотрю.", text.text)
        assertEquals(listOf("read_file"), text.toolCalls.map { it.name })
    }

    @Test
    fun `ответ на пределе вывода приходит с признаком обрезки`() = runBlocking {
        val (client, _) = request(body = message(stopReason = "max_tokens"))

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertTrue(text.truncated, "обрезанный ответ обязан быть виден вызывающему, а не выглядеть полным")
    }

    @Test
    fun `стоимость считается по токенам сообщения и ставкам профиля`() = runBlocking {
        val (client, _) = request(model = model(priceIn = 2_000_000, priceOut = 4_000_000))

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        // 500 входных по 2 микро/токен и 1000 выходных по 4 микро/токен.
        assertEquals(Cost(amountMicros = 5_000, known = true), text.cost)
    }

    @Test
    fun `без ставок стоимость неизвестна, а не нулевая`() = runBlocking {
        val (client, _) = request(model = model(priceIn = null, priceOut = null))

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals(Cost(amountMicros = 0, known = false), text.cost)
    }

    @Test
    fun `ответ без usage даёт неизвестную стоимость`() = runBlocking {
        val body = """{"content":[{"type":"text","text":"готово"}]}"""
        val (client, _) = request(body = body)

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals(Cost(amountMicros = 0, known = false), text.cost)
    }

    @Test
    fun `ответ без блоков content даёт responseUnreadable, а не пустой ответ`() = runBlocking {
        val (client, _) = request(body = """{"usage":{"input_tokens":1,"output_tokens":2}}""")

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `пустой ответ модели даёт responseUnreadable`() = runBlocking {
        val (client, _) = request(body = """{"content":[]}""")

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `текстовый блок без текста делает ответ неразбираемым`() = runBlocking {
        // Пропустить такой блок значило бы вернуть пустой текст вместо ответа модели.
        val (client, _) = request(body = """{"content":[{"type":"text"}]}""")

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `вызов инструмента без имени делает ответ неразбираемым`() = runBlocking {
        val body = """{"content":[{"type":"tool_use","id":"toolu_1","input":{}}]}"""
        val (client, _) = request(body = body)

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `вызов инструмента без аргументов делает ответ неразбираемым`() = runBlocking {
        // Пустая строка не является строкой JSON: отдать её значило бы перенести отказ
        // разбора ответа в разбор аргументов у вызывающего.
        val body = """{"content":[{"type":"tool_use","id":"toolu_1","name":"read_file"}]}"""
        val (client, _) = request(body = body)

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `неразбираемый ответ даёт responseUnreadable`() = runBlocking {
        val engine = MockEngine {
            respond("не json вовсе", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
        }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RESPONSE_UNREADABLE, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `отказ по ключу даёт unauthorized, а не пустой ответ`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "no key") }
        val client = AnthropicClient(provider, model(), "плохой", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.UNAUTHORIZED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `превышение лимита даёт rateLimited`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests, "slow down") }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.RATE_LIMITED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `ошибка сервера даёт requestFailed`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.REQUEST_FAILED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `разрыв соединения даёт requestFailed, а не исключение наружу`() = runBlocking {
        val engine = MockEngine { throw IOException("соединение разорвано") }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        val response = client.complete(ask)

        assertEquals(LlmErrorKind.REQUEST_FAILED, assertIs<LlmResponse.Error>(response).kind)
    }

    @Test
    fun `проверка доступа запрашивает список моделей теми же заголовками`() = runBlocking {
        val engine = jsonEngine("""{"data":[{"id":"test-model"}]}""")
        val client = AnthropicClient(provider, model(), "секрет", HttpClient(engine))

        val failure = client.checkAccess()

        assertNull(failure, "ключ принят — отказа нет")
        val sent = engine.requestHistory.single()
        assertEquals(HttpMethod.Get, sent.method)
        assertEquals("https://api.example.test/v1/models", sent.url.toString())
        assertEquals("секрет", sent.headers["x-api-key"])
        assertEquals(ANTHROPIC_VERSION, sent.headers["anthropic-version"])
    }

    @Test
    fun `проверка доступа на неверный ключ даёт unauthorized`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.Unauthorized, "bad key") }
        val client = AnthropicClient(provider, model(), "плохой", HttpClient(engine))

        assertEquals(ModelCheckFailure.Unauthorized, client.checkAccess())
    }

    @Test
    fun `проверка доступа на лимит даёт rateLimited`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.TooManyRequests, "slow") }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        assertEquals(ModelCheckFailure.RateLimited, client.checkAccess())
    }

    @Test
    fun `провайдер без списка моделей получает unsupported, а не ложный ответ о ключе`() = runBlocking {
        val engine = MockEngine { respondError(HttpStatusCode.NotFound, "no such endpoint") }
        val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

        assertEquals(ModelCheckFailure.Unsupported, client.checkAccess())
    }

    @Test
    fun `ошибка сервера при проверке доступа даёт requestFailed`() {
        runBlocking {
            val engine = MockEngine { respondError(HttpStatusCode.InternalServerError, "boom") }
            val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

            assertIs<ModelCheckFailure.RequestFailed>(client.checkAccess())
        }
    }

    @Test
    fun `разбираемый, но неожиданный ответ проверки даёт responseUnreadable`() {
        runBlocking {
            val engine = MockEngine {
                respond("не json", HttpStatusCode.OK, headersOf(HttpHeaders.ContentType, "text/plain"))
            }
            val client = AnthropicClient(provider, model(), "ключ", HttpClient(engine))

            assertIs<ModelCheckFailure.ResponseUnreadable>(client.checkAccess())
        }
    }

    @Test
    fun `заготовка Claude из каталога проходит на записанном ответе сервера`() = runBlocking {
        // Критерий задачи: заготовка Claude обязана пройти на записанном ответе, а не
        // только профиль, собранный тестом. Поэтому адрес, идентификатор модели и ставки
        // берутся из каталога, а не из литералов теста.
        val entry = ProviderCatalog.entries.single { it.provider.id == "anthropic" }
        val claude = entry.models.first()
        val inputRate = assertNotNull(claude.pricePerMillionInMicros, "у заготовки Claude нет ставки ввода")
        val outputRate = assertNotNull(claude.pricePerMillionOutMicros, "у заготовки Claude нет ставки вывода")
        val engine = jsonEngine(message(inputTokens = 1_000_000, outputTokens = 1_000_000))
        val client = AnthropicClient(entry.provider, claude, "ключ", HttpClient(engine))

        val text = assertIs<LlmResponse.Text>(client.complete(ask))

        assertEquals("готово", text.text)
        assertEquals(Cost(amountMicros = inputRate + outputRate, known = true), text.cost)
        val sent = engine.requestHistory.single()
        assertEquals("${entry.provider.baseUrl}/messages", sent.url.toString())
        assertTrue(
            sent.body.toByteArray().decodeToString().contains(""""model":"${claude.model}""""),
            "идентификатор модели из заготовки обязан уехать на сервер",
        )
    }

    @Test
    fun `базовый адрес с завершающим слэшем не удваивает разделитель`() = runBlocking {
        val engine = jsonEngine(message())
        val trailing = provider.copy(baseUrl = "https://api.example.test/v1/")
        val client = AnthropicClient(trailing, model(), "ключ", HttpClient(engine))

        client.complete(ask)

        assertEquals("https://api.example.test/v1/messages", engine.requestHistory.single().url.toString())
    }
}
