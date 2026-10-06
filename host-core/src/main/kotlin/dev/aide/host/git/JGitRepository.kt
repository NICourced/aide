package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlinx.coroutines.CancellationException
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.errors.StashApplyFailureException
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.RenameDetector
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.RefUpdate
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder
import org.slf4j.LoggerFactory

/**
 * Реализация чтения состояния git через JGit.
 *
 * Изменяющие операции вынесены в отдельные классы: постановка задачи в её ветку (T-1.18)
 * — в [TaskBranchOperation], снапшоты (T-1.19) — в [SnapshotOperation]. У чтения состояния
 * и у изменения репозитория разные поводы меняться, а коммиты шагов принесут свою операцию
 * (T-1.11).
 */
class JGitRepository private constructor(
    private val repository: Repository,
    private val git: Git,
) : GitRepository {

    private val logger = LoggerFactory.getLogger(JGitRepository::class.java)

    private val taskBranches = TaskBranchOperation(repository, git)

    private val snapshots = SnapshotOperation(repository)

    override val workStash: StashRepository = StashOperation(repository, git)

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

    override fun createSnapshot(ref: String): SnapshotOutcome = snapshots.create(ref)

    override fun snapshotRefs(): List<String> = snapshots.refs()

    override fun deleteSnapshots(refs: List<String>) = snapshots.delete(refs)

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
 * Постановка задачи в её ветку (T-1.18).
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

/**
 * Снапшоты в скрытом пространстве имён (T-1.19).
 *
 * Отдельный класс от [TaskBranchOperation]: постановка ветки переключает рабочее дерево
 * и HEAD, а снапшот — только ссылку на уже существующий коммит. Предпроверки поэтому
 * тоже разные: ветке мешает отсоединённый HEAD, снапшоту — единственное «ссылаться не на что».
 */
private class SnapshotOperation(private val repository: Repository) {

    private val logger = LoggerFactory.getLogger(SnapshotOperation::class.java)

    /** Ставит ссылку [ref] на текущий HEAD, не создавая коммит и не трогая рабочее дерево. */
    fun create(ref: String): SnapshotOutcome {
        val head = runCatching { repository.resolve(Constants.HEAD) }.getOrNull()
        return if (head == null) SnapshotOutcome.NoHead else writeRef(ref, head)
    }

    /**
     * Записывает ссылку на [head]; сбой — отказ, а не исключение.
     *
     * Так же, как у ветки задачи: «ссылка не пишется» — состояние репозитория, и движок
     * обязан получить код причины и пометить задачу, а не оставить прогон брошенным
     * исключением (иначе он застрянет в `PLANNED`, а задача — в `RUNNING` без объяснения).
     *
     * Успешные исходы — только те, при которых ссылка указывает на нужный коммит:
     * [RefUpdate.Result.NEW] (ссылки не было), [RefUpdate.Result.FORCED] (была и перезаписана),
     * [RefUpdate.Result.NO_CHANGE] (уже указывала на него). Остальные, включая
     * [RefUpdate.Result.LOCK_FAILURE] и [RefUpdate.Result.IO_FAILURE], — отказ.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun writeRef(ref: String, head: ObjectId): SnapshotOutcome = try {
        val update = repository.updateRef(ref).apply {
            setNewObjectId(head)
            isForceUpdate = true
        }
        val result = update.update()
        if (result in WRITTEN) {
            // FORCED значит, что ссылка уже была: два снапшота в одну миллисекунду с одним
            // поводом неразличимы, и первый теряется молча — поэтому о нём есть запись.
            if (result == RefUpdate.Result.FORCED) {
                logger.warn("Снапшот $ref перезаписан: два снапшота в одну миллисекунду с одним поводом")
            }
            SnapshotOutcome.Created
        } else {
            refused(ref, "исход ${result.name}")
        }
    } catch (cancel: CancellationException) {
        // Отмена — не «снапшот не поставлен»: если внутри появятся suspend-вызовы, отменённая
        // корутина обязана остаться отменённой (STOPPED), а не превратиться в отказ прогона.
        throw cancel
    } catch (error: Exception) {
        refused(ref, error.message.orEmpty(), error)
    }

    /** Ссылки снапшотов, от старых к новым: метка времени в имени сортируется как число. */
    fun refs(): List<String> = runCatching {
        repository.refDatabase.getRefsByPrefix(SNAPSHOT_REF_PREFIX)
            .map { it.name }
            .sortedBy(::timestampOf)
    }.getOrElse { error ->
        throw GitAccessException(ProtocolError.Internal("не удалось прочитать снапшоты", error.message))
    }

    /**
     * Удаляет ссылки; коммиты остаются в истории — снапшот это ссылка, а не ветка.
     *
     * Неудача на одной ссылке не прекращает уборку остальных: первый же сбой иначе оставлял бы
     * хвост неудалённых ссылок, и лимит соблюдался бы только на бумаге. Причины собираются
     * и бросаются после обхода — вызывающий видит, что уборка прошла не полностью.
     */
    fun delete(refs: List<String>) {
        val failed = mutableListOf<String>()
        refs.forEach { ref ->
            runCatching { deleteOne(ref) }.onFailure { error ->
                if (error is CancellationException) throw error
                logger.warn("Снапшот $ref не удалён: ${error.message}", error)
                failed += ref
            }
        }
        if (failed.isNotEmpty()) {
            throw GitAccessException(
                ProtocolError.Internal("не удалось удалить снапшоты", failed.joinToString()),
            )
        }
    }

    /** Удаляет одну ссылку; неудачный исход — [GitAccessException] наружу, а не тишина. */
    @Suppress("TooGenericExceptionCaught")
    private fun deleteOne(ref: String) {
        val result = runCatching {
            repository.updateRef(ref).apply { isForceUpdate = true }.delete()
        }.getOrElse { error ->
            if (error is CancellationException) throw error
            throw GitAccessException(ProtocolError.Internal("не удалось удалить снапшот $ref", error.message))
        }
        requireDeleted(ref, result)
    }

    /** Отказ постановки снапшота: причина — в журнал, наружу — значение-отказ. */
    private fun refused(ref: String, detail: String, cause: Throwable? = null): SnapshotOutcome {
        // Причина с исключением: без него стек теряется, и разбирать сбой нечем.
        if (cause == null) logger.warn("Снапшот $ref не поставлен: $detail")
        else logger.warn("Снапшот $ref не поставлен: $detail", cause)
        return SnapshotOutcome.Refused
    }

    /** Метка времени из имени ссылки: число до первого дефиса, как его собирает [snapshotRefName]. */
    private fun timestampOf(ref: String): Long =
        ref.removePrefix(SNAPSHOT_REF_PREFIX).substringBefore('-').toLongOrNull() ?: 0L

    private companion object {
        /** Исходы записи ссылки, означающие, что она записана; остальные — отказ. */
        val WRITTEN = setOf(RefUpdate.Result.NEW, RefUpdate.Result.FORCED, RefUpdate.Result.NO_CHANGE)
    }
}

/**
 * Отложенные правки пользователя в отдельной ссылке (T-1.59).
 *
 * Отдельный класс от [TaskBranchOperation] и [SnapshotOperation]: он один двигает рабочее
 * дерево и индекс (убирая правки из дерева и возвращая их), поэтому и предпроверки у него
 * свои — «откладывать нечего» и «ссылки на отложенное нет». Ссылка `refs/ai/stash/<task-id>`
 * лежит вне `refs/stash`: отложенное принадлежит задаче, а стек stash пользователя не трогается.
 */
private class StashOperation(private val repository: Repository, private val git: Git) : StashRepository {

    private val logger = LoggerFactory.getLogger(StashOperation::class.java)

    /**
     * Откладывает правки в ссылку [ref]; рабочее дерево после этого совпадает с HEAD.
     *
     * Без коммитов ссылаться не на что, а откладывать в никуда нельзя — возвращается
     * [StashOutcome.Nothing], и прогон дальше сам откажет по ветке (`NO_COMMITS`).
     * Чистое дерево — тоже [StashOutcome.Nothing]: откладывать нечего.
     */
    override fun stashEdits(ref: String, message: String): StashOutcome {
        if (runCatching { repository.resolve(Constants.HEAD) }.getOrNull() == null) {
            return StashOutcome.Nothing
        }
        return if (hasLocalChanges()) attempt(ref, message) else StashOutcome.Nothing
    }

    /**
     * Возвращает правки [ref] на ветку [branch] (null — остаться там, где стоим).
     *
     * Ссылка не найдена — отказ, а не успех: отложенное потеряно, и молчать об этом нельзя.
     * Конфликт с работой агента — [StashReturnOutcome.Conflict]: дерево остаётся с метками
     * конфликта, а ссылка — на месте, чтобы правки человека не пропали (§ 8.3, NFR-SAFE-2).
     */
    override fun returnStashEdits(ref: String, branch: String?): StashReturnOutcome {
        if (runCatching { repository.exactRef(ref) }.getOrNull() == null) {
            logger.warn("Отложенные правки не возвращены: ссылки $ref нет")
            return StashReturnOutcome.Refused
        }
        return attemptReturn(ref, branch)
    }

    @Suppress("TooGenericExceptionCaught")
    private fun attempt(ref: String, message: String): StashOutcome = try {
        val commit = git.stashCreate()
            .setIncludeUntracked(true)
            .setRef(ref)
            .setWorkingDirectoryMessage(message)
            .call()
        // null — откладывать оказалось нечего: так JGit сообщает о пустом наборе изменений.
        if (commit == null) StashOutcome.Nothing else StashOutcome.Stashed(ref, repository.branch)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        logger.warn("Правки не отложены в $ref: ${error.message}", error)
        StashOutcome.Refused
    }

    @Suppress("TooGenericExceptionCaught")
    private fun attemptReturn(ref: String, branch: String?): StashReturnOutcome = try {
        if (branch != null) git.checkout().setName(branch).call()
        git.stashApply().setStashRef(ref).setRestoreUntracked(true).call()
        // Ссылка убирается только после успешного возврата: при конфликте она — единственная
        // копия правок, и удалить её значит их потерять.
        drop(ref)
        StashReturnOutcome.Returned
    } catch (error: StashApplyFailureException) {
        logger.warn("Возврат отложенных правок конфликтует с работой агента ($ref)", error)
        StashReturnOutcome.Conflict
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: Exception) {
        logger.warn("Отложенные правки не возвращены ($ref): ${error.message}", error)
        StashReturnOutcome.Refused
    }

    /** Убирает ссылку отложенного после успешного возврата; неудачный исход — отказ. */
    private fun drop(ref: String) {
        val result = repository.updateRef(ref).apply { isForceUpdate = true }.delete()
        requireDeleted(ref, result)
    }

    /** Есть ли что откладывать: изменённые, новые, удалённые и конфликтные файлы рабочего дерева. */
    private fun hasLocalChanges(): Boolean = git.status().call().let { status ->
        listOf(
            status.added,
            status.changed,
            status.modified,
            status.removed,
            status.missing,
            status.conflicting,
            status.untracked,
        ).any { it.isNotEmpty() }
    }
}

/**
 * Означает ли исход `RefUpdate.delete()`, что ссылки больше нет.
 *
 * [RefUpdate.Result.FORCED] — ссылка была и удалена, [RefUpdate.Result.NEW] — её не было,
 * то есть она уже отсутствует. Остальные исходы ([RefUpdate.Result.LOCK_FAILURE],
 * [RefUpdate.Result.REJECTED], [RefUpdate.Result.IO_FAILURE]) означают, что ссылка могла
 * остаться, и выбрасывать их из вида нельзя: на этом держится лимит снапшотов.
 */
internal fun RefUpdate.Result.isDeleted(): Boolean =
    this == RefUpdate.Result.NEW || this == RefUpdate.Result.FORCED

/** Проверяет исход удаления и превращает неудачу в типизированный отказ доступа. */
internal fun requireDeleted(ref: String, result: RefUpdate.Result) {
    if (!result.isDeleted()) {
        throw GitAccessException(
            ProtocolError.Internal("не удалось удалить снапшот $ref", "исход ${result.name}"),
        )
    }
}

private fun notAGitRepository(workTree: Path): Nothing =
    throw GitAccessException(ProtocolError.NotAGitRepository(workTree.toString()))
