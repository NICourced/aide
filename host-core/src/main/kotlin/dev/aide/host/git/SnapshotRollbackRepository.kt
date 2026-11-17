package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef

/**
 * Операции отката к снапшоту: список, предпросмотр и сам откат (T-1.6).
 *
 * Отдельный интерфейс от [GitRepository]: это операция над историей и рабочим деревом
 * со своими предпроверками, и у неё свой повод меняться. Свойство в основном интерфейсе
 * не перегружает его — реализация живёт в отдельном классе.
 */
interface SnapshotRollbackRepository {

    /** Снапшоты — только предки текущего HEAD, от новых к старым по метке ссылки. */
    fun list(): List<Snapshot>

    /**
     * Предпросмотр отката к снапшоту [ref].
     *
     * Возвращает diff между деревом снапшота и текущим HEAD: что именно будет отменено.
     * Пустой diff — отказ [SnapshotRollbackRefusal.NOTHING_TO_REVERT], грязное дерево —
     * [SnapshotRollbackRefusal.DIRTY_WORKTREE], снапшот не предок HEAD —
     * [SnapshotRollbackRefusal.NOT_IN_CURRENT_BRANCH].
     */
    fun preview(ref: SnapshotRef): SnapshotRollbackOutcome<SnapshotPreview>

    /**
     * Откатывает рабочее дерево и текущую ветку к снапшоту [ref] обратным коммитом.
     *
     * [expectedHead] защищает от гонки: если HEAD изменился с момента предпросмотра,
     * откат не выполняется ([SnapshotRollbackRefusal.HEAD_CHANGED]). Откат сохраняет
     * историю — создаётся новый коммит с деревом снапшота, родителем которого является
     * текущий HEAD, а затем ветка и индекс двигаются на него.
     */
    fun revert(ref: SnapshotRef, expectedHead: String): SnapshotRollbackOutcome<SnapshotRevertCommitted>
}

/** Исход операции отката к снапшоту: успех или типизированный отказ. */
sealed interface SnapshotRollbackOutcome<out T> {

    /** Операция выполнена; результат в [value]. */
    data class Success<T>(val value: T) : SnapshotRollbackOutcome<T>

    /** Операция отклонена по существу; [reason] объясняет, почему именно. */
    data class Refused(val reason: SnapshotRollbackRefusal) : SnapshotRollbackOutcome<Nothing>
}

/** Обратный коммит, созданный при откате к снапшоту (T-1.6). */
data class SnapshotRevertCommitted(
    /** Полный хеш созданного коммита, ставшего новым HEAD. */
    val commit: String,
)

/** Почему операция отката к снапшоту отклонена (без [ACTIVE_RUN] — это правило обработчика). */
enum class SnapshotRollbackRefusal {
    /** Снапшот не найден или имя ссылки не из пространства `refs/ai/snap/`. */
    UNKNOWN_SNAPSHOT,

    /** Снапшот не является предком текущего HEAD. */
    NOT_IN_CURRENT_BRANCH,

    /** Рабочее дерево или индекс содержат незафиксированные изменения. */
    DIRTY_WORKTREE,

    /** HEAD изменился с момента предпросмотра. */
    HEAD_CHANGED,

    /** Дерево снапшота совпадает с деревом HEAD — откатывать нечего. */
    NOTHING_TO_REVERT,

    /** Git-операция не завершилась по технической причине. */
    GIT_FAILED,
}

/** Внутренний diff-результат для построения [SnapshotFileChange]. */
internal data class RawFileDiff(
    /** Путь в HEAD. */
    val path: String,
    /** Прежний путь при переименовании. */
    val previousPath: String?,
    /** Вид изменения между снапшотом и HEAD. */
    val changeKind: FileChangeKind,
    /** Строк, добавленных в HEAD относительно снапшота. */
    val addedLines: Int,
    /** Строк, удалённых из снапшота при переходе к HEAD. */
    val removedLines: Int,
)
