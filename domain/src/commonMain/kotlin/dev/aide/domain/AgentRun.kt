package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Стоимость в миллионных долях единицы тарификации провайдера.
 * Единица выбрана целочисленной, чтобы суммы совпадали с журналом до последней цифры (§ 5.9).
 */
@Serializable
data class Cost(
    /** Накопленная стоимость. При [known] = false равно нулю и не учитывается в итогах. */
    val amountMicros: Long = 0,
    /** false — цена хотя бы одного вызова неизвестна; итог с такой позицией помечается как неполный (FR-COST-5). */
    val known: Boolean = true,
)

/** Один шаг плана, показанного до первого изменяющего действия (FR-AGENT-7). */
@Serializable
data class PlanStep(
    /** Порядковый номер шага, начиная с 0. */
    val index: Int,
    /** Что агент собирается сделать, одной строкой. */
    val summary: String,
    /** Состояние шага. */
    val status: StepStatus,
)

/** Один прогон агента по задаче (§ 4). */
@Serializable
data class AgentRun(
    /** Идентификатор прогона. */
    val id: RunId,
    /** Задача, по которой идёт прогон. */
    val taskId: TaskId,
    /** Текущее состояние прогона. */
    val state: RunState,
    /** Режим автономности, зафиксированный на старте; переключение режима его не меняет (FR-AGENT-5). */
    val mode: AutonomyMode,
    /** План, показанный до первого изменяющего действия. */
    val plan: List<PlanStep> = emptyList(),
    /** Идентификаторы вызовов инструментов этого прогона, в порядке выполнения. */
    val toolCallIds: List<ToolCallId> = emptyList(),
    /** Момент старта прогона. */
    val startedAt: Instant,
    /** Момент завершения; null, пока прогон идёт. */
    val finishedAt: Instant? = null,
    /** Длительность прогона в миллисекундах (FR-AGENT-10). */
    val elapsedMillis: Long = 0,
    /** Накопленная стоимость прогона (FR-AGENT-10). */
    val cost: Cost = Cost(),
    /** Причина прерывания: падение хоста, стоп пользователя или отказ инструмента. */
    val interruptReason: String? = null,
)
