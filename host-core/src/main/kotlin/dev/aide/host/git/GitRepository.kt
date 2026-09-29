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
 * Чтение состояния git-репозитория.
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

    companion object {
        /** Сколько коммитов отдаётся по умолчанию: столько помещается в список без подгрузки. */
        const val DEFAULT_LOG_LIMIT: Int = 50
    }
}

/** Ошибка доступа к репозиторию, несущая типизированную причину. */
class GitAccessException(val error: ProtocolError) : Exception(error.toString())
