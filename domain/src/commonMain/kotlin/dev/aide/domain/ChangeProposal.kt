package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Сохранённое, но неприменённое файловое изменение — единица очереди предложений (T-1.15).
 *
 * В отличие от записи журнала [ToolCall], здесь лежит само содержимое изменения в виде
 * [FileChange], чтобы будущий пакет ревью мог собрать diff без повторного исполнения
 * вызова. Полноценный [ChangePacket] появится в T-1.24; до него очередь предложений
 * хранит сырые файловые изменения.
 */
@Serializable
data class ChangeProposal(
    /** Идентификатор предложения. */
    val id: ProposalId,
    /** Прогон, в рамках которого сделано предложение. */
    val runId: RunId,
    /** Вызов, которым предложение получено. */
    val callId: ToolCallId,
    /** Инструмент, предложивший изменение. */
    val tool: String,
    /** Файловые изменения без применения к диску. */
    val files: List<FileChange>,
    /** Уровень риска предложения по доверенному классификатору. */
    val risk: RiskLevel,
    /** Момент создания предложения. */
    val createdAt: Instant,
)
