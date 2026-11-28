package dev.aide.host.git

import dev.aide.host.diff.TextDiffInput
import dev.aide.protocol.ContextLinePayload
import dev.aide.protocol.ProtocolError
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStream
import org.eclipse.jgit.diff.DiffEntry
import org.eclipse.jgit.diff.DiffFormatter
import org.eclipse.jgit.lib.Constants
import org.eclipse.jgit.lib.ObjectId
import org.eclipse.jgit.lib.ObjectLoader
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.revwalk.RevTree
import org.eclipse.jgit.revwalk.RevWalk
import org.eclipse.jgit.revwalk.filter.RevFilter
import org.eclipse.jgit.treewalk.TreeWalk

/**
 * Чтение diff'а между базовой и задачной ветками через деревья коммитов (T-1.24).
 *
 * Рабочее дерево и индекс не трогаются: все версии читаются из blob'ов.
 * Неподдерживаемые файлы (бинарные, не-UTF-8, больше лимита) отклоняют сборку пакета.
 */
class PacketDiffOperation(private val repository: Repository) : ChangeSetRepository {

    private val blobs = BlobReader(repository)

    override fun read(baseBranch: String, taskBranch: String): GitChangeSet {
        val baseId = resolveOrThrow(baseBranch)
        val headId = resolveOrThrow(taskBranch)
        val mergeBase = mergeBase(baseId, headId)
        val baseCommit = fullName(mergeBase)
        val headCommit = fullName(headId)

        val inputs = RevWalk(repository).use { walk ->
            val baseTree = walk.parseCommit(mergeBase).tree
            val headTree = walk.parseCommit(headId).tree
            diffEntries(baseTree, headTree).map { entry ->
                TextDiffInput(
                    oldPath = entry.oldPath.takeIf { it != DEV_NULL } ?: entry.newPath,
                    newPath = entry.newPath.takeIf { it != DEV_NULL } ?: entry.oldPath,
                    oldText = blobs.text(entry.oldId.toObjectId()),
                    newText = blobs.text(entry.newId.toObjectId()),
                )
            }
        }

        return GitChangeSet(baseCommit = baseCommit, headCommit = headCommit, files = inputs)
    }

    override fun readContext(commit: String, path: String, startLine: Int, lineCount: Int): List<ContextLinePayload> {
        requireValidRange(startLine, lineCount)
        val blobId = resolveBlob(commit, path) ?: return emptyList()
        return blobs.lines(blobId, startLine, lineCount)
    }

    private fun requireValidRange(startLine: Int, lineCount: Int) {
        if (startLine < 1 || lineCount < 1 || lineCount > MAX_CONTEXT_LINES) {
            throw GitAccessException(ProtocolError.InvalidDiffRange(startLine, lineCount))
        }
    }

    private fun resolveBlob(commit: String, path: String): ObjectId? = runCatching {
        val commitId = repository.resolve(commit) ?: return@runCatching null
        RevWalk(repository).use { walk ->
            val tree = walk.parseCommit(commitId).tree
            TreeWalk.forPath(repository, path, tree)?.getObjectId(0)
        }
    }.getOrNull()

    private fun resolveOrThrow(ref: String): ObjectId {
        val id = runCatching { repository.resolve(ref) }.getOrNull()
        if (id == null || ObjectId.zeroId() == id) {
            throw GitAccessException(ProtocolError.Internal("ссылка не разбирается", ref))
        }
        return id
    }

    private fun mergeBase(base: ObjectId, head: ObjectId): ObjectId {
        return RevWalk(repository).use { walk ->
            walk.revFilter = RevFilter.MERGE_BASE
            walk.markStart(walk.parseCommit(base))
            walk.markStart(walk.parseCommit(head))
            val commit = walk.next()
                ?: throw GitAccessException(ProtocolError.Internal("merge-base не найден", "$base..$head"))
            commit.toObjectId()
        }
    }

    private fun fullName(id: ObjectId): String = id.name

    private fun diffEntries(baseTree: RevTree, headTree: RevTree): List<DiffEntry> {
        return DiffFormatter(OutputStream.nullOutputStream()).use { formatter ->
            formatter.setRepository(repository)
            formatter.setDetectRenames(true)
            formatter.scan(baseTree, headTree)
                .filterNot { entry -> entry.changeType == DiffEntry.ChangeType.COPY }
        }
    }

    private companion object {
        private const val DEV_NULL = "/dev/null"
        private const val MAX_CONTEXT_LINES: Int = 200
    }
}

private class BlobReader(private val repository: Repository) {

    fun text(id: ObjectId): String? {
        if (ObjectId.zeroId() == id) return null
        val loader = openBlob(id) ?: throw GitAccessException(
            ProtocolError.Internal("не удалось открыть blob", id.name),
        )
        if (loader.size > MAX_BLOB_BYTES) {
            throw GitAccessException(
                ProtocolError.Internal(
                    "файл превышает лимит $MAX_BLOB_BYTES байт",
                    "размер ${loader.size}",
                ),
            )
        }
        return readText(loader)
    }

    fun lines(blobId: ObjectId, startLine: Int, lineCount: Int): List<ContextLinePayload> {
        val loader = openBlob(blobId) ?: return emptyList()
        return loader.openStream().use { stream ->
            BufferedReader(InputStreamReader(stream, Charsets.UTF_8)).use { reader ->
                var number = 1
                while (number < startLine && reader.readLine() != null) {
                    number += 1
                }
                val lines = mutableListOf<ContextLinePayload>()
                while (lines.size < lineCount) {
                    val text = reader.readLine() ?: break
                    lines += ContextLinePayload(number, text)
                    number += 1
                }
                lines
            }
        }
    }

    private fun readText(loader: ObjectLoader): String {
        val bytes = loader.cachedBytes
        if (bytes.contains(0)) {
            throw GitAccessException(ProtocolError.Internal("бинарные файлы в пакете не поддерживаются", null))
        }
        return decodeUtf8(bytes)
            ?: throw GitAccessException(ProtocolError.Internal("файл не в UTF-8", null))
    }

    private fun openBlob(id: ObjectId): ObjectLoader? = runCatching {
        repository.open(id, Constants.OBJ_BLOB)
    }.getOrNull()

    private fun decodeUtf8(bytes: ByteArray): String? = runCatching {
        String(bytes, Charsets.UTF_8)
    }.getOrNull()

    private companion object {
        private const val MAX_BLOB_BYTES: Long = 512 * 1024L
    }
}
