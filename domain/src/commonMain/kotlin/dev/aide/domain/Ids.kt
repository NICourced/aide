package dev.aide.domain

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Идентификатор задачи, поставленной пользователем. */
@Serializable
@JvmInline
value class TaskId(val value: String)

/** Идентификатор одного прогона агента по задаче. */
@Serializable
@JvmInline
value class RunId(val value: String)

/** Идентификатор пакета изменений — единицы ревью. */
@Serializable
@JvmInline
value class PacketId(val value: String)

/** Идентификатор блока изменений внутри файла. */
@Serializable
@JvmInline
value class HunkId(val value: String)

/** Идентификатор одного вызова инструмента агентом. */
@Serializable
@JvmInline
value class ToolCallId(val value: String)

/** Ссылка на снапшот в git, вида `refs/ai/snap/<timestamp>-<label>` (§ 9). */
@Serializable
@JvmInline
value class SnapshotRef(val value: String)
