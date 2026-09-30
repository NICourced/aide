package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError

/** Файл, изменённый относительно HEAD. */
data class ChangedFile(
    /** Путь относительно корня репозитория. */
    val path: String,
    /** Что произошло с файлом. */
    val changeKind: FileChangeKind,
    /** Прежний путь при переименовании; null в остальных случаях. */
    val previousPath: String? = null,
)

/** Коммит в истории. */
data class CommitInfo(
    /** Полный хеш коммита. */
    val hash: String,
    /** Короткий хеш для показа в UI. */
    val shortHash: String,
    /** Первая строка сообщения. */
    val message: String,
    /** Автор в формате «Имя <почта>». */
    val author: String,
    /** Время коммита в миллисекундах от эпохи. */
    val committedAtEpochMillis: Long,
)

/**
 * Чтение состояния git-репозитория и первая изменяющая операция — ветка задачи.
 *
 * Интерфейс отделён от реализации: JGit — не единственный возможный источник
 * (этап 6 добавляет Rust-модуль для git-операций через FFI, § 8.1), а задачи
 * этапа 1 работают с этим интерфейсом, а не с JGit напрямую.
 */
interface GitRepository : AutoCloseable {

    /** Имя текущей ветки; для репозитория без коммитов возвращает имя ещё не созданной ветки. */
    fun currentBranch(): String

    /** Короткий хеш HEAD; пустая строка, если коммитов нет. */
    fun headCommit(): String

    /** Изменения относительно HEAD: изменённые, добавленные, удалённые и переименованные файлы. */
    fun changedFiles(): List<ChangedFile>

    /** История коммитов текущей ветки, от нового к старому. */
    fun commitLog(limit: Int = DEFAULT_LOG_LIMIT): List<CommitInfo>

    /**
     * Обеспечивает ветку [branch] и переключает в неё рабочий каталог (T-1.18).
     *
     * Идемпотентно: ветки нет — создаётся от текущего HEAD; ветка есть — прогон
     * продолжает её. Ref, история и рабочее дерево при этом не перезаписываются:
     * откат и перезапись — не то, что делает повторный запуск задачи.
     *
     * Отказ возвращается результатом, а не исключением: «нет коммитов», «HEAD отсоединён»
     * и «репозиторий только для чтения» — состояния репозитория, которые обязан объяснить
     * движок, а не сбой чтения, ради которого существует [GitAccessException].
     */
    fun ensureTaskBranch(branch: String): TaskBranchOutcome

    companion object {
        /** Сколько коммитов отдаётся по умолчанию: столько помещается в список без подгрузки. */
        const val DEFAULT_LOG_LIMIT: Int = 50
    }
}

/** Ошибка доступа к репозиторию, несущая типизированную причину. */
class GitAccessException(val error: ProtocolError) : Exception(error.toString())

/**
 * Как репозиторий обеспечил ветку задачи (T-1.18).
 *
 * В терминах git, без кодов прогона: git-слой не знает ни о задачах, ни о прогонах,
 * а перевод причины в код отказа делает адаптер порта (`TaskBranchGuard`).
 */
sealed interface TaskBranchOutcome {

    /** Ветки не было: создана от [base]. */
    data class Created(val base: String) : TaskBranchOutcome

    /** Ветка уже была: переключились в неё, ref и история не тронуты. */
    data object Existing : TaskBranchOutcome

    /** Ветку задачи получить нельзя; [reason] объясняет, почему именно. */
    data class Refused(val reason: TaskBranchRefusal) : TaskBranchOutcome
}

/** Почему репозиторий не может дать ветку задачи. */
enum class TaskBranchRefusal {

    /** Коммитов нет: ветку не от чего ответвлять. */
    NO_COMMITS,

    /** HEAD отсоединён: базовой ветки не существует, а угадывать её нельзя. */
    DETACHED_HEAD,

    /** Репозиторий нельзя изменить: ссылку на ветку записать некуда. */
    READ_ONLY,

    /** Git отказал в создании ветки или в переключении: правки мешают либо ссылка не пишется. */
    GIT_FAILED,
}
