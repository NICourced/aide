package dev.aide.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.10: слияние частей отчёта прогона (решение 8) и признак обрезки (решение 6).
 *
 * Проверяется наблюдаемый факт: обновление тестов заменяет тестовую часть и не стирает
 * замечания линтера, обновление линтера — наоборот; признак обрезки у каждой части свой
 * и сбрасывается вместе с заменой своей части, а не остаётся «липким».
 */
class TestReportTest {

    private val tests = TestReport(
        state = TestState.RED,
        failures = listOf(TestFailure("dev.aide.SampleTest.падает", "src/test/.../SampleTest.kt")),
    )

    private val lint = TestReport(
        lintFindings = listOf(LintFinding("src/main/App.kt", line = 4, rule = "detekt.MagicNumber")),
    )

    @Test
    fun `обновление линтера не стирает падения тестов`() {
        val merged = tests.merge(lint)

        assertEquals(TestState.RED, merged.state, "тестовая часть обязана остаться")
        assertEquals(tests.failures, merged.failures)
        assertEquals(lint.lintFindings, merged.lintFindings)
    }

    @Test
    fun `обновление тестов не стирает замечания линтера`() {
        val merged = lint.merge(tests)

        assertEquals(lint.lintFindings, merged.lintFindings, "замечания обязаны остаться")
        assertEquals(TestState.RED, merged.state)
        assertEquals(tests.failures, merged.failures)
    }

    @Test
    fun `чистый прогон тестов сбрасывает свою пометку обрезки, не трогая пометку линтера`() {
        val truncatedLint = lint.copy(lintFindings = listOf(LintFinding("a.kt")), lintTruncated = true)
        val truncatedTests = tests.copy(failuresTruncated = true)

        val merged = truncatedTests.merge(truncatedLint).merge(TestReport(state = TestState.GREEN))

        assertEquals(TestState.GREEN, merged.state)
        assertFalse(merged.failuresTruncated, "новый прогон тестов снял свою пометку")
        assertTrue(merged.lintTruncated, "пометка линтера осталась: его никто не переписывал")
        assertTrue(merged.truncated)
    }

    @Test
    fun `обрезка тестов и линтера считается раздельно`() {
        val onlyLint = TestReport(lintFindings = listOf(LintFinding("a.kt"))).copy(lintTruncated = true)
        val onlyTests = TestReport(state = TestState.RED).copy(failuresTruncated = true)

        assertFalse(onlyLint.failuresTruncated)
        assertTrue(onlyLint.truncated)
        assertFalse(onlyTests.lintTruncated)
        assertTrue(onlyTests.truncated)
    }

    @Test
    fun `пустой отчёт не несёт ни состояния, ни частей`() {
        val empty = TestReport()

        assertNull(empty.state)
        assertNull(empty.lintFindings)
        assertEquals(TestState.NOT_RUN, empty.reportState())
        assertFalse(empty.truncated)
    }
}
