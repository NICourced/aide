package dev.aide.host.agent

import dev.aide.agent.provider.ProviderCatalog
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ProviderProfile
import dev.aide.domain.RunState
import dev.aide.host.EmbeddedHost
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.server.freeLoopbackPort
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.Headers
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.57 сквозным путём: адаптер Anthropic Messages против локального сервера-заглушки.
 *
 * Второй протокол проверяется тем же способом, что и первый (T-1.56): конфигурация хоста,
 * реестр провайдеров, адаптер, планировщик и движок обязаны срастаться целиком, а ключ
 * приходить из переменной окружения процесса. Провайдер и модель берутся из **заготовки
 * Claude** — меняется только адрес, который смотрит на заглушку, поэтому проверяется
 * именно та заготовка, которую получит пользователь.
 *
 * Внешней сети нет: заглушка живёт на loopback и говорит на Messages API (О-11).
 * Настоящий Claude в CI не вызывается и вызываться не будет.
 */
class AnthropicProviderEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()
    private val stub = AnthropicStub()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        stub.stop()
        scope.cancel()
    }

    @Test
    fun `провайдер на протоколе Anthropic доводит прогон до завершения`() {
        runBlocking {
            stub.start()
            val host = hostWithConfig(stub.baseUrl)
            try {
                val client = connect(host)

                val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val finished = awaitRun(client) { it.state == RunState.FINISHED && it.taskId == taskId }
                assertNotNull(finished, "прогон обязан дойти до завершения: ${client.session.value.runs}")
                assertEquals(alias(), finished.modelAlias, "прогон обязан записать модель заготовки")
                assertEquals(2, stub.completions(), "план и шаг — два вызова модели, а не один")
                // Ключ у этого протокола едет своим заголовком; `Authorization` сервис
                // не понимает, и «Bearer» здесь означал бы, что адаптер перепутан с первым.
                assertEquals("aide-test-key-2f6a1c", stub.apiKey.get(), "ключ берётся из окружения хоста")
                assertNull(stub.authorization.get(), "у Messages API ключ идёт не в Authorization")
                assertEquals(STUB_VERSION, stub.version.get(), "версия протокола обязательна")
                assertTrue(
                    stub.lastBody.get().contains(""""model":"${modelId()}""""),
                    "идентификатор модели обязан уехать на сервер: ${stub.lastBody.get()}",
                )
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `проверка модели на протоколе Anthropic подтверждает ключ`() {
        runBlocking {
            stub.start()
            val host = hostWithConfig(stub.baseUrl)
            try {
                val client = connect(host)

                val check = client.models.check(alias()).getOrThrow()

                assertTrue(check.ok, "доступ обязан подтвердиться: ${check.failure}")
                assertEquals(1, stub.modelRequests(), "проверка обязана сходить за списком моделей")
                assertEquals("aide-test-key-2f6a1c", stub.apiKey.get())
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `отсутствие переменной окружения даёт явную ошибку с именем переменной`() {
        runBlocking {
            stub.start()
            // Провайдер настроен, но переменной, которую он просит, в окружении нет:
            // отказ должен быть виден до всякого обращения к сети.
            val host = hostWithConfig(stub.baseUrl, keyVariable = ABSENT_KEY_VARIABLE)
            try {
                val client = connect(host)

                val check = client.models.check(alias()).getOrThrow()

                // Отказ называет оба имени — переменную окружения и провайдера (T-1.58):
                // ключ можно задать и переменной, и через приложение в защищённое хранилище.
                assertEquals(ModelCheckFailure.MissingKey(ABSENT_KEY_VARIABLE, "anthropic"), check.failure)
                assertEquals(0, stub.modelRequests(), "без ключа ходить к провайдеру не за чем")
            } finally {
                host.close()
            }
        }
    }

    /** Алиас и идентификатор модели заготовки: тест не переписывает их своими значениями. */
    private fun entry() = ProviderCatalog.entries.single { it.provider.id == "anthropic" }

    private fun alias(): String = entry().models.first().alias

    private fun modelId(): String = entry().models.first().model

    /**
     * Конфигурация из заготовки Claude: меняются адрес и имя переменной с ключом.
     *
     * Остальные поля — как в каталоге: если заготовка разойдётся с адаптером (например,
     * протоколом или пределом вывода), это будет видно здесь, а не в настройках пользователя.
     */
    private fun stubConfig(baseUrl: String, keyVariable: String): AgentConfig {
        val entry = entry()
        return AgentConfig(
            defaultModel = entry.models.first().alias,
            providers = listOf(
                ProviderProfile(
                    id = entry.provider.id,
                    type = entry.provider.type,
                    baseUrl = baseUrl,
                    apiKeyEnv = keyVariable,
                ),
            ),
            models = entry.models,
        )
    }

    private fun tempDatabase(): Path = Files.createTempDirectory("aide-anthropic-e2e").resolve("host.db")

    /** Хост с готовыми настройками: каталог каждый раз свой, чтобы тесты его не делили. */
    private fun hostWithConfig(baseUrl: String, keyVariable: String = KEY_VARIABLE): EmbeddedHost {
        val database = tempDatabase()
        AgentConfigStore(AgentConfigStore.defaultPath(database)).save(stubConfig(baseUrl, keyVariable))
        return EmbeddedHost.open(databasePath = database)
    }

    /** Открывает соединение и дожидается приветствия. */
    private suspend fun openConnection(host: EmbeddedHost): KtorHostConnection {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        connection.start()
        val state = withTimeoutOrNull(10_000) {
            while (connection.state.value !is ConnectionState.Connected) delay(20)
            connection.state.value
        }
        assertNotNull(state, "клиент не подключился: ${connection.state.value}")
        return connection
    }

    private suspend fun connect(host: EmbeddedHost): HostClient {
        val connection = openConnection(host)
        return HostClient(connection, scope, requestIdPrefix = "anthropic-e2e").also { it.start() }
    }

    private suspend fun awaitRun(client: HostClient, predicate: (AgentRun) -> Boolean) =
        withTimeoutOrNull(15_000) {
            while (!client.session.value.runs.any(predicate)) delay(20)
            client.session.value.runs.first(predicate)
        }

    /**
     * Сервер-заглушка на loopback: список моделей и Messages API, без всякой сети.
     *
     * Первый вызов модели отвечает планом, дальше — «шаг выполнен»: так сквозной прогон
     * проходит и планирование, и шаг, не подменяя планировщик. Заголовки запоминаются,
     * чтобы проверка ключа и версии протокола шла по тому, что адаптер действительно отправил.
     */
    private class AnthropicStub {

        private val port = freeLoopbackPort()
        private val completions = AtomicInteger(0)
        private val modelRequests = AtomicInteger(0)
        private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)

        val apiKey = AtomicReference<String?>(null)
        val version = AtomicReference<String?>(null)
        val authorization = AtomicReference<String?>(null)
        val lastBody = AtomicReference<String>("")

        val baseUrl: String get() = "http://127.0.0.1:$port/v1"

        fun start() {
            val engine = embeddedServer(Netty, port = port) {
                routing {
                    get("/v1/models") {
                        rememberHeaders(call.request.headers)
                        modelRequests.incrementAndGet()
                        call.respondText("""{"data":[{"id":"claude-stub"}]}""", ContentType.Application.Json)
                    }
                    post("/v1/messages") {
                        rememberHeaders(call.request.headers)
                        lastBody.set(call.receiveText())
                        val text = if (completions.incrementAndGet() == 1) PLAN else ANSWER
                        call.respondText(message(text), ContentType.Application.Json)
                    }
                }
            }.start(wait = false)
            serverRef.set(engine)
        }

        fun stop() {
            serverRef.getAndSet(null)?.stop(0, 500)
        }

        fun completions(): Int = completions.get()

        fun modelRequests(): Int = modelRequests.get()

        private fun rememberHeaders(headers: Headers) {
            apiKey.set(headers["x-api-key"])
            version.set(headers["anthropic-version"])
            authorization.set(headers[HttpHeaders.Authorization])
        }

        /** Записанный ответ сервера в формате Messages: один текстовый блок и токены. */
        private fun message(text: String): String = """
            {
              "id": "msg_stub",
              "type": "message",
              "role": "assistant",
              "content": [{"type": "text", "text": "${text.replace("\"", "\\\"")}"}],
              "stop_reason": "end_turn",
              "usage": {"input_tokens": 100, "output_tokens": 200}
            }
        """.trimIndent()
    }

    private companion object {

        /** Переменная окружения с ключом; её значение задаёт тестовая задача `:host-core:test`. */
        const val KEY_VARIABLE: String = "AIDE_TEST_MODEL_KEY"

        /** Переменная, которой в окружении заведомо нет. */
        const val ABSENT_KEY_VARIABLE: String = "AIDE_TEST_ABSENT_MODEL_KEY"

        /** Версия протокола, которую обязан прислать адаптер. */
        const val STUB_VERSION: String = "2023-06-01"

        /** Ответ заглушки на планирование. */
        const val PLAN: String = """{"steps":[{"summary":"Прочитать логи"}]}"""

        /** Ответ заглушки на шаг. */
        const val ANSWER: String = "шаг выполнен"
    }
}
