package dev.aide.agent.provider

import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.domain.AgentConfig
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import io.ktor.client.HttpClient
import io.ktor.client.engine.mock.MockEngine
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.56: выбор модели по конфигурации, ключ из переменной окружения, проверка доступа.
 *
 * Конфигурация задаётся значением, окружение — функцией, фабрика клиентов — фейком:
 * ни файла, ни сети в этих проверках нет (О-11). Настоящий адаптер проверяется отдельно
 * на `MockEngine`, а сквозной путь — в `host-core` на локальной заглушке.
 */
class ProviderRegistryTest {

    private val clients = RecordingClients()

    private fun config(
        defaultModel: String? = "local/llama",
        apiKeyEnv: String? = "TEST_KEY",
        baseUrl: String = "http://127.0.0.1:9/v1",
    ): AgentConfig = AgentConfig(
        defaultModel = defaultModel,
        providers = listOf(
            ProviderProfile(
                id = "local",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = baseUrl,
                apiKeyEnv = apiKeyEnv,
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = "local/llama",
                provider = "local",
                model = "llama3.1",
                displayName = "Llama",
                contextWindow = 8_192,
                maxOutputTokens = 2_048,
                toolUse = true,
            ),
        ),
    )

    private fun registry(
        source: () -> AgentConfig = { config() },
        env: (String) -> String? = { name -> if (name == "TEST_KEY") "секрет-в-окружении" else null },
    ): ProviderRegistry = ProviderRegistry(config = source, env = env, clients = clients)

    @Test
    fun `выбранная по умолчанию модель отдаётся провайдером`() {
        val model = registry().current().getOrThrow()

        assertEquals("local/llama", model.alias)
        assertEquals(clients.client, model.client)
    }

    @Test
    fun `модель по умолчанию переживает перезапуск`() {
        // «Перезапуск» для реестра — это новое чтение той же конфигурации: состояние
        // он не держит, поэтому сохранённое значение читается тем же путём (решение 2).
        val saved = config(defaultModel = "local/llama")

        val afterRestart = ProviderRegistry(config = { saved }, env = { "секрет" }, clients = clients)

        assertEquals("local/llama", afterRestart.current().getOrThrow().alias)
    }

    @Test
    fun `отсутствие переменной окружения даёт отказ с именем переменной`() {
        val failure = registry(env = { null }).current().exceptionOrNull()

        val unavailable = assertIs<ModelUnavailableException>(failure)
        assertEquals(ModelCheckFailure.MissingKey("TEST_KEY"), unavailable.failure)
    }

    @Test
    fun `пустая переменная окружения считается отсутствующей`() {
        // Пустое значение — та же беда, что и незаданная переменная, и лечить её
        // пользователь будет так же: задать значение.
        val failure = registry(env = { "   " }).current().exceptionOrNull()

        assertEquals(
            ModelCheckFailure.MissingKey("TEST_KEY"),
            assertIs<ModelUnavailableException>(failure).failure,
        )
    }

    @Test
    fun `пустое имя переменной означает, что ключ не нужен`() {
        // Локальным провайдерам ключ не нужен (решение 6): клиент создаётся без ключа,
        // а не с пустой строкой, которую провайдер принял бы за неверный ключ.
        registry(source = { config(apiKeyEnv = null) }).current().getOrThrow()

        assertNull(clients.created.single().third, "локальному провайдеру ключ не передаётся")
    }

    @Test
    fun `ключ берётся из окружения и передаётся клиенту`() {
        registry().current().getOrThrow()

        assertEquals("секрет-в-окружении", clients.created.single().third)
    }

    @Test
    fun `конфигурация без модели по умолчанию даёт notConfigured`() {
        val failure = registry(source = { AgentConfig() }).current().exceptionOrNull()

        assertEquals(
            ModelCheckFailure.NotConfigured,
            assertIs<ModelUnavailableException>(failure).failure,
        )
    }

    @Test
    fun `неизвестный алиас по умолчанию даёт типизированный отказ`() {
        val failure = registry(source = { config(defaultModel = "нет/такой") }).current().exceptionOrNull()

        assertEquals(
            ModelCheckFailure.UnknownModel("нет/такой"),
            assertIs<ModelUnavailableException>(failure).failure,
        )
    }

    @Test
    fun `модель без провайдера даёт типизированный отказ`() {
        val broken = config().let { it.copy(providers = emptyList()) }
        val failure = registry(source = { broken }).current().exceptionOrNull()

        assertIs<ModelUnavailableException>(failure)
    }

    @Test
    fun `нечитаемая конфигурация даёт notConfigured, а не падение`() {
        val failure = registry(source = { error("файл недоступен") }).current().exceptionOrNull()

        assertEquals(
            ModelCheckFailure.NotConfigured,
            assertIs<ModelUnavailableException>(failure).failure,
        )
    }

    @Test
    fun `свой провайдер с произвольным адресом работает так же, как заготовка`() {
        val custom = config(baseUrl = "http://10.0.0.5:8000/v1")

        val model = registry(source = { custom }).current().getOrThrow()

        assertEquals("local/llama", model.alias)
        assertEquals("http://10.0.0.5:8000/v1", clients.created.single().first.baseUrl)
    }

    @Test
    fun `смена настройки видна следующему выбору модели`() {
        // Смена модели во время прогона не меняет правила идущего прогона (FR-AGENT-5),
        // но следующий прогон обязан взять новую: состояние не кэшируется.
        var current = config(defaultModel = "local/llama")
        val registry = registry(source = { current })

        assertEquals("local/llama", registry.current().getOrThrow().alias)

        current = current.copy(models = current.models + current.models.single().copy(alias = "local/second"))
        current = current.copy(defaultModel = "local/second")

        assertEquals("local/second", registry.current().getOrThrow().alias)
    }

    @Test
    fun `каждый объявленный протокол обслуживается адаптером`() {
        // T-1.57: отказ «адаптера ещё нет» убран для ANTHROPIC. Протокол объявлен
        // перечислением в домене, а адаптер живёт здесь, поэтому проверка идёт по всем
        // значениям перечисления сразу: новый протокол без адаптера обязан быть виден
        // здесь, а не превращаться в загадочный отказ на живом провайдере.
        val http = HttpClient(MockEngine { error("для сборки клиента запросов быть не должно") })
        val clients = ProviderClients(http)
        val provider = ProviderProfile("local", ProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:9/v1", null)
        val model = config().models.single()

        ProviderType.entries.forEach { type ->
            val created = clients.create(provider.copy(type = type), model, null)

            assertTrue(created.isSuccess, "у протокола $type обязан быть адаптер: ${created.exceptionOrNull()}")
        }
    }

    @Test
    fun `проверка модели возвращает отказ клиента`() {
        clients.client.accessFailure = ModelCheckFailure.Unauthorized

        val failure = kotlinx.coroutines.runBlocking { registry().check("local/llama") }

        assertEquals(ModelCheckFailure.Unauthorized, failure)
    }

    @Test
    fun `успешная проверка модели даёт null, и запрос списка выполнен`() {
        val failure = kotlinx.coroutines.runBlocking { registry().check("local/llama") }

        assertNull(failure, "успешная проверка не несёт отказа")
        assertEquals(1, clients.client.checkCount, "проверка обязана быть настоящей, а не подставной")
    }

    @Test
    fun `проверка неизвестной модели даёт отказ без обращения к провайдеру`() {
        val failure = kotlinx.coroutines.runBlocking { registry().check("нет/такой") }

        assertEquals(ModelCheckFailure.UnknownModel("нет/такой"), failure)
        assertEquals(0, clients.client.checkCount, "к сети обращаться не за чем")
    }

    @Test
    fun `проверка модели без переменной окружения даёт отказ с её именем`() {
        val failure = kotlinx.coroutines.runBlocking { registry(env = { null }).check("local/llama") }

        assertEquals(ModelCheckFailure.MissingKey("TEST_KEY"), failure)
        assertEquals(0, clients.client.checkCount, "проверять нечего: ключа нет")
    }

    @Test
    fun `отказ фабрики клиентов превращается в типизированный отказ`() {
        val failing = ProviderRegistry(
            config = { config() },
            env = { "секрет" },
            clients = { provider, _, _ -> Result.failure(UnsupportedProviderProtocolException(provider)) },
        )

        val failure = failing.current().exceptionOrNull()

        assertEquals(
            ModelCheckFailure.Unsupported,
            assertIs<ModelUnavailableException>(failure).failure,
        )
    }

    /** Фабрика, запоминающая аргументы и отдающая один и тот же клиент. */
    private class RecordingClients(val client: FakeProviderClient = FakeProviderClient()) : ProviderClientFactory {

        val created = mutableListOf<Triple<ProviderProfile, ModelProfile, String?>>()

        override fun create(
            provider: ProviderProfile,
            model: ModelProfile,
            apiKey: String?,
        ): Result<ProviderClient> {
            created += Triple(provider, model, apiKey)
            return Result.success(client)
        }
    }

    /** Клиент провайдера, отвечающий заданным отказом проверки. */
    private class FakeProviderClient : ProviderClient {

        var accessFailure: ModelCheckFailure? = null

        var checkCount: Int = 0
            private set

        override suspend fun checkAccess(): ModelCheckFailure? {
            checkCount += 1
            return accessFailure
        }

        override suspend fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }
}
