package dev.aide.host.agent

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.UnknownRunException
import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.requestIdOrNull
import dev.aide.host.store.RunStore
import dev.aide.host.store.TaskStore
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId

/**
 * Обработчик сообщений агента (T-1.1).
 *
 * Отдельная группа рядом со `StageZeroHandler`, а не внутри него: репозиторий и
 * агент — разные поводы меняться (О-1). Запросы отвечают сразу, работа идёт в
 * воркере хоста, а состояние приходит клиенту событиями (решение 1).
 */
class AgentRunHandler(
    private val engine: AgentRunEngine,
    private val runs: RunStore,
    private val tasks: TaskStore,
) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.PostTask -> HostMessage.TaskPosted(
            requestId = message.requestId,
            taskId = engine.postTask(message.prompt, message.mode),
        )

        is ClientMessage.AgentStatus -> HostMessage.AgentSnapshot(
            requestId = message.requestId,
            runs = runs.all(),
            tasks = tasks.all(),
        )

        is ClientMessage.RunControl -> control(message)

        // «Чужое» сообщение сюда попадает только из-за ошибки маршрутизации; отвечаем
        // его же идентификатором, иначе клиент ждал бы ответа до таймаута.
        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull() ?: RequestId("agent"),
            error = ProtocolError.Internal("AgentRunHandler получил сообщение другого вида"),
        )
    }

    /** Неизвестный прогон — типизированная ошибка, а не молчание (решение: control). */
    private fun control(message: ClientMessage.RunControl): HostMessage = try {
        engine.control(message.runId, message.command)
        HostMessage.RunControlled(message.requestId, message.runId)
    } catch (error: UnknownRunException) {
        HostMessage.Failure(message.requestId, ProtocolError.NotFound("прогон ${error.runId.value}"))
    }
}
