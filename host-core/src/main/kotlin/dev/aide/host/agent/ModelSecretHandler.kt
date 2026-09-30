package dev.aide.host.agent

import dev.aide.agent.provider.ModelSecretRejectedException
import dev.aide.agent.provider.ModelSecrets
import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.AgentConfig
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderProfile
import dev.aide.host.server.ClientMessageHandler
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.requestIdOrNull
import org.slf4j.LoggerFactory

/**
 * Ключи провайдеров в защищённом хранилище хоста (T-1.58).
 *
 * Отдельная группа рядом с [AgentConfigHandler]: конфигурация — это то, что правит человек
 * в файле, а ключ — секрет, который живёт в хранилище платформы и в файл не попадает
 * никогда. Обратного пути у значения нет: ни один ответ этого обработчика не несёт ключ,
 * только код состояния ([ModelSecretStatus]).
 *
 * Отказ хранилища — состояние, а не ошибка: запрос выполнен, ответ есть, сохранить нельзя.
 * Отказ по существу (пустое значение, неизвестный провайдер) — типизированная ошибка
 * запроса: данные негодны, и это видно пользователю словами (NFR-13).
 *
 * @param store защищённое хранилище платформы.
 * @param config конфигурация моделей: по ней находится провайдер и его имя переменной.
 * @param env окружение хоста — второй источник ключа после хранилища.
 */
class ModelSecretHandler(
    store: SecretStore,
    private val config: () -> AgentConfig,
    env: (String) -> String? = System::getenv,
) : ClientMessageHandler {

    private val logger = LoggerFactory.getLogger(ModelSecretHandler::class.java)

    private val secrets = ModelSecrets(store = store, env = env)

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.SetModelSecret -> change(message.requestId, message.providerId) { provider ->
            secrets.put(provider, message.value)
        }

        is ClientMessage.DeleteModelSecret -> change(message.requestId, message.providerId) { provider ->
            secrets.delete(provider)
        }

        is ClientMessage.ModelSecrets -> HostMessage.ModelSecretsSnapshot(
            requestId = message.requestId,
            statuses = statuses(),
        )

        // «Чужое» сообщение сюда попадает только из-за ошибки маршрутизации; отвечаем
        // его же идентификатором, иначе клиент ждал бы ответа до таймаута.
        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("secrets"),
            error = ProtocolError.Internal("ModelSecretHandler получил сообщение другого вида"),
        )
    }

    /** Состояние ключей всех провайдеров конфигурации: карта «провайдер → код состояния». */
    private fun statuses(): Map<String, ModelSecretStatus> =
        config().providers.associate { provider -> provider.id to secrets.status(provider) }

    /**
     * Общая обёртка записи и удаления: разбор причины отказа в одном месте.
     *
     * Значение ключа сюда не попадает вовсе — оно живёт внутри лямбды, и в журнал уходит
     * только имя провайдера и код состояния.
     */
    private fun change(
        requestId: RequestId,
        providerId: String,
        op: (ProviderProfile) -> ModelSecretStatus,
    ): HostMessage {
        val provider = config().providers.firstOrNull { it.id == providerId }
            ?: return HostMessage.Failure(
                requestId,
                ProtocolError.InvalidModelSecret(ModelSecretRejection.UnknownProvider(providerId)),
            )
        return try {
            val status = op(provider)
            logger.info("Ключ провайдера $providerId: $status")
            HostMessage.ModelSecretChanged(requestId, providerId, status)
        } catch (error: SecretStoreUnavailableException) {
            // Гонка или сбой платформенного шифрования: хранилище отказало уже после
            // проверки доступности. Ответ тот же, что и при заведомо недоступном.
            HostMessage.ModelSecretChanged(
                requestId,
                providerId,
                ModelSecretStatus.StoreUnavailable(error.reason),
            )
        } catch (error: ModelSecretRejectedException) {
            HostMessage.Failure(requestId, ProtocolError.InvalidModelSecret(error.rejection))
        }
    }
}
