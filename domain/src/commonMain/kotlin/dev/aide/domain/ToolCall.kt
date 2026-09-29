package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Один вызов инструмента агентом (§ 4, FR-AGENT-8, FR-AGENT-9). */
@Serializable
data class ToolCall(
    /** Идентификатор вызова. */
    val id: ToolCallId,
    /** Прогон, в рамках которого сделан вызов. */
    val runId: RunId,
    /** Имя инструмента. */
    val tool: String,
    /** Аргументы вызова в сериализованном виде. */
    val arguments: String,
    /** Результат вызова; null, пока вызов не завершён. */
    val result: String? = null,
    /** Чем закончился вызов. */
    val outcome: ToolOutcome,
    /** Длительность вызова в миллисекундах. */
    val durationMillis: Long,
    /** Стоимость вызова. */
    val cost: Cost,
    /** Требовал ли вызов подтверждения пользователя. */
    val requiredApproval: Boolean,
    /** Что ответил пользователь; null, если подтверждение не требовалось. */
    val approval: ApprovalDecision? = null,
    /** Момент завершения вызова. */
    val at: Instant,
)

/** Разрешение на инструмент, отдельно для чтения и для записи (FR-TOOLS-8, § 10.1). */
@Serializable
data class ToolPermission(
    /** Имя инструмента. */
    val tool: String,
    /** Разрешение на чтение. */
    val read: Permission,
    /** Разрешение на запись. */
    val write: Permission,
)
