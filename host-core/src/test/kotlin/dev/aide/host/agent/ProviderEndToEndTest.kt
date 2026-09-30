package dev.aide.host.agent

import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostCallException
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.ModelConfigError
import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.code
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.host.EmbeddedHost
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.git.GitCliFixture
import dev.aide.host.server.freeLoopbackPort
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.RequestId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.server.engine.EmbeddedServer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.request.receiveText
import io.ktor.server.response.respondText
import io.ktor.server.routing.get
import io.ktor.server.routing.post
import io.ktor.server.routing.routing
import java.io.ByteArrayOutputStream
import java.io.PrintStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.56 сквозным путём: настоящий адаптер провайдера против локального сервера-заглушки.
 *
 * Здесь проверяется то, чего не может проверить `MockEngine`: что конфигурация хоста,
 * реестр провайдеров, адаптер, планировщик и движок прогона действительно срастаются,
 * а ключ приходит из переменной окружения процесса. Внешней сети нет: заглушка живёт
 * на loopback и говорит на chat completions (О-11). Настоящий провайдер в CI не
 * вызывается и вызываться не будет — его проверяет владелец вручную.
 */
class ProviderEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()
    private val stub = StubProvider()

    /** Репозиторий с коммитом: прогон начинается в ветке задачи (T-1.18). */
    private val repo = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        stub.stop()
        scope.cancel()
        repo.close()
    }

    @Test
    fun `настроенный провайдер доводит прогон до завершения, и прогон помнит модель`() {
        runBlocking {
            stub.start()
            val host = hostWithDefaultConfig(stub.baseUrl, KEY_VARIABLE)
            try {
                val client = connect(host, "e2e")

                val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val finished = awaitRun(client) { it.state == RunState.FINISHED && it.taskId == taskId }
                assertNotNull(finished, "прогон обязан дойти до завершения: ${client.session.value.runs}")
                assertEquals(ALIAS, finished.modelAlias, "прогон обязан записать, на какой модели он шёл")
                assertEquals(2, stub.completions(), "план и шаг — два вызова модели, а не один")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `ключ уходит провайдеру из переменной окружения`() {
        runBlocking {
            stub.start()
            val host = hostWithDefaultConfig(stub.baseUrl, KEY_VARIABLE)
            try {
                val client = connect(host, "key")

                assertTrue(client.models.check(ALIAS).getOrThrow().ok, "доступ обязан подтвердиться")

                val secret = System.getenv(KEY_VARIABLE)
                assertEquals("Bearer $secret", stub.authorization.get(), "ключ берётся из окружения хоста")
                assertEquals(1, stub.modelRequests(), "проверка обязана сходить за списком моделей")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `отсутствие переменной окружения даёт явную ошибку с именем переменной`() {
        runBlocking {
            stub.start()
            // Провайдер настроен, но переменной, которую он просит, в окружении нет.
            val host = hostWithDefaultConfig(stub.baseUrl, ABSENT_KEY_VARIABLE)
            try {
                val client = connect(host, "missing")

                client.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                // Прогона нет вовсе: модель отказывает до планирования, поэтому отказ виден
                // на задаче — ровно так же, как отказ планирования (решение 2).
                val failed = awaitTask(client) { it.status == TaskStatus.FAILED }
                assertNotNull(failed, "задача без ключа обязана упасть, а не молчать")
                assertEquals(ModelCheckFailure.MissingKey(ABSENT_KEY_VARIABLE).code, failed.failureReason)

                val check = client.models.check(ALIAS).getOrThrow()
                assertFalse(check.ok, "проверка обязана отказать")
                val failure = assertIs<ModelCheckFailure.MissingKey>(check.failure)
                assertEquals(ABSENT_KEY_VARIABLE, failure.variable, "имя переменной обязано дойти до клиента")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `выбранная по умолчанию модель переживает перезапуск хоста`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            writeConfig(configPathFor(database), stub.baseUrl, KEY_VARIABLE)
            val first = hostWithConfig(database)
            try {
                val client = connect(first, "before-restart")
                client.models.load().getOrThrow()
                val saved = storedConfig(client)
                assertEquals(ALIAS, saved.defaultModel, "исходная конфигурация выбирает модель")

                // Сохраняем через протокол: так проверяется весь путь «экран → хост → файл».
                val changed = saved.copy(defaultModel = SECOND_ALIAS)
                client.models.save(changed).getOrThrow()
            } finally {
                first.close()
            }

            // Тот же файл настроек: новый хост обязан поднять ту же модель.
            val second = hostWithConfig(database)
            try {
                val client = connect(second, "after-restart")
                client.models.load().getOrThrow()

                assertEquals(
                    SECOND_ALIAS,
                    storedConfig(client).defaultModel,
                    "выбранная модель обязана пережить перезапуск хоста",
                )
            } finally {
                second.close()
            }
        }
    }

    @Test
    fun `неполная конфигурация отвергается с типизированной причиной и не пишется в файл`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            val configPath = configPathFor(database)
            writeConfig(configPath, stub.baseUrl, KEY_VARIABLE)
            val host = hostWithConfig(database)
            try {
                val client = connect(host, "invalid")
                client.models.load().getOrThrow()
                val before = Files.readString(configPath, StandardCharsets.UTF_8)
                // Повтор алиаса: реестр брал бы первую модель, а вторая молча не работала бы.
                val broken = storedConfig(client).let { it.copy(models = it.models + it.models.first()) }

                val result = client.models.save(broken)

                assertIs<HostCallException>(result.exceptionOrNull())
                assertEquals(
                    ModelConfigError.Rejected(AgentConfigRejection.DuplicateModelAlias(ALIAS)),
                    client.session.value.modelError,
                    "причина обязана дойти до экрана",
                )
                assertEquals(before, Files.readString(configPath, StandardCharsets.UTF_8), "отвергнутое не пишется")
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `повтор сохранения с тем же идентификатором не меняет конфигурацию дважды`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            writeConfig(configPathFor(database), stub.baseUrl, KEY_VARIABLE)
            val host = hostWithConfig(database)
            try {
                val connection = openConnection(host)
                val first = HostClient(connection, scope, requestIdPrefix = "idempotent").also { it.start() }
                first.models.load().getOrThrow()
                val saved = storedConfig(first)

                val request = ClientMessage.SaveAgentConfig(RequestId("save-dup"), saved)
                val answer = connection.request(request)
                assertIs<HostMessage.AgentConfigSaved>(answer)

                // Другой клиент меняет настройку — и повтор первого запроса не должен
                // вернуть прежнюю конфигурацию в файл.
                val second = HostClient(connection, scope, requestIdPrefix = "other").also { it.start() }
                second.models.save(saved.copy(defaultModel = SECOND_ALIAS)).getOrThrow()

                assertEquals(answer, connection.request(request), "повтор обязан вернуть прежний ответ")
                second.models.load().getOrThrow()
                assertEquals(
                    SECOND_ALIAS,
                    storedConfig(second).defaultModel,
                    "повтор не выполняет сохранение второй раз",
                )
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `ключ не попадает ни в файл настроек, ни в журнал хоста`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            val configPath = configPathFor(database)
            // Журнал хоста пишет slf4j-simple в stderr: подменяем поток, чтобы проверить
            // обещание «ключ нигде не сохраняется», а не только «его нет в файле».
            val originalError = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
            try {
                writeConfig(configPath, stub.baseUrl, KEY_VARIABLE)
                val host = hostWithConfig(database)
                try {
                    val client = connect(host, "no-leak")
                    client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                    assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })
                    client.models.check(ALIAS).getOrThrow()
                } finally {
                    host.close()
                }
            } finally {
                System.setErr(originalError)
            }

            val secret = System.getenv(KEY_VARIABLE)
            assertTrue(!secret.isNullOrBlank(), "переменная $KEY_VARIABLE обязана быть задана тестовой задачей")
            val file = Files.readString(configPath, StandardCharsets.UTF_8)
            val log = captured.toString(StandardCharsets.UTF_8.name())

            assertTrue(file.contains(KEY_VARIABLE), "в файле обязано быть имя переменной: $file")
            assertFalse(file.contains(secret), "ключа в файле настроек быть не должно")
            assertTrue(log.isNotEmpty(), "журнал хоста обязан быть непустым, иначе проверка пуста")
            assertFalse(log.contains(secret), "ключа в журнале быть не должно")
        }
    }

    /** Конфигурация, прочитанная клиентом: снимок после `models.load()`. */
    private fun storedConfig(client: HostClient): AgentConfig =
        assertNotNull(client.session.value.agentConfig, "хост обязан отдать конфигурацию")

    /**
     * База и файл настроек в одном каталоге: хост кладёт настройки рядом с базой, и тест
     * пользуется тем же правилом, а не подсовывает путь сбоку.
     */
    private fun tempDatabase(): Path = Files.createTempDirectory("aide-e2e").resolve("host.db")

    private fun configPathFor(database: Path): Path = AgentConfigStore.defaultPath(database)

    /** Готовит файл настроек: хост читает его при старте и больше не переписывает. */
    private fun writeConfig(path: Path, baseUrl: String, keyVariable: String) {
        AgentConfigStore(path).save(stubConfig(baseUrl, keyVariable))
    }

    private fun hostWithConfig(database: Path): EmbeddedHost = EmbeddedHost.open(databasePath = database)

    /** Хост с готовыми настройками: каталог каждый раз свой, чтобы тесты его не делили. */
    private fun hostWithDefaultConfig(baseUrl: String, keyVariable: String): EmbeddedHost {
        val database = tempDatabase()
        writeConfig(configPathFor(database), baseUrl, keyVariable)
        return hostWithConfig(database)
    }

    private fun stubConfig(baseUrl: String, keyVariable: String): AgentConfig = AgentConfig(
        defaultModel = ALIAS,
        providers = listOf(
            ProviderProfile(
                id = "stub",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = baseUrl,
                apiKeyEnv = keyVariable,
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = ALIAS,
                provider = "stub",
                model = MODEL_ID,
                displayName = "Заглушка",
                contextWindow = 32_000,
                maxOutputTokens = 512,
                toolUse = true,
                pricePerMillionInMicros = 1_000_000,
                pricePerMillionOutMicros = 2_000_000,
            ),
            ModelProfile(
                alias = SECOND_ALIAS,
                provider = "stub",
                model = MODEL_ID,
                displayName = "Заглушка 2",
                contextWindow = 32_000,
                maxOutputTokens = 512,
                toolUse = true,
                pricePerMillionInMicros = 1_000_000,
                pricePerMillionOutMicros = 2_000_000,
            ),
        ),
    )

    /** Открывает соединение и дожидается приветствия; клиент поверх него — по желанию теста. */
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

    private suspend fun connect(host: EmbeddedHost, prefix: String): HostClient {
        val connection = openConnection(host)
        val client = HostClient(connection, scope, requestIdPrefix = prefix).also { it.start() }
        // Репозиторий открывается до задачи: прогон начинается в ветке задачи (T-1.18),
        // а ставить её некуда, пока воркспейс не открыт.
        assertNotNull(client.openWorkspace(repo.root.toString()), "воркспейс обязан открыться")
        return client
    }

    private suspend fun awaitTask(client: HostClient, predicate: (Task) -> Boolean) =
        withTimeoutOrNull(15_000) {
            while (!client.session.value.tasks.any(predicate)) delay(20)
            client.session.value.tasks.first(predicate)
        }

    private suspend fun awaitRun(client: HostClient, predicate: (AgentRun) -> Boolean) =
        withTimeoutOrNull(15_000) {
            while (!client.session.value.runs.any(predicate)) delay(20)
            client.session.value.runs.first(predicate)
        }

    /**
     * Сервер-заглушка на loopback: список моделей и chat completions, без всякой сети.
     *
     * Первый вызов модели отвечает планом, дальше — «шаг выполнен»: так сквозной прогон
     * проходит и планирование, и шаг, не подменяя планировщик.
     */
    private class StubProvider {

        private val port = freeLoopbackPort()
        private val completions = AtomicInteger(0)
        private val modelRequests = AtomicInteger(0)
        private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)

        val authorization = AtomicReference<String?>(null)

        val baseUrl: String get() = "http://127.0.0.1:$port/v1"

        fun start() {
            val engine = embeddedServer(Netty, port = port) {
                routing {
                    get("/v1/models") {
                        authorization.set(call.request.headers[HttpHeaders.Authorization])
                        modelRequests.incrementAndGet()
                        call.respondText("""{"data":[{"id":"$MODEL_ID"}]}""", ContentType.Application.Json)
                    }
                    post("/v1/chat/completions") {
                        authorization.set(call.request.headers[HttpHeaders.Authorization])
                        call.receiveText()
                        val text = if (completions.incrementAndGet() == 1) PLAN else ANSWER
                        call.respondText(completion(text), ContentType.Application.Json)
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

        private fun completion(text: String): String = """
            {
              "choices": [{"index": 0, "message": {"role": "assistant", "content": "${text.replace("\"", "\\\"")}"}}],
              "usage": {"prompt_tokens": 100, "completion_tokens": 200}
            }
        """.trimIndent()
    }

    private companion object {
        /** Идентификатор модели у заглушки. */
        const val MODEL_ID = "stub-model"
        /** Алиас модели по умолчанию. */
        const val ALIAS = "stub/model"

        /** Второй алиас: им проверяется переживание выбора перезапуском. */
        const val SECOND_ALIAS = "stub/second"

        /** Переменная окружения с ключом; её значение задаёт тестовая задача `:host-core:test`. */
        const val KEY_VARIABLE = "AIDE_TEST_MODEL_KEY"

        /** Переменная, которой в окружении заведомо нет. */
        const val ABSENT_KEY_VARIABLE = "AIDE_TEST_ABSENT_MODEL_KEY"

        /** Ответ заглушки на планирование. */
        const val PLAN = """{"steps":[{"summary":"Прочитать логи"}]}"""

        /** Ответ заглушки на шаг. */
        const val ANSWER = "шаг выполнен"
    }
}
