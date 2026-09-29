package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Пакет изменений — единица ревью, соответствует одной задаче (§ 4). */
@Serializable
data class ChangePacket(
    /** Идентификатор пакета. */
    val id: PacketId,
    /** Задача, результатом которой стал пакет. */
    val taskId: TaskId,
    /** Ревизия пакета; доработка создаёт новую ревизию, прежняя остаётся в истории (инвариант 2 § 4.1). */
    val revision: Int,
    /** Название пакета для карточки инбокса. */
    val title: String,
    /** Краткое объяснение от агента целиком по пакету (FR-INBOX-3). */
    val summary: String,
    /** Изменения по файлам. */
    val files: List<FileChange>,
    /** Уровень риска пакета; не ниже максимального риска его hunk'ов (инвариант 5 § 4.1). */
    val risk: RiskLevel,
    /** Результат тестов и линтера (FR-DIFF-17). */
    val tests: TestStatus,
    /** Кто источник изменений. */
    val source: ChangeSource,
    /** Состояние пакета в очереди ревью. */
    val status: PacketStatus,
    /** Ветка задачи, из которой собран пакет. */
    val branch: String,
    /** Снапшот, к которому можно откатить пакет; null, если снапшот ещё не поставлен. */
    val snapshotRef: SnapshotRef? = null,
    /** Момент сборки пакета. */
    val createdAt: Instant,
)

/** Суммарно добавленные строки по всем файлам — то, что карточка показывает как `+N`. */
val ChangePacket.addedLines: Int get() = files.sumOf { it.addedLines }

/** Суммарно удалённые строки по всем файлам — то, что карточка показывает как `-M`. */
val ChangePacket.removedLines: Int get() = files.sumOf { it.removedLines }

/** Изменения одного файла внутри пакета (§ 4). */
@Serializable
data class FileChange(
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Что произошло с файлом. */
    val changeKind: FileChangeKind,
    /** Блоки изменений файла. */
    val hunks: List<Hunk>,
    /** Добавленные строки в этом файле. */
    val addedLines: Int,
    /** Удалённые строки в этом файле. */
    val removedLines: Int,
    /** Прежний путь; заполнен только при [changeKind] = [FileChangeKind.RENAMED]. */
    val previousPath: String? = null,
)

/** Один связанный блок изменений — единица показа и решения на мобильном экране (§ 4). */
@Serializable
data class Hunk(
    /** Идентификатор блока. */
    val id: HunkId,
    /** Путь файла, к которому относится блок. */
    val filePath: String,
    /** Номер первой строки в исходном файле. */
    val startLine: Int,
    /** Что произошло с блоком. */
    val kind: HunkKind,
    /** Риск блока; определяет, попадает ли он в «Принять всё безопасное» (FR-INBOX-10). */
    val risk: RiskLevel,
    /** Строки блока в порядке следования. */
    val lines: List<HunkLine>,
)

/** Одна строка внутри блока. */
@Serializable
data class HunkLine(
    /** Роль строки: контекст, добавленная или удалённая. */
    val kind: LineKind,
    /** Номер строки в исходном файле; null для добавленных строк. */
    val oldNumber: Int? = null,
    /** Номер строки в новом файле; null для удалённых строк. */
    val newNumber: Int? = null,
    /** Текст строки без diff-префикса. */
    val text: String,
)

/** Итог прогона тестов и линтера по пакету. */
@Serializable
data class TestStatus(
    /** Итог последнего прогона. */
    val state: TestState,
    /** Имена упавших тестов; пусто, если падений нет. */
    val failed: List<String> = emptyList(),
    /** Число замечаний линтера. */
    val lintFindings: Int = 0,
)
