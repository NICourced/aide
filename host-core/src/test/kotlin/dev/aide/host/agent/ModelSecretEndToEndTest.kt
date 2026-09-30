package dev.aide.host.agent

import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.RunState
import dev.aide.domain.SecretStoreUnavailableReason
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.domain.code
import dev.aide.host.EmbeddedHost
import dev.aide.host.HostStorage
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.git.GitCliFixture
import dev.aide.host.secrets.InMemorySecretStore
import dev.aide.host.secrets.SecretStoreFactory
import dev.aide.host.server.freeLoopbackPort
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
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
import kotlinx.serialization.json.Json

/**
 * T-1.58 сквозным путём: ключ, заданный из приложения, и его последствия.
 *
 * Здесь проверяется то, чего не видно по отдельным классам: ключ действительно уходит
 * провайдеру, хранилище действительно идёт перед переменной окружения, удаление
 * действительно возвращает прогон к явной ошибке «ключ не задан», а значение не
 * появляется ни в ответах хоста, ни в файлах, ни в журнале.
 *
 * Хранилище подставлено в память: настоящее (libsecret) проверяется отдельно и только
 * там, где оно есть, — тест не должен падать на машине без keyring. Внешней сети нет:
 * заглушка провайдера живёт на loopback (О-11).
 */
class ModelSecretEndToEndTest {

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
    fun `ключ, сохранённый из приложения, уходит провайдеру и доводит прогон до конца`() {
        runBlocking {
            stub.start()
            val secrets = InMemorySecretStore()
            withHost(secrets, config()) { host, client ->
                assertTrue(client.models.setSecret(STUB_PROVIDER, STORE_KEY).isSuccess)
                assertEquals(ModelSecretStatus.InStore, client.secretStatus(STUB_PROVIDER))

                val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val finished = awaitRun(client) { it.state == RunState.FINISHED && it.taskId == taskId }
                assertNotNull(finished, "прогон обязан дойти до завершения: ${client.session.value.runs}")
                assertEquals("Bearer $STORE_KEY", stub.authorization.get(), "ключ обязан прийти из хранилища")
                assertEquals(STORE_KEY, secrets.stored[STUB_PROVIDER])
            }
        }
    }

    @Test
    fun `ключ из хранилища идёт перед переменной окружения`() {
        runBlocking {
            stub.start()
            val secrets = InMemorySecretStore()
            // У провайдера задана переменная окружения, и она есть в процессе теста:
            // побеждать обязана запись из хранилища.
            withHost(secrets, config(modelProvider = ENV_PROVIDER)) { _, client ->
                val fromEnv = System.getenv(PRESENT_KEY_VARIABLE)
                assertNotNull(fromEnv, "переменная $PRESENT_KEY_VARIABLE задана тестовой задачей")

                client.models.setSecret(ENV_PROVIDER, STORE_KEY).getOrThrow()
                client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })

                assertEquals("Bearer $STORE_KEY", stub.authorization.get(), "хранилище обязано победить окружение")
            }
        }
    }

    @Test
    fun `удаление ключа даёт явную ошибку «ключ не задан», а не отказ авторизации`() {
        runBlocking {
            stub.start()
            val secrets = InMemorySecretStore(mapOf(STUB_PROVIDER to STORE_KEY))
            withHost(secrets, config()) { _, client ->
                // Ключ удалён из приложения: следующий прогон обязан упасть до обращения
                // к провайдеру — то есть с «ключ не задан», а не с «провайдер отверг ключ».
                client.models.deleteSecret(STUB_PROVIDER).getOrThrow()
                assertEquals(ModelSecretStatus.Absent, client.secretStatus(STUB_PROVIDER))
                assertTrue(secrets.stored.isEmpty(), "удаление обязано убрать запись из хранилища")

                val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val failed = awaitTask(client) { it.id == taskId && it.status == TaskStatus.FAILED }
                assertNotNull(failed, "задача без ключа обязана упасть, а не молчать")
                assertEquals(ModelCheckFailure.MissingKey(ABSENT_KEY_VARIABLE).code, failed.failureReason)
                assertFalse(
                    failed.failureReason == ModelCheckFailure.Unauthorized.code,
                    "отказ авторизации показывал бы неверную причину: ${failed.failureReason}",
                )
                assertEquals(0, stub.completions(), "до провайдера дело дойти не должно")
            }
        }
    }

    @Test
    fun `ответы хоста не несут значения ключа`() {
        runBlocking {
            stub.start()
            withHost(InMemorySecretStore(), config()) { host, _ ->
                val connection = openConnection(host)
                val first = assertIs<HostMessage.ModelSecretChanged>(
                    connection.send(ClientMessage.SetModelSecret(RequestId("set-first"), STUB_PROVIDER, SECRET_A)),
                )
                val second = assertIs<HostMessage.ModelSecretChanged>(
                    connection.send(ClientMessage.SetModelSecret(RequestId("set-second"), STUB_PROVIDER, SECRET_B)),
                )

                assertEquals(ModelSecretStatus.InStore, first.status)
                // Ответ не зависит от значения: различаться может только идентификатор запроса.
                assertEquals(first.copy(requestId = second.requestId), second, "ответ не должен нести ключ")

                val json = Json { encodeDefaults = true }
                val probe = ClientMessage.SetModelSecret(RequestId("probe"), "stub", SECRET_A)
                assertTrue(
                    json.encodeToString(ClientMessage.serializer(), probe).contains(SECRET_A),
                    "проверка пуста: значение не находится даже в запросе",
                )
                listOf<HostMessage>(
                    first,
                    second,
                    connection.send(ClientMessage.ModelSecrets(RequestId("snapshot"))),
                ).forEach { response ->
                    val text = json.encodeToString(HostMessage.serializer(), response)
                    assertFalse(text.contains(SECRET_A), "ответ не должен нести значение: $text")
                    assertFalse(text.contains(SECRET_B), "ответ не должен нести значение: $text")
                }
            }
        }
    }

    @Test
    fun `секрет не появляется ни в файле настроек, ни в каталоге данных, ни в журнале`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            val directory = assertNotNull(database.parent)
            val originalError = System.err
            val captured = ByteArrayOutputStream()
            System.setErr(PrintStream(captured, true, StandardCharsets.UTF_8.name()))
            try {
                val secrets = InMemorySecretStore()
                writeConfig(configPathFor(database))
                val host = hostWith(database, secrets)
                try {
                    val client = connect(host, "no-leak")
                    client.models.setSecret(STUB_PROVIDER, LEAK_SECRET).getOrThrow()
                    client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                    assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })
                    assertEquals("Bearer $LEAK_SECRET", stub.authorization.get(), "секрет обязан дойти до провайдера")
                } finally {
                    host.close()
                }
            } finally {
                System.setErr(originalError)
            }

            val log = captured.toString(StandardCharsets.UTF_8.name())
            assertTrue(log.isNotEmpty(), "журнал хоста обязан быть непустым, иначе проверка пуста")
            assertFalse(log.contains(LEAK_SECRET), "секрета в журнале быть не должно")

            val offenders = Files.walk(directory).use { paths ->
                paths.filter { Files.isRegularFile(it) }
                    .filter { file -> String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(LEAK_SECRET) }
                    .toList()
            }
            assertTrue(
                offenders.isEmpty(),
                "секрет не должен появляться ни в одном файле состояния хоста: $offenders",
            )
        }
    }

    @Test
    fun `снимок состояний отдаёт состояние по каждому провайдеру`() {
        runBlocking {
            stub.start()
            val secrets = InMemorySecretStore()
            withHost(secrets, config()) { _, client ->
                client.models.setSecret(STUB_PROVIDER, STORE_KEY).getOrThrow()

                client.models.loadSecrets().getOrThrow()

                val statuses = client.session.value.modelSecrets
                assertEquals(ModelSecretStatus.InStore, statuses[STUB_PROVIDER])
                // Второй провайдер ключа не имеет ни в хранилище, ни в окружении.
                assertEquals(ModelSecretStatus.Absent, statuses[ABSENT_ENV_PROVIDER])
                assertEquals(
                    ModelSecretStatus.FromEnv,
                    statuses[ENV_PROVIDER],
                    "провайдер с заданной переменной окружения обязан быть виден как таковой",
                )
            }
        }
    }

    @Test
    fun `пустое значение и неизвестный провайдер отвергаются типизированно`() {
        runBlocking {
            stub.start()
            withHost(InMemorySecretStore(), config()) { host, _ ->
                val connection = openConnection(host)

                val empty = connection.send(ClientMessage.SetModelSecret(RequestId("empty"), STUB_PROVIDER, "   "))
                val unknown = connection.send(
                    ClientMessage.SetModelSecret(RequestId("unknown"), "нет-такого", STORE_KEY),
                )

                assertEquals(
                    ProtocolError.InvalidModelSecret(ModelSecretRejection.EmptyValue),
                    assertIs<HostMessage.Failure>(empty).error,
                )
                assertEquals(
                    ProtocolError.InvalidModelSecret(ModelSecretRejection.UnknownProvider("нет-такого")),
                    assertIs<HostMessage.Failure>(unknown).error,
                )
            }
        }
    }

    @Test
    fun `недоступное хранилище отказывает с причиной и не пишет файл`() {
        runBlocking {
            stub.start()
            val database = tempDatabase()
            val directory = assertNotNull(database.parent)
            writeConfig(configPathFor(database))
            val secrets = InMemorySecretStore.unavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS)
            val host = hostWith(database, secrets)
            try {
                val client = connect(host, "unavailable")

                client.models.setSecret(STUB_PROVIDER, STORE_KEY).getOrThrow()

                assertEquals(
                    ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
                    client.secretStatus(STUB_PROVIDER),
                    "отказ обязан дойти до приложения с причиной",
                )
                assertFalse(Files.exists(directory.resolve("model-secrets.bin")), "блоб не создаётся")

                val offenders = Files.walk(directory).use { paths ->
                    paths.filter { Files.isRegularFile(it) }
                        .filter { file -> String(Files.readAllBytes(file), StandardCharsets.UTF_8).contains(STORE_KEY) }
                        .toList()
                }
                assertTrue(offenders.isEmpty(), "ключ не должен улечься в файл вместо хранилища: $offenders")
            } finally {
                host.close()
            }
        }
    }

    /**
     * Настоящее хранилище платформы: ключ из приложения идёт в системный keyring.
     *
     * Здесь хост поднимается так, как в приложении: без подстановки, то есть хранилище
     * выбирает [dev.aide.host.secrets.SecretStoreFactory] по платформе. На машине с
     * рабочим keyring это libsecret, и ключ проходит настоящий путь; на машине без него
     * хост обязан отказать с причиной — и то и другое проверка, а не пропуск теста.
     *
     * Запись адресуется уникальным идентификатором провайдера: чужие записи тест не
     * трогает, а свою удаляет за собой в любом исходе.
     */
    @Test
    fun `настоящее хранилище платформы сохраняет ключ, а без него хост отказывает с причиной`() {
        runBlocking {
            stub.start()
            val providerId = "aide-e2e-${System.nanoTime()}"
            val real = SecretStoreFactory(secretBlobPath()).create()
            val database = tempDatabase()
            writeConfig(configPathFor(database), config(modelProvider = providerId, providerId = providerId))
            val host = hostWith(database, secrets = null)
            try {
                val client = connect(host, "real-secret")

                client.models.setSecret(providerId, REAL_KEY).getOrThrow()

                when (val availability = real.availability()) {
                    is SecretStoreAvailability.Available -> assertRealRoundTrip(client, providerId)
                    is SecretStoreAvailability.Unavailable -> {
                        assertEquals(
                            ModelSecretStatus.StoreUnavailable(availability.reason),
                            client.secretStatus(providerId),
                            "без хранилища хост обязан отказать с причиной",
                        )
                        assertFalse(
                            Files.exists(secretBlobPath()),
                            "отказ не должен превращаться в запись ключа рядом с базой",
                        )
                    }
                }
            } finally {
                if (real.availability() is SecretStoreAvailability.Available) {
                    runCatching { real.delete(providerId) }
                }
                host.close()
            }
        }
    }

    /** Настоящее хранилище доступно: ключ уходит провайдеру, а удаление возвращает отказ. */
    private suspend fun assertRealRoundTrip(client: HostClient, providerId: String) {
        assertEquals(ModelSecretStatus.InStore, client.secretStatus(providerId))

        client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
        assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })
        assertEquals("Bearer $REAL_KEY", stub.authorization.get(), "ключ обязан прийти из системного хранилища")

        client.models.deleteSecret(providerId).getOrThrow()
        assertEquals(ModelSecretStatus.Absent, client.secretStatus(providerId))

        val taskId = client.postTask("Ещё раз", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
        val failed = awaitTask(client) { it.id == taskId && it.status == TaskStatus.FAILED }
        assertNotNull(failed, "после удаления ключа прогон обязан упасть")
        assertEquals(ModelCheckFailure.MissingKey(ABSENT_KEY_VARIABLE).code, failed.failureReason)
    }

    /** Путь блоба DPAPI рядом с базой: тот же, что выбирает [dev.aide.host.HostStorage]. */
    private fun secretBlobPath(): Path = Files.createTempDirectory("aide-real-secret")
        .resolve(HostStorage.SECRETS_FILE_NAME)

    /** Отправляет запрос по соединению и требует ответа: молчание хоста — провал теста. */
    private suspend fun KtorHostConnection.send(message: ClientMessage): HostMessage =
        assertNotNull(request(message), "хост не ответил на ${message::class.simpleName}")

    /** Состояние ключа, каким его знает клиент: из последнего ответа хоста, без запроса. */
    private fun HostClient.secretStatus(providerId: String): ModelSecretStatus? =
        session.value.modelSecrets[providerId]

    /** Поднимает хост с заданным хранилищем, конфигурацией и клиентом; закрывает хост после блока. */
    private suspend fun withHost(
        secrets: SecretStore,
        config: AgentConfig,
        block: suspend (EmbeddedHost, HostClient) -> Unit,
    ) {
        val database = tempDatabase()
        writeConfig(configPathFor(database), config)
        val host = hostWith(database, secrets)
        try {
            block(host, connect(host, "secrets"))
        } finally {
            host.close()
        }
    }

    /**
     * Конфигурация: провайдеры с ключом из хранилища, из окружения и вовсе без ключа.
     *
     * @param modelProvider провайдер модели по умолчанию (для проверки приоритета берётся
     *   провайдер с переменной окружения).
     * @param providerId идентификатор первого провайдера; проверке настоящего хранилища
     *   нужен уникальный, чтобы не трогать записи пользователя в системном хранилище.
     */
    private fun config(modelProvider: String = STUB_PROVIDER, providerId: String = STUB_PROVIDER): AgentConfig =
        AgentConfig(
            defaultModel = ALIAS,
            providers = listOf(
                ProviderProfile(providerId, ProviderType.OPENAI_COMPATIBLE, stub.baseUrl, ABSENT_KEY_VARIABLE),
                ProviderProfile(ENV_PROVIDER, ProviderType.OPENAI_COMPATIBLE, stub.baseUrl, PRESENT_KEY_VARIABLE),
                ProviderProfile(ABSENT_ENV_PROVIDER, ProviderType.OPENAI_COMPATIBLE, stub.baseUrl, null),
            ),
            models = listOf(
                ModelProfile(ALIAS, modelProvider, MODEL_ID, "Заглушка", 32_000, 512, true, 1_000_000, 2_000_000),
            ),
        )

    private fun tempDatabase(): Path = Files.createTempDirectory("aide-secret-e2e").resolve("host.db")

    private fun configPathFor(database: Path): Path = AgentConfigStore.defaultPath(database)

    private fun writeConfig(path: Path, config: AgentConfig = config()) {
        AgentConfigStore(path).save(config)
    }

    private fun hostWith(database: Path, secrets: SecretStore?): EmbeddedHost =
        EmbeddedHost.open(databasePath = database, secrets = secrets)

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
     * Сервер-заглушка на loopback: список моделей и chat completions, без внешней сети.
     *
     * Первый вызов модели отвечает планом, дальше — «шаг выполнен»: сквозной прогон
     * проходит и планирование, и шаг, не подменяя планировщик.
     */
    private class StubProvider {

        private val port = freeLoopbackPort()
        private val completions = AtomicInteger(0)
        private val serverRef = AtomicReference<EmbeddedServer<*, *>?>(null)

        val authorization = AtomicReference<String?>(null)

        val baseUrl: String get() = "http://127.0.0.1:$port/v1"

        fun start() {
            val engine = embeddedServer(Netty, port = port) {
                routing {
                    get("/v1/models") {
                        authorization.set(call.request.headers[HttpHeaders.Authorization])
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

        /** Алиас выбранной модели. */
        const val ALIAS = "stub/model"

        /** Провайдер, ключ которого обязан прийти из хранилища. */
        const val STUB_PROVIDER = "stub"

        /** Провайдер с заданной переменной окружения. */
        const val ENV_PROVIDER = "env-provider"

        /** Провайдер без имени переменной: ключ ему не нужен. */
        const val ABSENT_ENV_PROVIDER = "local"

        /** Переменная окружения, заданная тестовой задачей `:host-core:test`. */
        const val PRESENT_KEY_VARIABLE = "AIDE_TEST_MODEL_KEY"

        /** Переменная, которой в окружении заведомо нет. */
        const val ABSENT_KEY_VARIABLE = "AIDE_TEST_ABSENT_MODEL_KEY"

        /** Ключи, которые тест кладёт в хранилище. */
        const val STORE_KEY = "sk-store-2f6a1c"

        /** Ключ, которым проверяется отсутствие утечки в файлы и журнал. */
        const val LEAK_SECRET = "sk-leak-9d41e7"

        /** Ключ, который кладётся в настоящее хранилище платформы. */
        const val REAL_KEY = "sk-real-5c30ba"

        /** Два разных ключа: ответ хоста не должен зависеть от значения. */
        const val SECRET_A = "sk-first-a1"
        const val SECRET_B = "sk-second-b2"

        /** Ответ заглушки на планирование. */
        const val PLAN = """{"steps":[{"summary":"Прочитать логи"}]}"""

        /** Ответ заглушки на шаг. */
        const val ANSWER = "шаг выполнен"
    }
}
