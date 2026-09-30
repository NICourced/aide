package dev.aide.agent.provider

import dev.aide.domain.AgentConfig
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import org.slf4j.LoggerFactory

/**
 * Выбор провайдера и модели по конфигурации хоста (T-1.56).
 *
 * Конфигурация берётся **по требованию**, а не хранится в поле: файл настроек правит
 * и хост, и человек за редактором, и второй экземпляр состояния в памяти разошёлся бы
 * с файлом молча. Цена решения — чтение небольшого JSON на старте прогона и на проверку
 * модели, то есть в темпе действий пользователя, а не шагов агента.
 *
 * @param config источник конфигурации; в хосте — файл рядом с базой, в тестах — значение.
 * @param env окружение хоста с ключами; второй источник после защищённого хранилища.
 * @param clients фабрика адаптеров по протоколу провайдера; обязательна, потому что
 *   адаптеров без транспорта не бывает, а умолчание «протокол не поддержан» прятало бы
 *   забытую подстановку.
 * @param secrets защищённое хранилище ключей (T-1.58); обязателен по той же причине, что
 *   и фабрика: умолчание «хранилища нет» в main-коде молча выключило бы первый источник
 *   приоритета, и ключ, сохранённый из приложения, перестал бы работать.
 */
class ProviderRegistry(
    private val config: () -> AgentConfig,
    private val env: (String) -> String? = System::getenv,
    private val clients: ProviderClientFactory,
    secrets: SecretStore,
) : AgentModels {

    private val logger = LoggerFactory.getLogger(ProviderRegistry::class.java)

    private val keys = ModelSecrets(store = secrets, env = env)

    /** Модель по умолчанию: алиас конфигурации, клиент — по её провайдеру и ключу. */
    override fun current(): Result<ConfiguredModel> =
        resolve(MODEL_FROM_CONFIG).map { ConfiguredModel(it.model.alias, it.client) }

    /**
     * Проверяет доступ к модели одним запросом списка моделей провайдера.
     *
     * @return null, если доступ подтверждён; иначе типизированный отказ.
     */
    override suspend fun check(alias: String): ModelCheckFailure? =
        resolve(alias).fold(
            onSuccess = { it.client.checkAccess() },
            onFailure = { failure ->
                if (failure is ModelUnavailableException) {
                    failure.failure
                } else {
                    logger.warn("Проверка модели $alias не удалась: ${failure.message}", failure)
                    ModelCheckFailure.RequestFailed(failure.message)
                }
            },
        )

    /**
     * Собирает модель и её клиент по алиасу.
     *
     * Отказ на каждом шаге типизирован: не выбрана модель, неизвестен алиас, нет
     * провайдера, нет переменной окружения с ключом, нет адаптера протокола. Все пять
     * состояний пользователь различает по коду и имени переменной (NFR-13).
     *
     * @param alias алиас из настроек; [MODEL_FROM_CONFIG] — «взять из конфигурации»:
     *   так вызывается выбор модели для прогона, у которого алиаса ещё нет.
     */
    private fun resolve(alias: String?): Result<Resolved> = runCatching {
        val snapshot = readConfig()
        val chosen = alias?.takeIf { it.isNotBlank() }
            ?: snapshot.defaultModel?.takeIf { it.isNotBlank() }
            ?: throw ModelUnavailableException(ModelCheckFailure.NotConfigured)
        val model = snapshot.models.firstOrNull { it.alias == chosen }
            ?: throw ModelUnavailableException(ModelCheckFailure.UnknownModel(chosen))
        val provider = snapshot.providers.firstOrNull { it.id == model.provider }
            ?: throw ModelUnavailableException(ModelCheckFailure.UnknownModel(chosen))
        val client = clients.create(provider, model, apiKey(provider)).getOrElse { error ->
            throw ModelUnavailableException(
                if (error is UnsupportedProviderProtocolException) {
                    ModelCheckFailure.Unsupported
                } else {
                    ModelCheckFailure.RequestFailed(error.message)
                },
            )
        }
        Resolved(model, client)
    }

    /**
     * Ключ провайдера по приоритету источников (T-1.58).
     *
     * Пустое имя переменной означает «ключ не нужен» — это законное состояние локального
     * провайдера (Ollama, LM Studio, vLLM). А вот отсутствие ключа у провайдера, который
     * его требует, — явная ошибка: сначала защищённое хранилище платформы, затем переменная
     * окружения, и только когда пусто в обоих — отказ с двумя именами (имя переменной и
     * идентификатор провайдера, под которым ключ можно задать из приложения). Иначе
     * пользователь видел бы «не авторизован» вместо «задайте DEEPSEEK_API_KEY».
     */
    private fun apiKey(provider: ProviderProfile): String? {
        val name = provider.apiKeyEnv?.takeIf { it.isNotBlank() } ?: return null
        return keys.resolve(provider)
            ?: throw ModelUnavailableException(ModelCheckFailure.MissingKey(name, provider.id))
    }

    /** Чтение не удалось — отказ, а не падение хоста: об этом и пишет журнал. */
    private fun readConfig(): AgentConfig = runCatching { config() }.getOrElse { error ->
        logger.warn("Конфигурация моделей не прочитана: ${error.message}", error)
        AgentConfig()
    }

    /** Модель и её клиент: то, что нужно и вызову, и проверке. */
    private class Resolved(val model: ModelProfile, val client: ProviderClient)

    private companion object {
        /**
         * «Алиас не задан»: у [check] он приходит из настроек всегда, а при выборе модели
         * для прогона его нет — там решает конфигурация. Отдельное имя вместо `null`,
         * чтобы вызов не читался как потерянный аргумент.
         */
        const val MODEL_FROM_CONFIG: String = ""
    }
}
