package dev.aide.client.state

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.SecretStoreUnavailableReason
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking

/**
 * T-1.56: доступ клиента к настройкам моделей.
 *
 * Проверяется то, что клиент делает поверх соединения: куда кладёт ответ, что показывает
 * после сохранения и как доносит типизированный отказ. Сама конфигурация живёт на хосте,
 * поэтому «переживает перезапуск» проверяется там, а не здесь.
 */
class ModelConfigClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = FakeHostConnection()
    private lateinit var client: HostClient

    @BeforeTest
    fun setUp() {
        client = HostClient(connection, scope, requestIdPrefix = "models")
        client.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `конфигурация и каталог попадают в сессию`() {
        runBlocking {
            connection.respond = { message ->
                if (message is ClientMessage.AgentConfigRequest) {
                    HostMessage.AgentConfigSnapshot(message.requestId, config(), catalog())
                } else {
                    error("неожиданный запрос: $message")
                }
            }

            client.models.load().getOrThrow()

            assertEquals(CONFIG_ALIAS, client.session.value.agentConfig?.defaultModel)
            assertEquals(listOf("stub"), client.session.value.providerCatalog.map { it.provider.id })
        }
    }

    @Test
    fun `в сессии остаётся то, что вернул хост, а не отправленная копия`() {
        // Хост — хозяин конфигурации: если он нормализует ответ, экран обязан показать
        // его вариант, а не свою оптимистичную копию.
        runBlocking {
            connection.respond = { message ->
                val sent = assertIs<ClientMessage.SaveAgentConfig>(message)
                HostMessage.AgentConfigSaved(sent.requestId, sent.config.copy(defaultModel = "host/chosen"))
            }

            client.models.save(config()).getOrThrow()

            assertEquals("host/chosen", client.session.value.agentConfig?.defaultModel)
        }
    }

    @Test
    fun `проверка модели запоминает отказ с именем переменной`() {
        runBlocking {
            connection.respond = { message ->
                val check = assertIs<ClientMessage.CheckModel>(message)
                HostMessage.ModelCheckResult(
                    requestId = check.requestId,
                    ok = false,
                    failure = ModelCheckFailure.MissingKey("STUB_KEY"),
                )
            }

            val outcome = client.models.check(CONFIG_ALIAS).getOrThrow()

            assertFalse(outcome.ok)
            assertEquals(ModelCheckFailure.MissingKey("STUB_KEY"), outcome.failure)
            assertEquals(outcome, client.session.value.modelCheck, "результат обязан дойти до экрана")
        }
    }

    @Test
    fun `успешная проверка не несёт отказа`() {
        runBlocking {
            connection.respond = { message ->
                val check = assertIs<ClientMessage.CheckModel>(message)
                HostMessage.ModelCheckResult(check.requestId, ok = true)
            }

            val outcome = client.models.check(CONFIG_ALIAS).getOrThrow()

            assertTrue(outcome.ok)
            assertNull(outcome.failure)
        }
    }

    @Test
    fun `отказ хоста на сохранении возвращается вызывающему`() {
        runBlocking {
            connection.respond = { message ->
                val requestId = (message as ClientMessage.SaveAgentConfig).requestId
                HostMessage.Failure(requestId, ProtocolError.Internal("не записано"))
            }

            val result = client.models.save(config())

            assertIs<HostCallException>(result.exceptionOrNull())
            assertNull(client.session.value.agentConfig, "неудачное сохранение не подменяет конфигурацию")
        }
    }

    @Test
    fun `отказ конфигурации доходит до сессии с типизированной причиной`() {
        // Иначе «сохранить» выглядело бы сработавшим: черновик остаётся на экране,
        // а настройка на хосте — прежней.
        runBlocking {
            connection.respond = { message ->
                val save = assertIs<ClientMessage.SaveAgentConfig>(message)
                HostMessage.Failure(
                    save.requestId,
                    ProtocolError.InvalidAgentConfig(AgentConfigRejection.DuplicateModelAlias("stub/model")),
                )
            }

            val result = client.models.save(config())

            assertIs<HostCallException>(result.exceptionOrNull())
            assertEquals(
                ModelConfigError.Rejected(AgentConfigRejection.DuplicateModelAlias("stub/model")),
                client.session.value.modelError,
            )
            assertNull(client.session.value.agentConfig, "отвергнутая конфигурация не подменяет показанную")
        }
    }

    @Test
    fun `отказ связи помечается как недоступность хоста`() {
        runBlocking {
            connection.respond = { message ->
                val save = assertIs<ClientMessage.SaveAgentConfig>(message)
                HostMessage.Failure(save.requestId, ProtocolError.Internal("нет связи"))
            }

            client.models.save(config())

            assertEquals(ModelConfigError.Unreachable, client.session.value.modelError)
        }
    }

    @Test
    fun `успешное сохранение сбрасывает прошлый отказ`() {
        runBlocking {
            var refuse = true
            connection.respond = { message ->
                val save = assertIs<ClientMessage.SaveAgentConfig>(message)
                if (refuse) {
                    HostMessage.Failure(
                        save.requestId,
                        ProtocolError.InvalidAgentConfig(AgentConfigRejection.NonPositiveContext("stub/model")),
                    )
                } else {
                    HostMessage.AgentConfigSaved(save.requestId, save.config)
                }
            }

            client.models.save(config())
            assertNotNull(client.session.value.modelError, "отказ обязан быть виден")

            refuse = false
            client.models.save(config()).getOrThrow()

            assertNull(client.session.value.modelError, "успех обязан снять прошлый отказ")
        }
    }

    @Test
    fun `сохранение уезжает целиком, со всеми полями профиля`() {
        // Хост записывает в файл ровно то, что пришло: если клиент потеряет поле (например,
        // ставки или имя переменной окружения), настройка молча изменится.
        runBlocking {
            var seen: AgentConfig? = null
            connection.respond = { message ->
                val sent = assertIs<ClientMessage.SaveAgentConfig>(message)
                seen = sent.config
                HostMessage.AgentConfigSaved(sent.requestId, sent.config)
            }

            val withPrices = config()
            client.models.save(withPrices).getOrThrow()

            assertEquals(withPrices, seen)
        }
    }

    private fun config(): AgentConfig = AgentConfig(
        defaultModel = CONFIG_ALIAS,
        providers = listOf(
            ProviderProfile("stub", ProviderType.OPENAI_COMPATIBLE, "http://127.0.0.1:9/v1", "STUB_KEY"),
        ),
        models = listOf(
            ModelProfile(CONFIG_ALIAS, "stub", "stub-model", "Заглушка", 8_192, 512, true),
        ),
    )

    @Test
    fun `сохранение ключа относит состояние к провайдеру`() {
        runBlocking {
            connection.respond = { message ->
                val set = assertIs<ClientMessage.SetModelSecret>(message)
                HostMessage.ModelSecretChanged(set.requestId, set.providerId, ModelSecretStatus.InStore)
            }

            val result = client.models.setSecret("stub", "живой-ключ")

            assertTrue(result.isSuccess)
            assertEquals(
                ModelSecretStatus.InStore,
                client.session.value.modelSecrets["stub"],
                "состояние обязано дойти до экрана",
            )
            assertNull(client.session.value.modelError, "успешная запись не оставляет отказа")
        }
    }

    @Test
    fun `значение ключа уходит на хост, но в сессии не сохраняется`() {
        // Обратного пути нет: клиент отдаёт значение и больше его не видит — в сессии
        // остаётся только код состояния (T-1.58).
        runBlocking {
            var sent: ClientMessage.SetModelSecret? = null
            connection.respond = { message ->
                val set = assertIs<ClientMessage.SetModelSecret>(message)
                sent = set
                HostMessage.ModelSecretChanged(set.requestId, set.providerId, ModelSecretStatus.InStore)
            }

            client.models.setSecret("stub", "живой-ключ").getOrThrow()

            assertEquals("живой-ключ", assertNotNull(sent).value, "значение обязано уйти на хост")
            assertFalse(
                client.session.value.toString().contains("живой-ключ"),
                "сессия не должна хранить значение ключа",
            )
        }
    }

    @Test
    fun `удаление ключа показывает состояние «не задан»`() {
        runBlocking {
            connection.respond = { message ->
                val delete = assertIs<ClientMessage.DeleteModelSecret>(message)
                HostMessage.ModelSecretChanged(delete.requestId, delete.providerId, ModelSecretStatus.Absent)
            }

            client.models.deleteSecret("stub").getOrThrow()

            assertEquals(ModelSecretStatus.Absent, client.session.value.modelSecrets["stub"])
        }
    }

    @Test
    fun `недоступное хранилище — состояние, а не ошибка вызова`() {
        // Хост ответил, сохранить не смог: пользователю нужно увидеть причину на экране,
        // а не «запрос не выполнен» — иначе непонятно, что делать.
        runBlocking {
            connection.respond = { message ->
                val set = assertIs<ClientMessage.SetModelSecret>(message)
                HostMessage.ModelSecretChanged(
                    set.requestId,
                    set.providerId,
                    ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
                )
            }

            client.models.setSecret("stub", "ключ").getOrThrow()

            assertEquals(
                ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
                client.session.value.modelSecrets["stub"],
            )
        }
    }

    @Test
    fun `снимок состояний ключей попадает в сессию целиком`() {
        runBlocking {
            connection.respond = { message ->
                val snapshot = assertIs<ClientMessage.ModelSecrets>(message)
                HostMessage.ModelSecretsSnapshot(
                    snapshot.requestId,
                    mapOf("stub" to ModelSecretStatus.FromEnv, "local" to ModelSecretStatus.Absent),
                )
            }

            client.models.loadSecrets().getOrThrow()

            assertEquals(
                mapOf("stub" to ModelSecretStatus.FromEnv, "local" to ModelSecretStatus.Absent),
                client.session.value.modelSecrets,
            )
        }
    }

    @Test
    fun `отказ записи ключа доходит до сессии типизированной причиной`() {
        runBlocking {
            connection.respond = { message ->
                val set = assertIs<ClientMessage.SetModelSecret>(message)
                HostMessage.Failure(
                    set.requestId,
                    ProtocolError.InvalidModelSecret(ModelSecretRejection.EmptyValue),
                )
            }

            val result = client.models.setSecret("stub", "   ")

            assertIs<HostCallException>(result.exceptionOrNull())
            assertEquals(
                ModelConfigError.SecretRejected(ModelSecretRejection.EmptyValue),
                client.session.value.modelError,
            )
        }
    }

    private fun catalog(): List<ProviderCatalogEntry> {
        val config = config()
        return listOf(ProviderCatalogEntry(config.providers.single(), config.models))
    }

    private companion object {
        const val CONFIG_ALIAS = "stub/model"
    }
}
