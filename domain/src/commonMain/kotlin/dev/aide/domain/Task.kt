package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Постановка задачи пользователем: текст или расшифровка голоса (§ 4). */
@Serializable
data class Task(
    /** Идентификатор задачи. */
    val id: TaskId,
    /** Короткое название, которое видно на карточке пакета. */
    val title: String,
    /** Исходная постановка: текст или расшифровка голоса (FR-AGENT-20, FR-AGENT-21). */
    val prompt: String,
    /** Ветка задачи вида `ai/<task-id>` (§ 8.3). */
    val branch: String,
    /** Текущее состояние задачи. */
    val status: TaskStatus,
    /** Момент постановки задачи. */
    val createdAt: Instant,
    /** Прогоны агента по этой задаче, в порядке запуска. */
    val runIds: List<RunId> = emptyList(),
)
