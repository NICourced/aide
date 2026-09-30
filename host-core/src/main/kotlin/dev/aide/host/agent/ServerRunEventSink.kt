package dev.aide.host.agent

import dev.aide.agent.ports.AgentEventSink
import dev.aide.domain.AgentRun
import dev.aide.domain.Task
import dev.aide.host.server.ClientSessions
import dev.aide.protocol.HostEvent

/**
 * Рассылка смены состояния прогона и статуса задачи клиентам (T-1.1).
 *
 * Движок отдаёт событие порту, а хост рассылает его как событие без запроса (§ 8.4).
 * Реестр сессий общий с сервером — поэтому подключение и рассылка событий работают
 * через один и тот же список.
 */
class ServerRunEventSink(private val sessions: ClientSessions) : AgentEventSink {

    override suspend fun runStateChanged(run: AgentRun) {
        sessions.broadcast(HostEvent.RunStateChanged(run))
    }

    override suspend fun taskStateChanged(task: Task) {
        sessions.broadcast(HostEvent.TaskStateChanged(task))
    }
}
