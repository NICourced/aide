package dev.aide.host.agent

import dev.aide.host.server.ClientMessageHandler
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage

/**
 * Направляет сообщения клиента тому обработчику, который ими владеет.
 *
 * Репозиторий, ФС и git и рантайм агента — разные поводы меняться, поэтому обработчики
 * разделены (О-1); маршрутизация живёт в одном месте и не размазана по сессиям.
 * Ключи провайдеров — отдельный обработчик от конфигурации (T-1.58): конфигурацию
 * правит человек в файле, а ключ живёт в хранилище платформы.
 */
class ClientMessageRouter(
    private val stageZero: ClientMessageHandler,
    private val agent: ClientMessageHandler,
    private val modelConfig: ClientMessageHandler,
    private val modelSecrets: ClientMessageHandler,
) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.PostTask, is ClientMessage.AgentStatus, is ClientMessage.RunControl -> agent.handle(message)
        is ClientMessage.AgentConfigRequest, is ClientMessage.SaveAgentConfig, is ClientMessage.CheckModel ->
            modelConfig.handle(message)

        is ClientMessage.SetModelSecret, is ClientMessage.DeleteModelSecret, is ClientMessage.ModelSecrets ->
            modelSecrets.handle(message)

        else -> stageZero.handle(message)
    }
}
