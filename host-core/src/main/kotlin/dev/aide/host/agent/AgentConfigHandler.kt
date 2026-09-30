package dev.aide.host.agent

import dev.aide.agent.config.AgentConfigValidator
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.server.ClientMessageHandler
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.requestIdOrNull

/**
 * Настройки моделей: конфигурация, каталог заготовок, проверка доступа (T-1.56).
 *
 * Отдельная группа рядом с [AgentRunHandler]: прогон и его настройка — разные поводы
 * меняться. Здесь нет ни движка, ни транспорта: чтение файла, запись файла и один
 * запрос проверки, который уходит в реестр провайдеров.
 *
 * @param store файл конфигурации рядом с базой хоста.
 * @param checkModel проверка доступа к модели; у реестра это запрос списка моделей.
 * @param catalog заготовки популярных сервисов; читаются лениво, потому что нужны
 *   только экрану настроек.
 */
class AgentConfigHandler(
    private val store: AgentConfigStore,
    private val checkModel: suspend (String) -> ModelCheckFailure?,
    private val catalog: () -> List<ProviderCatalogEntry>,
) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.AgentConfigRequest -> HostMessage.AgentConfigSnapshot(
            requestId = message.requestId,
            config = store.load(),
            catalog = catalog(),
        )

        is ClientMessage.SaveAgentConfig -> save(message)
        is ClientMessage.CheckModel -> check(message)

        // «Чужое» сообщение сюда попадает только из-за ошибки маршрутизации; отвечаем
        // его же идентификатором, иначе клиент ждал бы ответа до таймаута.
        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("config"),
            error = ProtocolError.Internal("AgentConfigHandler получил сообщение другого вида"),
        )
    }

    /**
     * Сохраняет конфигурацию и возвращает то, что записано.
     *
     * Ответ несёт саму конфигурацию, а не «готово»: клиент правит копию, и без возврата
     * он не знал бы, что именно легло в файл, — показывал бы свою версию, а хост свою.
     * Непринятая конфигурация не пишется вовсе: отвергнутый файл настроек хуже прежнего.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun save(message: ClientMessage.SaveAgentConfig): HostMessage {
        val rejection = AgentConfigValidator.validate(message.config)
        return if (rejection != null) {
            HostMessage.Failure(message.requestId, ProtocolError.InvalidAgentConfig(rejection))
        } else {
            try {
                store.save(message.config)
                HostMessage.AgentConfigSaved(message.requestId, store.load())
            } catch (error: Exception) {
                HostMessage.Failure(
                    message.requestId,
                    ProtocolError.Internal("конфигурация моделей не сохранена", error.message),
                )
            }
        }
    }

    /**
     * Проверка доступа к модели: `ok` — только при подтверждённом доступе.
     *
     * Отказ, а не «готово» — и он типизирован: «провайдер не поддерживает запрос списка
     * моделей» и «ключ неверен» не должны выглядеть одинаково (решение 5).
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun check(message: ClientMessage.CheckModel): HostMessage = try {
        val failure = checkModel(message.alias)
        HostMessage.ModelCheckResult(message.requestId, ok = failure == null, failure = failure)
    } catch (error: Exception) {
        HostMessage.ModelCheckResult(
            message.requestId,
            ok = false,
            failure = ModelCheckFailure.RequestFailed(error.message),
        )
    }
}
