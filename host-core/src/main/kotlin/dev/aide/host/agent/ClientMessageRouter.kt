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
 * правит человек в файле, а ключ живёт в хранилище платформы. Журнал вызовов (T-1.3) —
 * тоже отдельный: он читается страницами, а не отвечает за задачи и прогоны.
 */
class ClientMessageRouter(
    private val stageZero: ClientMessageHandler,
    private val agent: ClientMessageHandler,
    private val approvals: ClientMessageHandler,
    private val modelSettings: ClientMessageHandler,
    private val toolLog: ClientMessageHandler,
    private val snapshots: ClientMessageHandler,
) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.PostTask, is ClientMessage.AgentStatus, is ClientMessage.RunControl,
        is ClientMessage.PlanDecision -> agent.handle(message)

        is ClientMessage.ApprovalAnswer, is ClientMessage.PendingApprovals -> approvals.handle(message)

        is ClientMessage.AgentConfigRequest, is ClientMessage.SaveAgentConfig, is ClientMessage.CheckModel,
        is ClientMessage.SetModelSecret, is ClientMessage.DeleteModelSecret, is ClientMessage.ModelSecrets ->
            modelSettings.handle(message)

        is ClientMessage.ToolCalls, is ClientMessage.ToolCallDetail -> toolLog.handle(message)

        is ClientMessage.SnapshotList, is ClientMessage.SnapshotPreviewRequest,
        is ClientMessage.SnapshotRevert -> snapshots.handle(message)

        else -> stageZero.handle(message)
    }

    /**
     * Объединяет конфигурацию моделей и секреты в один обработчик, чтобы уменьшить
     * число параметров [ClientMessageRouter] (T-1.58, T-1.6).
     */
    internal class ModelSettingsHandler(
        private val config: ClientMessageHandler,
        private val secrets: ClientMessageHandler,
    ) : ClientMessageHandler {

        override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
            is ClientMessage.AgentConfigRequest, is ClientMessage.SaveAgentConfig, is ClientMessage.CheckModel ->
                config.handle(message)

            is ClientMessage.SetModelSecret, is ClientMessage.DeleteModelSecret, is ClientMessage.ModelSecrets ->
                secrets.handle(message)

            else -> error("ModelSettingsHandler получил сообщение другого вида")
        }
    }
}
