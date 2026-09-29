package dev.aide.host.store

import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ClientPlatform
import dev.aide.domain.Cost
import dev.aide.domain.DecisionScope
import dev.aide.domain.DecisionValue
import dev.aide.domain.HunkId
import dev.aide.domain.PacketId
import dev.aide.domain.Permission
import dev.aide.domain.ReviewDecision
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import kotlinx.datetime.Instant

/**
 * Образцы доменных объектов для тестов хранилища.
 *
 * Набор повторяет `DomainFixtures` из `domain/src/commonTest`, но живёт здесь:
 * общий тестовый набор домена не публикуется как артефакт, и host-core его не видит.
 */
object StoreFixtures {

    private val t0 = Instant.parse("2026-09-22T10:00:00Z")
    private val t1 = Instant.parse("2026-09-22T10:01:00Z")

    val task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Сделай выдачу токена через сервис",
        branch = "ai/t-1",
        status = TaskStatus.REVIEW,
        createdAt = t0,
        runIds = listOf(RunId("r-1")),
    )

    val queuedTask = task.copy(
        id = TaskId("t-2"),
        title = "Второй запуск",
        prompt = "Починить сборку",
        branch = "ai/t-2",
        status = TaskStatus.QUEUED,
        createdAt = t1,
        runIds = emptyList(),
    )

    val run = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.FINISHED,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        toolCallIds = listOf(ToolCallId("tc-1")),
        startedAt = t0,
        finishedAt = t1,
        elapsedMillis = 60_000,
        cost = Cost(amountMicros = 12_500, known = true),
        interruptReason = null,
    )

    /** Прогон с неизвестной ценой: итог по задаче становится неполным (FR-COST-5). */
    val runWithUnknownCost = run.copy(
        id = RunId("r-2"),
        state = RunState.INTERRUPTED,
        toolCallIds = emptyList(),
        finishedAt = null,
        elapsedMillis = 5_000,
        cost = Cost(amountMicros = 0, known = false),
    )

    val toolCall = ToolCall(
        id = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "fs.write",
        arguments = """{"path":"src/auth/Login.kt"}""",
        result = "ok",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 42,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = true,
        at = t1,
    )

    val toolPermission = ToolPermission(tool = "fs.write", read = Permission.ALLOW, write = Permission.ASK)

    val decision = ReviewDecision(
        packetId = PacketId("p-1"),
        packetRevision = 1,
        scope = DecisionScope.HUNK,
        targetHunkId = HunkId("h-1"),
        value = DecisionValue.CHANGES_REQUESTED,
        comment = "Вынеси выдачу токена в отдельную функцию",
        clientPlatform = ClientPlatform.ANDROID,
        decidedAt = t1,
    )

    val packetLevelDecision = decision.copy(scope = DecisionScope.PACKET, targetHunkId = null, comment = null)
}
