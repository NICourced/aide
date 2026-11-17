package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.protocol.ProtocolError
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.api.ResetCommand.ResetType
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.PersonIdent
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevCommit
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.treewalk.CanonicalTreeParser
import org.slf4j.LoggerFactory

/**
 * Реализация отката к снапшоту через JGit (T-1.6).
 *
 * Отдельный класс от [RestoreOperation]: восстановление при стопе двигает ветку назад,
 * а откат — обратный коммит, сохраняющий историю (NFR-SAFE-5).
 */
internal class SnapshotRollbackOperation(
    private val repository: Repository,
    private val git: Git,
    private val changedFiles: () -> List<ChangedFile>,
) : SnapshotRollbackRepository {

    internal val logger = LoggerFactory.getLogger(SnapshotRollbackOperation::class.java)

    /**
     * Hook для тестов: вызывается после создания объекта обратного коммита и перед
     * `git reset`, чтобы доказать, что до единого reset HEAD не сдвинут.
     */
    internal var beforeResetHook: () -> Unit = {}

    /**
     * Снапшоты — только предки текущего HEAD, от новых к старым по метке ссылки.
     *
     * Неизвестная метка в имени ссылки пропускается: такая ссылка не может быть
     * снапшотом, созданным известным поводом, и подмена на `BEFORE_AGENT_STEP`
     * обманывала бы пользователя.
     */
    override fun list(): List<Snapshot> = runCatching {
        val head = resolveHead() ?: return@runCatching emptyList()
        repository.refDatabase.getRefsByPrefix(SNAPSHOT_REF_PREFIX)
            .mapNotNull { ref ->
                val parsed = parseSnapshotRefOrNull(ref.name)
                if (parsed == null) {
                    logger.warn("Некорректное имя ссылки снапшота: ${ref.name}")
                    return@mapNotNull null
                }
                val commitId = ref.objectId ?: return@mapNotNull null
                val snapshotCommit = runCatching { repository.parseCommit(commitId) }.getOrNull()
                    ?: return@mapNotNull null
                val trigger = SnapshotTrigger.entries.find {
                    it.name == parsed.label.uppercase().replace('-', '_')
                }
                if (trigger == null) {
                    logger.warn("Неизвестная метка снапшота: ${parsed.label} в ${ref.name}")
                    return@mapNotNull null
                }
                if (!isAncestor(snapshotCommit, head)) return@mapNotNull null
                snapshotFromRef(ref.name, commitId.name, parsed, trigger)
            }
            .sortedByDescending { it.ref.value }
    }.getOrElse { error ->
        if (error is CancellationException) throw error
        throw GitAccessException(ProtocolError.Internal("не удалось прочитать снапшоты", error.message))
    }

    override fun preview(ref: SnapshotRef): SnapshotRollbackOutcome<SnapshotPreview> = runCatching {
        when (val prepared = prepare(ref)) {
            is SnapshotRollbackOutcome.Success -> {
                val files = diff(prepared.value.snapshotCommit.tree.id, prepared.value.head.tree.id)
                    .map { it.toSnapshotFileChange() }
                    .sortedBy { it.path }
                SnapshotRollbackOutcome.Success(
                    SnapshotPreview(
                        snapshot = snapshotFromRef(ref.value, prepared.value.snapshotCommit),
                        headCommit = prepared.value.head.name,
                        files = files,
                    ),
                )
            }

            is SnapshotRollbackOutcome.Refused -> prepared
        }
    }.getOrElse { error ->
        gitFailed("Предпросмотр снапшота ${ref.value} не удался", error)
    }

    override fun revert(
        ref: SnapshotRef,
        expectedHead: String,
    ): SnapshotRollbackOutcome<SnapshotRevertCommitted> = runCatching {
        when (val prepared = prepare(ref, expectedHead)) {
            is SnapshotRollbackOutcome.Success -> {
                val newCommitId = createReverseCommit(ref, prepared.value.snapshotCommit, prepared.value.head)
                beforeResetHook()
                git.reset().setMode(ResetType.HARD).setRef(newCommitId.name).call()
                SnapshotRollbackOutcome.Success(SnapshotRevertCommitted(newCommitId.name))
            }

            is SnapshotRollbackOutcome.Refused -> prepared
        }
    }.getOrElse { error ->
        gitFailed("Откат к снапшоту ${ref.value} не удался", error)
    }

    /** Общая проверка для предпросмотра и отката: ссылка, метка, дерево, предок, HEAD. */
    private fun prepare(
        ref: SnapshotRef,
        expectedHead: String? = null,
    ): SnapshotRollbackOutcome<Prepared> {
        val snapshotCommit = resolveSnapshot(ref.value)
        val head = resolveHead()
        val parsed = parseSnapshotRefOrNull(ref.value)
        val trigger = parsed?.let { parseSnapshotTrigger(it) }
        val outcome = when {
            snapshotCommit == null || parsed == null || trigger == null ->
                refused(SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT)
            changedFiles().isNotEmpty() -> refused(SnapshotRollbackRefusal.DIRTY_WORKTREE)
            head == null -> refused(SnapshotRollbackRefusal.GIT_FAILED)
            !isAncestor(snapshotCommit, head) -> refused(SnapshotRollbackRefusal.NOT_IN_CURRENT_BRANCH)
            snapshotCommit.tree.id == head.tree.id -> refused(SnapshotRollbackRefusal.NOTHING_TO_REVERT)
            expectedHead != null && head.name != expectedHead -> refused(SnapshotRollbackRefusal.HEAD_CHANGED)
            else -> SnapshotRollbackOutcome.Success(Prepared(snapshotCommit, head))
        }
        return outcome
    }

    private data class Prepared(
        val snapshotCommit: RevCommit,
        val head: RevCommit,
    )

    /** Валидирует имя ссылки и возвращает коммит, на который она указывает. */
    private fun resolveSnapshot(ref: String): RevCommit? = when {
        !ref.startsWith(SNAPSHOT_REF_PREFIX) -> null
        else -> runCatching { repository.resolve(ref) }.getOrNull()?.let { objectId ->
            runCatching { repository.parseCommit(objectId) }.getOrNull()
        }
    }

    /** Текущий HEAD как объект коммита. */
    private fun resolveHead(): RevCommit? = runCatching {
        val headId = repository.resolve(Constants.HEAD) ?: return null
        repository.parseCommit(headId)
    }.getOrNull()


    /** Является ли [ancestor] предком [descendant] (или равен ему). */
    private fun isAncestor(
        ancestor: RevCommit,
        descendant: RevCommit,
    ): Boolean = runCatching {
        RevWalk(repository).use { walk ->
            val a = walk.parseCommit(ancestor.id)
            val d = walk.parseCommit(descendant.id)
            walk.isMergedInto(a, d)
        }
    }.getOrDefault(false)

    /** Создаёт объект обратного коммита: дерево снапшота, родитель — текущий HEAD. */
    private fun createReverseCommit(
        ref: SnapshotRef,
        snapshotCommit: RevCommit,
        head: RevCommit,
    ): ObjectId {
        val parsed = parseSnapshotRef(ref.value)
        val author = readPersonIdent()
        val committer = author
        val message = "Откат к снапшоту ${parsed.epochMillis}-${parsed.label}"
        return repository.newObjectInserter().use { inserter ->
            val treeId = snapshotCommit.tree.id
            val commitBuilder = org.eclipse.jgit.lib.CommitBuilder().apply {
                setTreeId(treeId)
                setParentIds(head)
                setAuthor(author)
                setCommitter(committer)
                setMessage(message)
            }
            val commitId = inserter.insert(commitBuilder)
            inserter.flush()
            commitId
        }
    }

    /** Автор обратного коммита — человек из git-config; при отсутствии — фиксированная личность. */
    private fun readPersonIdent(): PersonIdent {
        val config = repository.config
        val name = config.getString("user", null, "name")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_NAME
        val email = config.getString("user", null, "email")?.takeIf { it.isNotBlank() } ?: DEFAULT_USER_EMAIL
        return PersonIdent(name, email)
    }

    /** Diff деревьев с детекцией переименований и подсчётом строк. */
    private fun diff(oldTree: ObjectId, newTree: ObjectId): List<RawFileDiff> {
        val stream = ByteArrayOutputStream()
        repository.newObjectReader().use { reader ->
            val oldParser = CanonicalTreeParser().apply { reset(reader, oldTree) }
            val newParser = CanonicalTreeParser().apply { reset(reader, newTree) }
            DiffFormatter(stream).use { formatter ->
                formatter.setRepository(repository)
                formatter.setDetectRenames(true)
                val entries = formatter.scan(oldParser, newParser)
                return entries.map { entry ->
                    val header = formatter.toFileHeader(entry)
                    val edits = header.toEditList()
                    RawFileDiff(
                        path = entry.newPath.takeIf { it != DiffEntry.DEV_NULL } ?: entry.oldPath,
                        previousPath = entry.oldPath.takeIf { it != DiffEntry.DEV_NULL && it != entry.newPath },
                        changeKind = entry.changeType.toFileChangeKind(),
                        addedLines = edits.sumOf { it.lengthB },
                        removedLines = edits.sumOf { it.lengthA },
                    )
                }
            }
        }
    }

    private companion object {
        const val DEFAULT_USER_NAME = "aide-user"
        const val DEFAULT_USER_EMAIL = "user@aide.local"
    }
}

/** Разбирает имя ссылки на метку времени и метку повода. */
private fun parseSnapshotRef(ref: String): ParsedSnapshotRef {
    val body = ref.removePrefix(SNAPSHOT_REF_PREFIX)
    val separator = body.indexOf('-')
    require(separator > 0) { "неверный формат ссылки снапшота: $ref" }
    val epochMillis = body.substring(0, separator).toLongOrNull()
        ?: error("неверная метка времени в ссылке снапшота: $ref")
    val label = body.substring(separator + 1)
    return ParsedSnapshotRef(epochMillis, label)
}

private data class ParsedSnapshotRef(val epochMillis: Long, val label: String)

/** Безопасно разбирает имя ссылки; malformed refs возвращают `null`. */
private fun parseSnapshotRefOrNull(ref: String): ParsedSnapshotRef? = runCatching {
    parseSnapshotRef(ref)
}.getOrNull()

/** Распознаёт метку ссылки как известный повод снапшота. */
private fun parseSnapshotTrigger(parsed: ParsedSnapshotRef): SnapshotTrigger? =
    SnapshotTrigger.entries.find {
        it.name == parsed.label.uppercase().replace('-', '_')
    }

/** Строит [Snapshot] по существующей ссылке и коммиту. */
private fun snapshotFromRef(ref: String, commit: RevCommit): Snapshot {
    val parsed = parseSnapshotRef(ref)
    val trigger = SnapshotTrigger.entries.find {
        it.name == parsed.label.uppercase().replace('-', '_')
    } ?: error("неизвестная метка снапшота: ${parsed.label}")
    return snapshotFromRef(ref, commit.name, parsed, trigger)
}

private fun snapshotFromRef(
    ref: String,
    commit: String,
    parsed: ParsedSnapshotRef,
    trigger: SnapshotTrigger,
): Snapshot = Snapshot(
    ref = SnapshotRef(ref),
    label = parsed.label,
    commit = commit,
    trigger = trigger,
    createdAt = Instant.fromEpochMilliseconds(parsed.epochMillis),
)

private fun RawFileDiff.toSnapshotFileChange(): SnapshotFileChange = SnapshotFileChange(
    path = path,
    previousPath = previousPath,
    changeKind = changeKind,
    addedLines = addedLines,
    removedLines = removedLines,
)

private fun DiffEntry.ChangeType.toFileChangeKind(): FileChangeKind = when (this) {
    DiffEntry.ChangeType.ADD -> FileChangeKind.ADDED
    DiffEntry.ChangeType.DELETE -> FileChangeKind.DELETED
    DiffEntry.ChangeType.MODIFY -> FileChangeKind.MODIFIED
    DiffEntry.ChangeType.RENAME -> FileChangeKind.RENAMED
    DiffEntry.ChangeType.COPY -> FileChangeKind.ADDED
}

private fun refused(reason: SnapshotRollbackRefusal): SnapshotRollbackOutcome<Nothing> =
    SnapshotRollbackOutcome.Refused(reason)

private fun SnapshotRollbackOperation.gitFailed(
    context: String,
    error: Throwable,
): SnapshotRollbackOutcome<Nothing> {
    if (error is CancellationException) throw error
    logger.warn("$context: ${error.message}", error)
    return refused(SnapshotRollbackRefusal.GIT_FAILED)
}
