package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Path
import kotlin.io.path.exists
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/**
 * Реализация чтения состояния git через JGit.
 *
 * Никаких изменяющих операций здесь нет: ветки, коммиты и снапшоты появляются
 * в задачах T-1.11, T-1.18 и T-1.19. Эта задача читает состояние, чтобы UI мог
 * показать ветку и дерево.
 */
class JGitRepository private constructor(
    private val repository: Repository,
    private val git: Git,
) : GitRepository {

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

private fun notAGitRepository(workTree: Path): Nothing =
    throw GitAccessException(ProtocolError.NotAGitRepository(workTree.toString()))
