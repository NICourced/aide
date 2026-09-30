package dev.aide.client.state

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Отказ настроек моделей, который видит экран (T-1.56).
 *
 * Два случая, различимые для пользователя: хост не ответил (настройки не загружены или
 * не сохранены — это связь) и хост отверг конфигурацию по существу. Без второго «сохранить»
 * выглядело бы сработавшим, а настройка оставалась бы прежней.
 */
sealed interface ModelConfigError {

    /** Хост не ответил: связь оборвалась, запрос не прошёл или ответ не разобран. */
    data object Unreachable : ModelConfigError

    /** Хост отверг конфигурацию; причина типизирована и показывается словами (NFR-13). */
    data class Rejected(val rejection: AgentConfigRejection) : ModelConfigError

    /** Хост отверг запись ключа по существу: пустое значение или неизвестный провайдер (T-1.58). */
    data class SecretRejected(val rejection: ModelSecretRejection) : ModelConfigError
}

/** Результат проверки модели: доступ подтверждён, если [failure] = null (T-1.56). */
data class ModelCheckOutcome(
    /** Алиас, к которому относится результат. */
    val alias: String,
    /** Почему доступ не подтверждён; null — проверка прошла. */
    val failure: ModelCheckFailure?,
) {
    /** Доступ подтверждён. */
    val ok: Boolean get() = failure == null
}

/**
 * Настройки моделей хоста: чтение, запись и проверка доступа (T-1.56).
 *
 * Отдельный класс, а не методы [HostClient]: конфигурация моделей — самостоятельный
 * повод меняться, и общий у неё с доступом к хосту только транспорт. Смена модели
 * живёт на хосте (файл настроек рядом с базой), клиент её только показывает и правит,
 * поэтому локально ничего не хранится — и «переживает перезапуск» проверяется на хосте.
 */
class ModelConfigClient(
    private val connection: HostConnection,
    private val nextRequestId: suspend () -> RequestId,
    private val session: MutableStateFlow<HostSession>,
) {

    /** Запрашивает конфигурацию и каталог заготовок; результат сохраняется в [HostSession]. */
    suspend fun load(): Result<Unit> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.AgentConfigRequest(requestId))) {
            is HostMessage.AgentConfigSnapshot -> {
                session.update {
                    it.copy(
                        agentConfig = response.config,
                        providerCatalog = response.catalog,
                        modelError = null,
                    )
                }
                Result.success(Unit)
            }

            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на запрос настроек"))
        }
    }

    /**
     * Сохраняет конфигурацию целиком.
     *
     * В сессию пишется то, что вернул хост, а не отправленная копия: если хост нормализовал
     * или дополнил конфигурацию, экран обязан показать сохранённое, а не своё.
     */
    suspend fun save(config: AgentConfig): Result<AgentConfig> {
        val requestId = nextRequestId()
        val message = ClientMessage.SaveAgentConfig(requestId, config)
        return when (val response = connection.request(message)) {
            is HostMessage.AgentConfigSaved -> {
                session.update { it.copy(agentConfig = response.config, modelError = null, lastError = null) }
                Result.success(response.config)
            }

            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на сохранение настроек"))
        }
    }

    /**
     * Запоминает отказ в сессии и возвращает его вызывающему.
     *
     * И то и другое нужно: экран показывает состояние (в том числе после рекомпозиции,
     * когда локальная переменная потерялась бы), а вызывающий решает, что делать дальше.
     */
    private fun fail(error: ProtocolError): Result<Nothing> {
        session.update { it.copy(modelError = error.toModelConfigError()) }
        return Result.failure(HostCallException(error))
    }

    /** Проверяет доступ к модели; результат сохраняется в [HostSession]. */
    suspend fun check(alias: String): Result<ModelCheckOutcome> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.CheckModel(requestId, alias))) {
            is HostMessage.ModelCheckResult -> {
                val outcome = ModelCheckOutcome(alias, response.failure)
                session.update { it.copy(modelCheck = outcome, modelError = null) }
                Result.success(outcome)
            }

            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на проверку модели"))
        }
    }

    /**
     * Запрашивает состояние ключей всех провайдеров (T-1.58).
     *
     * Значений в ответе нет и быть не может: хост отдаёт только код состояния, и клиент
     * физически не может показать ключ, даже если бы захотел.
     */
    suspend fun loadSecrets(): Result<Unit> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.ModelSecrets(requestId))) {
            is HostMessage.ModelSecretsSnapshot -> {
                session.update { it.copy(modelSecrets = response.statuses, modelError = null) }
                Result.success(Unit)
            }

            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на запрос состояния ключей"))
        }
    }

    /**
     * Сохраняет ключ провайдера (T-1.58).
     *
     * Отказ хранилища — не ошибка вызова: хост ответил состоянием с причиной, и оно
     * попадает в сессию наравне с «ключ задан». Ошибкой считается лишь то, что хост не
     * ответил или отверг запись по существу.
     */
    suspend fun setSecret(providerId: String, value: String): Result<Unit> {
        val requestId = nextRequestId()
        val message = ClientMessage.SetModelSecret(requestId, providerId, value)
        return when (val response = connection.request(message)) {
            is HostMessage.ModelSecretChanged -> useStatus(providerId, response.status)
            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на сохранение ключа"))
        }
    }

    /** Удаляет ключ провайдера (T-1.58): следующий прогон падает на «ключ не задан». */
    suspend fun deleteSecret(providerId: String): Result<Unit> {
        val requestId = nextRequestId()
        return when (val response = connection.request(ClientMessage.DeleteModelSecret(requestId, providerId))) {
            is HostMessage.ModelSecretChanged -> useStatus(providerId, response.status)
            is HostMessage.Failure -> fail(response.error)
            else -> fail(ProtocolError.Internal("Хост не ответил на удаление ключа"))
        }
    }

    /** Записывает состояние ключа в сессию: и «задан», и «хранилище недоступно» — ответ хоста. */
    private fun useStatus(providerId: String, status: ModelSecretStatus): Result<Unit> {
        session.update { it.copy(modelSecrets = it.modelSecrets + (providerId to status), modelError = null) }
        return Result.success(Unit)
    }
}

/** Отказ хоста в терминах экрана настроек: связь, отказ конфигурации или отказ ключа. */
private fun ProtocolError.toModelConfigError(): ModelConfigError = when (this) {
    is ProtocolError.InvalidAgentConfig -> ModelConfigError.Rejected(rejection)
    is ProtocolError.InvalidModelSecret -> ModelConfigError.SecretRejected(rejection)
    else -> ModelConfigError.Unreachable
}
