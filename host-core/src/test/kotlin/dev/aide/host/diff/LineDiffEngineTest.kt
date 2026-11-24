package dev.aide.host.diff

import dev.aide.domain.FileChangeKind
import dev.aide.domain.HunkKind
import dev.aide.domain.LineKind
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

private fun fragments(text: String, ranges: List<dev.aide.domain.TextRange>): List<String> =
    ranges.map { text.substring(it.start, it.endExclusive) }

class LineDiffEngineTest {

    @Test
    fun `два далёких изменения дают два hunk-а`() {
        val change = LineDiffEngine().diff(
            TextDiffInput(
                "src/A.kt",
                "src/A.kt",
                text(30),
                text(30).replaced(5 to "five", 25 to "twenty-five"),
            ),
        )
        assertEquals(listOf(5, 25), change.hunks.map { it.startLine })
        assertEquals(listOf("src/A.kt:5-5", "src/A.kt:25-25"), change.hunks.map { it.id.value })
        assertEquals(listOf(2, 3, 4), change.hunks.first().lines.take(3).map { it.oldNumber })
        assertEquals(listOf(6, 7, 8), change.hunks.first().lines.takeLast(3).map { it.oldNumber })
        assertEquals(2, change.addedLines)
        assertEquals(2, change.removedLines)
    }

    @Test
    fun `близкие изменения объединяются без повторного контекста`() {
        val change = LineDiffEngine().diff(
            TextDiffInput(
                "src/A.kt",
                "src/A.kt",
                text(30),
                text(30).replaced(5 to "five", 10 to "ten"),
            ),
        )
        assertEquals(1, change.hunks.size)
        assertEquals(HunkKind.REPLACE, change.hunks.single().kind)
        assertEquals(
            listOf(6, 7, 8, 9),
            change.hunks.single().lines.filter {
                it.kind == LineKind.CONTEXT && it.oldNumber in 6..9
            }.map { it.oldNumber },
        )
        assertEquals(2, change.addedLines)
        assertEquals(2, change.removedLines)
        assertTrue(
            change.hunks.single().lines
                .filter { it.kind == LineKind.CONTEXT }
                .all { it.changedRanges.isEmpty() },
        )
    }

    @Test
    fun `добавление и удаление файла сохраняют нумерацию своей версии`() {
        val added = LineDiffEngine().diff(TextDiffInput("A.kt", "A.kt", null, "a\nb\n"))
        val deleted = LineDiffEngine().diff(TextDiffInput("A.kt", "A.kt", "a\nb\n", null))
        assertEquals(FileChangeKind.ADDED, added.changeKind)
        assertEquals(HunkKind.ADD, added.hunks.single().kind)
        assertEquals(listOf(1, 2), added.hunks.single().lines.map { it.newNumber })
        assertTrue(added.hunks.single().lines.all { it.oldNumber == null })
        assertNull(added.previousPath)
        assertEquals(FileChangeKind.DELETED, deleted.changeKind)
        assertEquals(HunkKind.REMOVE, deleted.hunks.single().kind)
        assertEquals(listOf(1, 2), deleted.hunks.single().lines.map { it.oldNumber })
        assertTrue(deleted.hunks.single().lines.all { it.newNumber == null })
    }

    @Test
    fun `пустые добавленный и удалённый файлы остаются видимыми`() {
        val added = LineDiffEngine().diff(TextDiffInput("A.kt", "A.kt", null, ""))
        val deleted = LineDiffEngine().diff(TextDiffInput("A.kt", "A.kt", "", null))
        assertEquals(1, added.hunks.size)
        assertEquals(1, deleted.hunks.size)
        assertEquals(HunkKind.ADD, added.hunks.single().kind)
        assertEquals(HunkKind.REMOVE, deleted.hunks.single().kind)
        assertTrue(added.hunks.single().lines.isEmpty())
        assertTrue(deleted.hunks.single().lines.isEmpty())
        assertEquals(0, added.addedLines + added.removedLines)
        assertEquals(0, deleted.addedLines + deleted.removedLines)
    }

    @Test
    fun `переименование сохраняет прежний путь с правкой и без`() {
        val pure = LineDiffEngine().diff(TextDiffInput("Old.kt", "New.kt", "same\n", "same\n"))
        val changed = LineDiffEngine().diff(TextDiffInput("Old.kt", "New.kt", "old\n", "new\n"))
        assertEquals(FileChangeKind.RENAMED, pure.changeKind)
        assertEquals("Old.kt", pure.previousPath)
        assertTrue(pure.hunks.isEmpty())
        assertEquals(0, pure.addedLines + pure.removedLines)
        assertEquals("Old.kt", changed.previousPath)
        assertEquals("new", changed.hunks.single().lines.single { it.kind == LineKind.ADDED }.text)
    }

    @Test
    fun `повторный и последующий вызовы не меняют результат`() {
        val engine = LineDiffEngine()
        val input = TextDiffInput("A.kt", "A.kt", "a\nb\n", "a\nc\n")
        val first = engine.diff(input)
        assertEquals(first, engine.diff(input))
        engine.diff(TextDiffInput("B.kt", "B.kt", "x\n", "y\n"))
        assertEquals(first, engine.diff(input))
    }

    @Test
    fun `word-diff диапазоны передаются в строки hunk-а`() {
        val change = LineDiffEngine().diff(
            TextDiffInput(
                "A.kt",
                "A.kt",
                "val total = first + second\n",
                "val sum = first - second\n",
            ),
        )
        assertEquals(1, change.hunks.size)
        val hunk = change.hunks.single()
        val removed = hunk.lines.single { it.kind == LineKind.REMOVED }
        val added = hunk.lines.single { it.kind == LineKind.ADDED }
        assertEquals(listOf("total", "+"), fragments(removed.text, removed.changedRanges))
        assertEquals(listOf("sum", "-"), fragments(added.text, added.changedRanges))
    }

    @Test
    fun `один edit с разным числом строк корректно сопоставляет по смещению`() {
        val change = LineDiffEngine().diff(
            TextDiffInput(
                "A.kt",
                "A.kt",
                "alpha beta\nsecond removed\nomega\n",
                "alpha gamma\nomega\n",
            ),
        )
        assertEquals(1, change.hunks.size)
        val hunk = change.hunks.single()
        val removed = hunk.lines.filter { it.kind == LineKind.REMOVED }
        val added = hunk.lines.filter { it.kind == LineKind.ADDED }
        assertEquals(2, removed.size)
        assertEquals(1, added.size)
        assertEquals(listOf("beta"), fragments(removed[0].text, removed[0].changedRanges))
        assertEquals(listOf("gamma"), fragments(added[0].text, added[0].changedRanges))
        assertEquals(listOf("second removed"), fragments(removed[1].text, removed[1].changedRanges))
    }
}

private fun text(count: Int): String =
    (1..count).joinToString("\n", postfix = "\n") { "line-$it" }

private fun String.replaced(vararg replacements: Pair<Int, String>): String {
    val result = removeSuffix("\n").lines().toMutableList()
    replacements.forEach { (number, replacement) -> result[number - 1] = replacement }
    return result.joinToString("\n", postfix = "\n")
}
