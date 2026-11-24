package dev.aide.host.diff

import dev.aide.domain.TextRange
import org.eclipse.jgit.diff.DiffAlgorithm
import org.eclipse.jgit.diff.Edit
import org.eclipse.jgit.diff.EditList
import org.eclipse.jgit.diff.RawText
import org.eclipse.jgit.diff.RawTextComparator

data class WordDiffResult(
    val oldRanges: List<TextRange> = emptyList(),
    val newRanges: List<TextRange> = emptyList(),
)

class WordDiffEngine {
    fun diff(oldText: String, newText: String): WordDiffResult {
        require(!oldText.contains('\n') && !oldText.contains('\r')) {
            "WordDiffEngine принимает только однострочные строки; oldText содержит перевод строки"
        }
        require(!newText.contains('\n') && !newText.contains('\r')) {
            "WordDiffEngine принимает только однострочные строки; newText содержит перевод строки"
        }
        if (oldText == newText) return WordDiffResult()
        val old = tokenize(oldText)
        val new = tokenize(newText)
        val edits = DiffAlgorithm.getAlgorithm(DiffAlgorithm.SupportedAlgorithm.HISTOGRAM)
            .diff(RawTextComparator.DEFAULT, old.asRawText(), new.asRawText())
        return WordDiffResult(
            oldRanges = old.rangesOrFullString(oldText, edits) { it.beginA to it.endA },
            newRanges = new.rangesOrFullString(newText, edits) { it.beginB to it.endB },
        )
    }
}

private data class Token(
    val text: String,
    val start: Int,
    val endExclusive: Int,
)

private fun tokenize(text: String): List<Token> =
    TOKEN.findAll(text).map { match ->
        Token(match.value, match.range.first, match.range.last + 1)
    }.toList()

private fun List<Token>.asRawText(): RawText =
    RawText(joinToString("\n") { it.text }.toByteArray(Charsets.UTF_8))

private fun List<Token>.rangesOrFullString(
    text: String,
    edits: EditList,
    bounds: (Edit) -> Pair<Int, Int>,
): List<TextRange> {
    if (text.isEmpty()) return emptyList()
    val changed = BooleanArray(size)
    edits.forEach { edit ->
        val (begin, end) = bounds(edit)
        for (index in begin until end.coerceAtMost(size)) {
            changed[index] = true
        }
    }
    val hasUnchangedContent = indices.any { index ->
        !changed[index] && !this[index].text.isBlank()
    }
    return if (hasUnchangedContent) {
        changedRanges(edits, bounds)
    } else {
        listOf(TextRange(0, text.length))
    }
}

private fun List<Token>.changedRanges(
    edits: EditList,
    bounds: (Edit) -> Pair<Int, Int>,
): List<TextRange> = edits.mapNotNull { edit ->
    val (begin, end) = bounds(edit)
    if (begin == end) {
        null
    } else {
        TextRange(this[begin].start, this[end - 1].endExclusive)
    }
}

private val TOKEN = Regex("""[\p{L}\p{N}\p{M}_]+|\s+|[^\p{L}\p{N}\p{M}_\s]""")
