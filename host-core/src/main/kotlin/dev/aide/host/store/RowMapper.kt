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
import dev.aide.domain.Task as DomainTask
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.host.store.Agent_run as AgentRunRow
import dev.aide.host.store.Review_decision as ReviewDecisionRow
import dev.aide.host.store.Tool_call as ToolCallRow
import dev.aide.host.store.Tool_permission as ToolPermissionRow
import kotlinx.datetime.Instant
import kotlinx.serialization.DeserializationStrategy

/**
 * Превращение строки базы в доменный объект.
 *
 * Обычный путь — разобрать `payload`. Пустая колонка означает запись, сделанную
 * до версии 2 схемы: там лежали только индексированные колонки, и полного
 * объекта у такой записи нет. Такую запись приходится собирать из колонок —
 * иначе чтение списка задач падало бы на базе, прошедшей миграцию 1 → 2.
 * Поля, которых в схеме версии 1 не было, при этом возвращаются значениями
 * по умолчанию: восстановить их нечем.
 */
internal object RowMapper {

    fun task(row: Task): DomainTask =
        decode(row.payload, DomainTask.serializer()) ?: DomainTask(
            id = TaskId(row.id),
            title = row.title,
            prompt = row.prompt,
            branch = row.branch,
            status = TaskStatus.valueOf(row.status),
            createdAt = Instant.fromEpochMilliseconds(row.created_at),
        )

    fun run(row: AgentRunRow): AgentRun =
        decode(row.payload, AgentRun.serializer()) ?: AgentRun(
            id = RunId(row.id),
            taskId = TaskId(row.task_id),
            state = RunState.valueOf(row.state),
            mode = AutonomyMode.valueOf(row.mode),
            startedAt = Instant.fromEpochMilliseconds(row.started_at),
            finishedAt = row.finished_at?.let { Instant.fromEpochMilliseconds(it) },
            elapsedMillis = row.elapsed_millis,
            cost = Cost(amountMicros = row.cost_micros, known = row.cost_known != 0L),
        )

    fun toolCall(row: ToolCallRow): ToolCall =
        decode(row.payload, ToolCall.serializer()) ?: ToolCall(
            id = ToolCallId(row.id),
            runId = RunId(row.run_id),
            tool = row.tool,
            arguments = "",
            outcome = ToolOutcome.valueOf(row.outcome),
            durationMillis = row.duration_millis,
            cost = Cost(),
            requiredApproval = row.required_approval != 0L,
            at = Instant.fromEpochMilliseconds(row.at),
        )

    fun decision(row: ReviewDecisionRow): ReviewDecision =
        decode(row.payload, ReviewDecision.serializer()) ?: ReviewDecision(
            packetId = PacketId(row.packet_id),
            packetRevision = row.packet_revision.toInt(),
            scope = DecisionScope.valueOf(row.scope),
            targetHunkId = row.target_hunk_id?.let { HunkId(it) },
            value = DecisionValue.valueOf(row.value_),
            clientPlatform = ClientPlatform.valueOf(row.client_platform),
            decidedAt = Instant.fromEpochMilliseconds(row.decided_at),
        )

    fun permission(row: ToolPermissionRow): ToolPermission =
        decode(row.payload, ToolPermission.serializer()) ?: ToolPermission(
            tool = row.tool,
            read = Permission.valueOf(row.read_permission),
            write = Permission.valueOf(row.write_permission),
        )

    private fun <T> decode(bytes: ByteArray, deserializer: DeserializationStrategy<T>): T? =
        if (bytes.isEmpty()) null else StoreCodec.decode(deserializer, bytes)
}
