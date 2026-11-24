package dev.aide.host.diff

import dev.aide.domain.TextRange
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class WordDiffEngineTest {

    @Test
    fun `несколько правок в строке перечисляются отдельно`() {
        val old = "val total = first + second"
        val new = "val sum = first - second"
        val result = WordDiffEngine().diff(old, new)
        assertEquals(listOf("total", "+"), fragments(old, result.oldRanges))
        assertEquals(listOf("sum", "-"), fragments(new, result.newRanges))
        assertValidRanges(old, result.oldRanges)
        assertValidRanges(new, result.newRanges)
    }

    @Test
    fun `полная замена покрывает каждую непустую сторону целиком`() {
        val result = WordDiffEngine().diff("old value", "новое значение")
        assertEquals(listOf(TextRange(0, 9)), result.oldRanges)
        assertEquals(listOf(TextRange(0, 14)), result.newRanges)
        assertValidRanges("old value", result.oldRanges)
        assertValidRanges("новое значение", result.newRanges)
    }

    @Test
    fun `добавление слова не отмечает несуществующий старый фрагмент`() {
        val result = WordDiffEngine().diff("a b", "a new b")
        assertTrue(result.oldRanges.isEmpty())
        assertEquals(listOf("new "), fragments("a new b", result.newRanges))
        assertValidRanges("a b", result.oldRanges)
        assertValidRanges("a new b", result.newRanges)
    }

    @Test
    fun `unicode и отсутствие перевода строки сохраняют точные границы`() {
        val old = "val greeting = \"привет\""
        val new = "val greeting = \"пока\""
        val result = WordDiffEngine().diff(old, new)
        assertEquals(listOf("привет"), fragments(old, result.oldRanges))
        assertEquals(listOf("пока"), fragments(new, result.newRanges))
        assertValidRanges(old, result.oldRanges)
        assertValidRanges(new, result.newRanges)
    }

    @Test
    fun `одинаковые строки не имеют изменённых диапазонов`() {
        val result = WordDiffEngine().diff("same", "same")
        assertEquals(WordDiffResult(), result)
        assertValidRanges("same", result.oldRanges)
        assertValidRanges("same", result.newRanges)
    }

    @Test
    fun `многострочный вход запрещён`() {
        assertFailsWith<IllegalArgumentException> {
            WordDiffEngine().diff("a\nb", "a b")
        }
        assertFailsWith<IllegalArgumentException> {
            WordDiffEngine().diff("a b", "a\rb")
        }
    }

    @Test
    fun `пустая сторона покрывает только непустую`() {
        val result = WordDiffEngine().diff("", "x")
        assertTrue(result.oldRanges.isEmpty())
        assertEquals(listOf("x"), fragments("x", result.newRanges))
        assertValidRanges("", result.oldRanges)
        assertValidRanges("x", result.newRanges)
    }

    @Test
    fun `whitespace-only изменения покрывают только пробелы`() {
        val old = "a  b"
        val new = "a b"
        val result = WordDiffEngine().diff(old, new)
        assertEquals(listOf("  "), fragments(old, result.oldRanges))
        assertEquals(listOf(" "), fragments(new, result.newRanges))
        assertValidRanges(old, result.oldRanges)
        assertValidRanges(new, result.newRanges)
    }

    @Test
    fun `изменение на границе слова покрывает целые токены`() {
        val result = WordDiffEngine().diff("abc", "xabc")
        assertEquals(listOf("abc"), fragments("abc", result.oldRanges))
        assertEquals(listOf("xabc"), fragments("xabc", result.newRanges))
        assertValidRanges("abc", result.oldRanges)
        assertValidRanges("xabc", result.newRanges)
    }
}

private fun fragments(text: String, ranges: List<TextRange>): List<String> =
    ranges.map { text.substring(it.start, it.endExclusive) }

private fun assertValidRanges(text: String, ranges: List<TextRange>) {
    ranges.forEach { range ->
        assertTrue(range.start >= 0, "start должен быть >= 0")
        assertTrue(range.endExclusive <= text.length, "endExclusive должен быть <= длины строки")
        assertTrue(range.start < range.endExclusive, "диапазон не должен быть пустым")
    }
    for (index in 1 until ranges.size) {
        assertTrue(
            ranges[index].start >= ranges[index - 1].endExclusive,
            "диапазоны должны быть отсортированы и не пересекаться",
        )
    }
}
