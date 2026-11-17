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

/**
 * Предпросмотр отката к снапшоту: что изменилось между деревом снапшота и текущим HEAD (T-1.6).
 *
 * Содержимое файлов не едет: preview несёт только список файлов, виды изменений и объёмы
 * добавленных/удалённых строк. Полный хеш HEAD нужен, чтобы подтверждение привязать
 * к конкретному состоянию ветки.
 */
@Serializable
data class SnapshotPreview(
    /** Снапшот, к которому готовится откат. */
    val snapshot: Snapshot,
    /** Полный хеш текущего HEAD на момент предпросмотра. */
    val headCommit: String,
    /** Изменения между деревом снапшота и HEAD, отсортированные по пути. */
    val files: List<SnapshotFileChange>,
)

/**
 * Изменение одного файла в предпросмотре отката (T-1.6).
 *
 * [changeKind] описывает изменение, которое будет отменено: ADDED означает, что файл
 * появился после снапшота и при откате исчезнет, DELETED — что файл удалён после
 * снапшота и при откате вернётся, RENAMED — переименование, MODIFIED — правка.
 */
@Serializable
data class SnapshotFileChange(
    /** Путь файла относительно корня репозитория после изменения. */
    val path: String,
    /** Прежний путь при переименовании; null в остальных случаях. */
    val previousPath: String? = null,
    /** Что произошло с файлом между снапшотом и HEAD. */
    val changeKind: FileChangeKind,
    /** Сколько строк добавлено в HEAD относительно снапшота. */
    val addedLines: Int,
    /** Сколько строк удалено в HEAD относительно снапшота. */
    val removedLines: Int,
)
