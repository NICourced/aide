package dev.aide.host.diff

import dev.aide.domain.ContextGap
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.RiskLevel
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

data class TextDiffInput(
    val oldPath: String,
    val newPath: String,
    val oldText: String?,
    val newText: String?,
)

class LineDiffEngine {
    fun diff(input: TextDiffInput): FileChange {
        require(input.oldText != null || input.newText != null) { "Обе версии файла отсутствуют" }
        require(input.oldText != input.newText || input.oldPath != input.newPath) { "Файл не изменился" }
        val old = input.oldText.asRawText()
        val new = input.newText.asRawText()
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, old, new)
        val changeKind = input.changeKind()
        val batch = HunkBuilder(input.newPath, old, new).build(edits, changeKind)
        return FileChange(
            path = input.newPath,
            previousPath = input.oldPath.takeIf { it != input.newPath },
            changeKind = changeKind,
            hunks = batch.hunks,
            contextGaps = batch.contextGaps,
            addedLines = edits.sumOf { it.lengthB },
            removedLines = edits.sumOf { it.lengthA },
        )
    }
}

private data class HunkBatch(
    val hunks: List<Hunk>,
    val contextGaps: List<ContextGap>,
)

private class HunkBuilder(
    private val path: String,
    private val old: RawText,
    private val new: RawText,
) {
    private val wordDiff = WordDiffEngine()

    fun build(edits: EditList, changeKind: FileChangeKind): HunkBatch {
        if (edits.isEmpty()) {
            val hunks = when (changeKind) {
                FileChangeKind.ADDED -> listOf(emptyHunk(HunkKind.ADD))
                FileChangeKind.DELETED -> listOf(emptyHunk(HunkKind.REMOVE))
                else -> emptyList()
            }
            return HunkBatch(hunks, contextGaps = emptyList())
        }
        val groups = edits.grouped()
        return HunkBatch(groups.map(::buildGroup), contextGaps(groups))
    }

    private fun contextGaps(groups: List<List<Edit>>): List<ContextGap> {
        val cursor = ContextCursor()
        val gaps = mutableListOf<ContextGap>()
        groups.forEach { group ->
            val first = group.first()
            val oldStart = (first.beginA - CONTEXT_LINES).coerceAtLeast(0)
            if (oldStart > cursor.old) {
                gaps += ContextGap(cursor.old + 1, cursor.new + 1, oldStart - cursor.old)
            }
            val last = group.last()
            cursor.old = (last.endA + CONTEXT_LINES).coerceAtMost(old.size())
            cursor.new = (last.endB + CONTEXT_LINES).coerceAtMost(new.size())
        }
        if (cursor.old < old.size()) {
            gaps += ContextGap(cursor.old + 1, cursor.new + 1, old.size() - cursor.old)
        }
        return gaps
    }

    private data class ContextCursor(var old: Int = 0, var new: Int = 0)

    private fun EditList.grouped(): List<List<Edit>> {
        val groups = mutableListOf<MutableList<Edit>>()
        forEach { edit ->
            val previous = groups.lastOrNull()?.lastOrNull()
            val overlaps = previous != null &&
                edit.beginA - previous.endA <= CONTEXT_LINES * 2 &&
                edit.beginB - previous.endB <= CONTEXT_LINES * 2
            if (overlaps) groups.last().add(edit) else groups.add(mutableListOf(edit))
        }
        return groups
    }

    private fun buildGroup(group: List<Edit>): Hunk {
        val first = group.first()
        val last = group.last()
        val endA = (last.endA + CONTEXT_LINES).coerceAtMost(old.size())
        val endB = (last.endB + CONTEXT_LINES).coerceAtMost(new.size())
        val lines = mutableListOf<HunkLine>()
        var a = (first.beginA - CONTEXT_LINES).coerceAtLeast(0)
        var b = (first.beginB - CONTEXT_LINES).coerceAtLeast(0)
        group.forEach { edit ->
            while (a < edit.beginA) {
                lines += HunkLine(LineKind.CONTEXT, a + 1, b + 1, old.getString(a))
                a += 1
                b += 1
            }
            lines += changedLines(edit)
            a = edit.endA
            b = edit.endB
        }
        while (a < endA) {
            lines += HunkLine(LineKind.CONTEXT, a + 1, b + 1, old.getString(a))
            a += 1
            b += 1
        }
        check(b == endB) { "Курсоры версий разошлись" }
        return hunk(group, lines)
    }

    private fun changedLines(edit: Edit): List<HunkLine> {
        val count = maxOf(edit.lengthA, edit.lengthB)
        val diffs = (0 until count).map { offset ->
            val oldIndex = edit.beginA + offset
            val newIndex = edit.beginB + offset
            wordDiff.diff(
                if (oldIndex < edit.endA) old.getString(oldIndex) else "",
                if (newIndex < edit.endB) new.getString(newIndex) else "",
            )
        }
        return buildList {
            for (index in edit.beginA until edit.endA) {
                add(
                    HunkLine(
                        kind = LineKind.REMOVED,
                        oldNumber = index + 1,
                        text = old.getString(index),
                        changedRanges = diffs[index - edit.beginA].oldRanges,
                    ),
                )
            }
            for (index in edit.beginB until edit.endB) {
                add(
                    HunkLine(
                        kind = LineKind.ADDED,
                        newNumber = index + 1,
                        text = new.getString(index),
                        changedRanges = diffs[index - edit.beginB].newRanges,
                    ),
                )
            }
        }
    }

    private fun hunk(group: List<Edit>, lines: List<HunkLine>): Hunk {
        val first = group.first()
        val removed = group.sumOf { it.lengthA }
        val added = group.sumOf { it.lengthB }
        return Hunk(
            id = HunkId("$path:${first.beginA + 1}-${first.beginB + 1}"),
            filePath = path,
            startLine = first.beginA + 1,
            kind = when {
                removed == 0 -> HunkKind.ADD
                added == 0 -> HunkKind.REMOVE
                else -> HunkKind.REPLACE
            },
            risk = RiskLevel.NORMAL,
            lines = lines,
        )
    }

    private fun emptyHunk(kind: HunkKind): Hunk = Hunk(
        id = HunkId("$path:1-1"),
        filePath = path,
        startLine = 1,
        kind = kind,
        risk = RiskLevel.NORMAL,
        lines = emptyList(),
    )

    private companion object {
        const val CONTEXT_LINES = 3
    }
}

private fun TextDiffInput.changeKind(): FileChangeKind = when {
    oldText == null -> FileChangeKind.ADDED
    newText == null -> FileChangeKind.DELETED
    oldPath != newPath -> FileChangeKind.RENAMED
    else -> FileChangeKind.MODIFIED
}

private fun String?.asRawText(): RawText =
    this?.let { RawText(it.toByteArray(Charsets.UTF_8)) } ?: RawText.EMPTY_TEXT
