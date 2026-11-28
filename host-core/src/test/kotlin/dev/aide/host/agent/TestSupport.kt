package dev.aide.host.agent

import dev.aide.agent.ports.ChangePackets
import dev.aide.domain.AgentRun
import dev.aide.domain.ChangePacket
import dev.aide.domain.ChangeSource
import dev.aide.domain.PacketId
import dev.aide.domain.PacketStatus
import dev.aide.domain.RiskLevel
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import kotlinx.datetime.Instant

/**
 * Порт пакетов изменений для тестов host-core, которым не важен сам пакет (T-1.24).
 *
 * host-agent тестовые утилиты не публикуются как артефакт, поэтому минимальный
 * порт дублируется здесь — там, где он нужен интеграционным тестам хоста.
 */
val noPackets: ChangePackets = object : ChangePackets {

    override suspend fun publish(task: Task, run: AgentRun): ChangePacket = ChangePacket(
        id = PacketId("noop"),
        taskId = task.id,
        revision = 1,
        title = "noop",
        summary = "noop",
        files = emptyList(),
        risk = RiskLevel.NORMAL,
        tests = TestStatus(state = TestState.NOT_RUN),
        source = ChangeSource.AGENT,
        status = PacketStatus.AWAITING_REVIEW,
        branch = task.branch,
        createdAt = Instant.fromEpochMilliseconds(1),
    )
}
