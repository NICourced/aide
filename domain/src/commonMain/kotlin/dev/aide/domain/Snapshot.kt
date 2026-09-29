package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Точка отката в git, к которой можно вернуть состояние репозитория (§ 9). */
@Serializable
data class Snapshot(
    /** Ссылка вида `refs/ai/snap/<timestamp>-<label>`. */
    val ref: SnapshotRef,
    /** Метка снапшота: `before-agent-step`, `after-agent-step`, `before-manual-edit`, `after-manual-edit`. */
    val label: String,
    /** Коммит, на который указывает ссылка. */
    val commit: String,
    /** Что стало поводом для снапшота. */
    val trigger: SnapshotTrigger,
    /** Задача, в рамках которой сделан снапшот; null для ручных правок вне задачи. */
    val taskId: TaskId? = null,
    /** Момент создания снапшота. */
    val createdAt: Instant,
)
