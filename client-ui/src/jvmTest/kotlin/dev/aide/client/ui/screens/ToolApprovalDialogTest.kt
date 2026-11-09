package dev.aide.client.ui.screens

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.RunId
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolKind
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-1.13: диалог подтверждения вызова показывает инструмент, аргументы и последствия,
 * а каждая кнопка отдаёт правильное решение.
 */
@OptIn(ExperimentalTestApi::class)
class ToolApprovalDialogTest {

    @Test
    fun `диалог показывает имя инструмента и аргументы`() = runComposeUiTest {
        val request = request()
        show(request) { }

        onNodeWithTag("approval-tool").assertIsDisplayed().assertTextContains("write_file", substring = true)
        onNodeWithTag("approval-arguments").assertIsDisplayed().assertTextContains("src/App.kt", substring = true)
        onNodeWithTag("approval-what").assertIsDisplayed().assertTextContains("Будет изменён файл")
        onNodeWithTag("approval-rollback").assertIsDisplayed()
            .assertTextContains("Перед изменением будет создана точка отката")
    }

    @Test
    fun `кнопка разрешить один раз отдает ALLOW_ONCE`() = runComposeUiTest {
        val request = request()
        var decision: ApprovalDecision? = null
        show(request) { decision = it }

        onNodeWithTag("approval-allow-once").performClick()

        assertEquals(ApprovalDecision.ALLOW_ONCE, decision)
    }

    @Test
    fun `кнопка разрешить на сессию отдает ALLOW_SESSION`() = runComposeUiTest {
        val request = request()
        var decision: ApprovalDecision? = null
        show(request) { decision = it }

        onNodeWithTag("approval-allow-session").performClick()

        assertEquals(ApprovalDecision.ALLOW_SESSION, decision)
    }

    @Test
    fun `кнопка запретить отдает DENY`() = runComposeUiTest {
        val request = request()
        var decision: ApprovalDecision? = null
        show(request) { decision = it }

        onNodeWithTag("approval-deny").performClick()

        assertEquals(ApprovalDecision.DENY, decision)
    }

    private fun androidx.compose.ui.test.ComposeUiTest.show(
        request: ToolApprovalRequest,
        onDecide: (ApprovalDecision) -> Unit,
    ) {
        setContent { ToolApprovalDialog(request, onDecide) }
    }

    private fun request(): ToolApprovalRequest = ToolApprovalRequest(
        callId = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "write_file",
        arguments = """{"path":"src/App.kt","content":"x"}""",
        kind = ToolKind.WRITE,
        changesRepository = true,
    )
}
