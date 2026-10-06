package dev.aide.tools

import dev.aide.domain.ToolOutcome
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.ports.StepCommit
import dev.aide.tools.ports.StepCommits
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject

/**
 * T-1.11: внутренний инструмент фиксации шага — делегирование порту и исходы.
 *
 * `host-tools` о git не знает (О-1), поэтому вся работа инструмента — передать ветку
 * и сообщение порту и перевести исход обратно в результат вызова. Ошибка порта обязана
 * стать `FAILURE`, а не исключением: отказ коммита прогон не роняет, но и не прячется.
 */
class CommitStepToolTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `делегирует порту ветку и сообщение, а хеш возвращает результатом`() {
        val commits = FakeStepCommits(StepCommit.Committed(HASH))
        val tool = CommitStepTool(commits)

        val result = runBlocking { tool.execute(arguments(), workspace.context) }

        assertEquals(listOf(BRANCH to MESSAGE), commits.calls, "ветка и сообщение обязаны дойти до порта")
        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertTrue(result.text.contains(HASH), "хеш коммита обязан быть виден в журнале: ${result.text}")
    }

    @Test
    fun `чистое дерево — успех без коммита`() {
        val tool = CommitStepTool(FakeStepCommits(StepCommit.NothingToCommit))

        val result = runBlocking { tool.execute(arguments(), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, "коммитить нечего — не ошибка инструмента")
    }

    @Test
    fun `отказ порта возвращается FAILURE с кодом причины`() {
        val tool = CommitStepTool(FakeStepCommits(StepCommit.Refused("commit_failed")))

        val result = runBlocking { tool.execute(arguments(), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("commit_failed"), "код причины обязан быть в тексте: ${result.text}")
    }

    @Test
    fun `инструмент внутренний и объявлен изменяющим`() {
        val tool = CommitStepTool(FakeStepCommits())

        assertEquals(ToolVisibility.INTERNAL, tool.visibility, "модели его не показывают (T-1.11)")
        assertEquals(ToolKind.WRITE, tool.kind)
        assertEquals(CommitStepTool.TOOL_NAME, tool.name)
    }

    private fun arguments(): JsonObject = argumentsOf(
        CommitStepTool.BRANCH_ARGUMENT to BRANCH,
        CommitStepTool.MESSAGE_ARGUMENT to MESSAGE,
    )

    private companion object {

        const val BRANCH: String = "ai/t-1"
        const val MESSAGE: String = "Шаг 1: поправить вход"
        const val HASH: String = "abc1234"
    }
}

/** Порт фиксации шага в памяти: запоминает вызовы и отвечает заданным исходом. */
private class FakeStepCommits(
    private val outcome: StepCommit = StepCommit.Committed("agent-commit-hash"),
) : StepCommits {

    val calls: MutableList<Pair<String, String>> = mutableListOf()

    override suspend fun commit(branch: String, message: String): StepCommit {
        calls += branch to message
        return outcome
    }
}
