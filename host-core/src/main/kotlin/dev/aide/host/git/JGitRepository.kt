package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.slf4j.LoggerFactory

/**
 * Реализация чтения состояния git через JGit.
 *
 * Изменяющая операция этого слоя — постановка задачи в её ветку (T-1.18) — вынесена
 * в [TaskBranchOperation]: у чтения состояния и у изменения репозитория разные поводы
 * меняться, а коммиты шагов и снапшоты принесут свои операции (T-1.11, T-1.19).
 */
class JGitRepository private constructor(
    private val repository: Repository,
    private val git: Git,
) : GitRepository {

    private val logger = LoggerFactory.getLogger(JGitRepository::class.java)

    private val taskBranches = TaskBranchOperation(repository, git)

    override fun currentBranch(): String = repository.branch ?: DETACHED_HEAD

    override fun headCommit(): String =
        runCatching { repository.resolve(HEAD) }.getOrNull()?.name?.take(SHORT_HASH_LENGTH).orEmpty()

    override fun changedFiles(): List<ChangedFile> {
        val status = runCatching { git.status().call() }.getOrElse { error ->
            throw GitAccessException(ProtocolError.Internal("не удалось прочитать состояние git", error.message))
        }
        val renamed = stagedRenames()
        return buildList {
            status.added.filterNot { it in renamed.values }.forEach { add(ChangedFile(it, FileChangeKind.ADDED)) }
            status.untracked.forEach { add(ChangedFile(it, FileChangeKind.ADDED)) }
            status.changed.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }
            status.modified.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }
            status.conflicting.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }
            status.removed.filterNot { it in renamed.keys }.forEach { add(ChangedFile(it, FileChangeKind.DELETED)) }
            status.missing.forEach { add(ChangedFile(it, FileChangeKind.DELETED)) }
            renamed.forEach { (oldPath, newPath) ->
                add(ChangedFile(path = newPath, changeKind = FileChangeKind.RENAMED, previousPath = oldPath))
            }
        }.distinctBy { it.path }.sortedBy { it.path }
    }

    override fun commitLog(limit: Int): List<CommitInfo> {
        if (headCommit().isEmpty()) return emptyList()
        return runCatching {
            git.log().setMaxCount(limit).call().map { commit ->
                CommitInfo(
                    hash = commit.name,
                    shortHash = commit.name.take(SHORT_HASH_LENGTH),
                    message = commit.fullMessage.lineSequence().first().trim(),
                    author = "${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>",
                    committedAtEpochMillis = commit.commitTime.toLong() * MILLIS_PER_SECOND,
                )
            }
        }.getOrElse { error ->
            throw GitAccessException(ProtocolError.Internal("не удалось прочитать историю", error.message))
        }
    }

    override fun close() {
        // `Git` владеет `Repository` и закрывает её вместе с собой.
        git.close()
    }

    override fun ensureTaskBranch(branch: String): TaskBranchOutcome = taskBranches.ensure(branch)

    /**
     * Переименования, уже зафиксированные в индексе.
     *
     * `git status` показывает переименование только между HEAD и индексом: переименование
     * в рабочем каталоге он отдаёт как удаление старого файла и новый файл вне индекса.
     * Поэтому сопоставление идёт по разнице HEAD → индекс, а не по рабочему дереву,
     * иначе результат разошёлся бы с `git status` на незакоммиченном `mv`.
     */
    private fun stagedRenames(): Map<String, String> {
        if (headCommit().isEmpty()) return emptyMap()
        return runCatching {
            val entries = git.diff().setCached(true).call()
            val detector = RenameDetector(repository)
            // Порог сходства как у git по умолчанию, иначе переименования с правкой
            // содержимого распознавались бы реже, чем их показывает `git status`.
            detector.renameScore = GIT_RENAME_SCORE
            detector.addAll(entries)
            detector.compute()
                .filter { it.changeType == DiffEntry.ChangeType.RENAME }
                .associate { it.oldPath to it.newPath }
        }.getOrElse { error ->
            throw GitAccessException(ProtocolError.Internal("не удалось сопоставить переименования", error.message))
        }
    }

    companion object {
        private const val HEAD = "HEAD"
        private const val GIT_DIR = ".git"
        private const val SHORT_HASH_LENGTH = 7
        private const val MILLIS_PER_SECOND = 1_000L
        private const val GIT_RENAME_SCORE = 50
        private const val DETACHED_HEAD = "HEAD"

        /** Открывает репозиторий по пути к рабочему каталогу. */
        fun open(workTree: Path): JGitRepository {
            val gitDir = workTree.resolve(GIT_DIR)
            if (!gitDir.exists()) {
                notAGitRepository(workTree)
            }
            val repository = runCatching {
                FileRepositoryBuilder()
                    .setWorkTree(workTree.toFile())
                    .setGitDir(gitDir.toFile())
                    .readEnvironment()
                    .build()
            }.getOrElse { notAGitRepository(workTree) }
            return JGitRepository(repository, Git(repository))
        }
    }
}

/**
 * Постановка задачи в её ветку (T-1.18) — единственная изменяющая операция git в этом слое.
 *
 * Отдельный класс от [JGitRepository]: чтение состояния и изменение репозитория — разные
 * поводы меняться, и операция держит собственные предпроверки (нет коммитов, HEAD
 * отсоединён, репозиторий только для чтения).
 */
private class TaskBranchOperation(private val repository: Repository, private val git: Git) {

    private val logger = LoggerFactory.getLogger(TaskBranchOperation::class.java)

    /** Обеспечивает ветку задачи, не переписывая её, если она уже есть. */
    fun ensure(branch: String): TaskBranchOutcome {
        // Спрашиваем точную ссылку, а не разбираем имя на части: веткой задачи считается
        // только локальная ветка — тег или ссылка из другого пространства имён ею не являются.
        val existing = runCatching { repository.exactRef(Constants.R_HEADS + branch) }.getOrNull()
        return if (existing != null) continueBranch(branch) else createFromHead(branch)
    }

    /** Ветка уже есть: прогон продолжает её, ref и история не тронуты. */
    private fun continueBranch(branch: String): TaskBranchOutcome =
        // Уже в ней: переключение не нужно, а лишний checkout трогал бы индекс.
        // У отсоединённого HEAD имени ветки нет, сравнение даст false — и переключение состоится.
        if (repository.branch == branch) {
            TaskBranchOutcome.Existing
        } else {
            attempt(branch) {
                git.checkout().setName(branch).call()
                TaskBranchOutcome.Existing
            }
        }

    /**
     * Создаёт ветку от текущего HEAD.
     *
     * Проверки идут от «ответвлять не от чего» к «записать нельзя»: у репозитория без
     * коммитов, репозитория с отсоединённым HEAD и репозитория только для чтения разные
     * причины, и пользователю нужна та, которая объясняет его случай.
     */
    private fun createFromHead(branch: String): TaskBranchOutcome {
        val head = runCatching { repository.exactRef(Constants.HEAD) }.getOrNull()
        return when {
            // HEAD — прямая ссылка на коммит: базовой ветки нет, а угадывать её нельзя.
            head == null || !head.isSymbolic -> TaskBranchOutcome.Refused(TaskBranchRefusal.DETACHED_HEAD)
            // Символическая ссылка на ещё не созданную ветку: коммитов нет, ответвлять нечего.
            head.objectId == null -> TaskBranchOutcome.Refused(TaskBranchRefusal.NO_COMMITS)
            // Создание ветки пишет ссылку в каталог `.git` — единственное место, куда пишет
            // эта операция; права на рабочий каталог здесь ни при чём.
            !isWritable() -> TaskBranchOutcome.Refused(TaskBranchRefusal.READ_ONLY)
            else -> attempt(branch) {
                git.branchCreate().setName(branch).call()
                git.checkout().setName(branch).call()
                TaskBranchOutcome.Created(head.leaf.name.removePrefix(Constants.R_HEADS))
            }
        }
    }

    /**
     * Выполняет изменяющую операцию git, превращая её сбой в отказ.
     *
     * Сбой здесь — не ошибка чтения: правило правок пользователя, занятая ссылка или
     * недоступный каталог. Прогон обязан отказаться, а не увести коммиты в чужую ветку,
     * поэтому причина уходит в журнал, а движок получает код отказа.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun attempt(branch: String, action: () -> TaskBranchOutcome): TaskBranchOutcome = try {
        action()
    } catch (error: Exception) {
        logger.warn("Не удалось поставить прогон в ветку $branch: ${error.message}", error)
        TaskBranchOutcome.Refused(TaskBranchRefusal.GIT_FAILED)
    }

    /** Есть ли куда записать ссылку: создание ветки пишет в каталог `.git`. */
    private fun isWritable(): Boolean = repository.directory?.let { Files.isWritable(it.toPath()) } == true
}

private fun notAGitRepository(workTree: Path): Nothing =
    throw GitAccessException(ProtocolError.NotAGitRepository(workTree.toString()))
