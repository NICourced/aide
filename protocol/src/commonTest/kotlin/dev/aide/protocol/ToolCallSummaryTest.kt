package dev.aide.protocol

import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.3: сводка страницы несёт поля записи и обрезанные превью с признаком обрезки.
 *
 * Без обрезки страница из полусотни полных результатов (`read_file`, `run_command`)
 * весила бы мегабайты. Признак обрезки обязателен отдельно от длины превью: по нему
 * видно, что показано не всё, и стоит запросить полное содержимое отдельным запросом.
 */
class ToolCallSummaryTest {

    @Test
    fun `сводка повторяет поля записи`() {
        val call = toolCall()

        val summary = ToolCallSummary.of(call)

        assertEquals(call.id, summary.id)
        assertEquals(call.runId, summary.runId)
        assertEquals(call.tool, summary.tool)
        assertEquals(call.outcome, summary.outcome)
        assertEquals(call.durationMillis, summary.durationMillis)
        assertEquals(call.cost, summary.cost)
        assertEquals(call.requiredApproval, summary.requiredApproval)
        assertEquals(call.approval, summary.approval)
        assertEquals(call.at, summary.at)
        assertEquals(call.arguments, summary.argumentsPreview)
        assertEquals(call.result, summary.resultPreview)
        assertFalse(summary.argumentsTruncated)
        assertFalse(summary.resultTruncated)
    }

    @Test
    fun `длинные аргументы обрезаются с признаком обрезки`() {
        val call = toolCall(arguments = "a".repeat(TOOL_CALL_PREVIEW_CHARS + 1))

        val summary = ToolCallSummary.of(call)

        assertEquals(TOOL_CALL_PREVIEW_CHARS, summary.argumentsPreview.length)
        assertTrue(summary.argumentsTruncated, "признак обрезки обязан отличать усечённое превью от полного")
    }

    @Test
    fun `длинный результат обрезается с признаком обрезки`() {
        val call = toolCall(result = "r".repeat(TOOL_CALL_PREVIEW_CHARS + 1))

        val summary = ToolCallSummary.of(call)

        assertEquals(TOOL_CALL_PREVIEW_CHARS, summary.resultPreview?.length)
        assertTrue(summary.resultTruncated)
    }

    @Test
    fun `ровно по лимиту — ещё не обрезка`() {
        val call = toolCall(arguments = "a".repeat(TOOL_CALL_PREVIEW_CHARS))

        val summary = ToolCallSummary.of(call)

        assertEquals(TOOL_CALL_PREVIEW_CHARS, summary.argumentsPreview.length)
        assertFalse(summary.argumentsTruncated)
    }

    @Test
    fun `без результата превью пусто, а не пустая строка`() {
        val summary = ToolCallSummary.of(toolCall(result = null))

        assertNull(summary.resultPreview, "незавершённый вызов не несёт результата — и превью тоже нет")
        assertFalse(summary.resultTruncated)
    }

    private fun toolCall(arguments: String = """{"path":"src/App.kt"}""", result: String? = "ok"): ToolCall =
        ToolCall(
            id = ToolCallId("tc-1"),
            runId = RunId("r-1"),
            tool = "read_file",
            arguments = arguments,
            result = result,
            outcome = ToolOutcome.SUCCESS,
            durationMillis = 5,
            cost = Cost(amountMicros = 0, known = true),
            requiredApproval = false,
            at = Instant.fromEpochMilliseconds(1_758_535_200_000),
        )
}
