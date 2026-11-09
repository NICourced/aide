package dev.aide.host.server

import dev.aide.host.agent.PendingApprovals
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.requestIdOrNull

/**
 * Обработчик решений по подтверждению вызовов инструментов (T-1.13).
 *
 * Ответ клиента маршрутизируется в [PendingApprovals], а список ожидающих запросов
 * отдаётся подключившемуся позже клиенту.
 */
class ApprovalHandler(private val pending: PendingApprovals) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.ApprovalAnswer -> {
            pending.answer(message.runId, message.callId, message.decision)
            HostMessage.ApprovalAnswered(message.requestId, message.runId, message.callId)
        }

        is ClientMessage.PendingApprovals -> HostMessage.PendingApprovalsSnapshot(
            requestId = message.requestId,
            requests = pending.snapshot(),
        )

        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("approval"),
            error = ProtocolError.Internal("ApprovalHandler получил сообщение другого вида"),
        )
    }
}
