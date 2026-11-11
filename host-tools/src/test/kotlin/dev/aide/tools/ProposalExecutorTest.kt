package dev.aide.tools

import dev.aide.domain.ChangeProposal
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.tools.file.ProposableTool
import dev.aide.tools.file.ProposeResult
import dev.aide.tools.ports.ChangeProposals
import dev.aide.tools.ports.ChangeRisk
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * T-1.16: оценка риска и сохранение предложения изолированы от исполнения инструмента.
 *
 * Проверяется, что `ProposableTool.describe` вызывается ровно один раз независимо от того,
 * применяется изменение или сохраняется, и что риск hunk'ов — итоговый, а не тот, который
 * инструмент вернул до оценки. Аргументы модели, в том числе `risk=SAFE`, попадают только
 * в `describe`, но не в `ChangeRisk`, поэтому LLM не может понизить итоговый уровень.
 */
class ProposalExecutorTest {

    private val workspace = ToolsWorkspace()
    private val runId = RunId("run-1")
    private val callId = ToolCallId("call-1")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `describe вызывается один раз при автоприменении SAFE`() = runBlocking {
        val tool = RecordingProposableTool()
        val executor = ProposalExecutor(
            risk = ChangeRisk { _, _, _ -> RiskLevel.SAFE },
            proposals = RecordingChangeProposals(),
        )

        val result = executor.evaluate(tool, proposalInput())

        assertTrue(result is EvaluatedChange.Ready)
        assertEquals(RiskLevel.SAFE, result.risk)
        assertEquals(1, tool.describeCalls)
        assertEquals(RiskLevel.SAFE, result.files.single().hunks.single().risk)
    }

    @Test
    fun `describe вызывается один раз при сохранении NORMAL и hunk'и получают итоговый риск`() =
        runBlocking {
            val tool = RecordingProposableTool()
            val proposals = RecordingChangeProposals()
            val executor = ProposalExecutor(
                risk = ChangeRisk { _, _, _ -> RiskLevel.NORMAL },
                proposals = proposals,
            )

            val evaluated = executor.evaluate(tool, proposalInput())
            assertTrue(evaluated is EvaluatedChange.Ready)
            val saved = executor.propose(proposalInput(), evaluated.files, evaluated.risk)

            assertEquals(ToolOutcome.PROPOSED, saved.outcome)
            assertEquals(1, tool.describeCalls)
            val proposal = proposals.saved.single()
            assertEquals(RiskLevel.NORMAL, proposal.risk)
            assertEquals(RiskLevel.NORMAL, proposal.files.single().hunks.single().risk)
        }

    @Test
    fun `сбой сохранения предложения не выдаёт PROPOSED`() = runBlocking {
        val tool = RecordingProposableTool()
        val executor = ProposalExecutor(
            risk = ChangeRisk { _, _, _ -> RiskLevel.NORMAL },
            proposals = ChangeProposals { error("db unavailable") },
        )

        val evaluated = executor.evaluate(tool, proposalInput())
        assertTrue(evaluated is EvaluatedChange.Ready)
        val result = executor.propose(proposalInput(), evaluated.files, evaluated.risk)

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("db unavailable"))
    }

    @Test
    fun `доверенный ChangeRisk игнорирует аргумент risk модели и определяет риск по FileChange`() =
        runBlocking {
            val tool = ProposableToolWithRiskArgument()
            val proposals = RecordingChangeProposals()
            val executor = ProposalExecutor(
                risk = ChangeRisk { _, _, files ->
                    // Доверенная оценка видит только готовые FileChange, не JSON-аргументы.
                    // Обычный исходник — NORMAL, независимо от того, что заявила модель.
                    assertEquals("src/App.kt", files.single().path)
                    RiskLevel.NORMAL
                },
                proposals = proposals,
            )

            val input = proposalInputWithRisk(risk = "SAFE")
            val evaluated = executor.evaluate(tool, input)
            assertTrue(evaluated is EvaluatedChange.Ready)
            assertEquals(RiskLevel.NORMAL, evaluated.risk)

            val result = executor.propose(input, evaluated.files, evaluated.risk)
            assertEquals(ToolOutcome.PROPOSED, result.outcome)
            assertEquals(1, tool.describeCalls)

            val proposal = proposals.saved.single()
            assertEquals(RiskLevel.NORMAL, proposal.risk)
            assertEquals(RiskLevel.NORMAL, proposal.files.single().hunks.single().risk)
        }

    private fun proposalInput(): ProposalInput = ProposalInput(
        runId = runId,
        callId = callId,
        toolName = "write_file",
        arguments = buildJsonObject {
            put("path", JsonPrimitive("src/App.kt"))
            put("content", JsonPrimitive("fun main() = Unit"))
        },
        context = TestToolContext(Files.createTempDirectory("proposal-")),
        timeoutMillis = 1_000,
    )

    private fun proposalInputWithRisk(risk: String): ProposalInput = ProposalInput(
        runId = runId,
        callId = callId,
        toolName = "write_file",
        arguments = buildJsonObject {
            put("path", JsonPrimitive("src/App.kt"))
            put("content", JsonPrimitive("fun main() = Unit"))
            put("risk", JsonPrimitive(risk))
        },
        context = TestToolContext(Files.createTempDirectory("proposal-")),
        timeoutMillis = 1_000,
    )

    private class RecordingProposableTool : ProposableTool {

        var describeCalls: Int = 0

        override suspend fun describe(
            arguments: JsonObject,
            context: ToolContext,
            risk: RiskLevel,
        ): ProposeResult {
            describeCalls++
            return ProposeResult.Files(
                listOf(
                    FileChange(
                        path = "src/App.kt",
                        changeKind = FileChangeKind.ADDED,
                        hunks = listOf(
                            Hunk(
                                id = HunkId("hunk-1"),
                                filePath = "src/App.kt",
                                startLine = 1,
                                kind = HunkKind.ADD,
                                risk = risk,
                                lines = listOf(
                                    HunkLine(
                                        kind = LineKind.ADDED,
                                        newNumber = 1,
                                        text = "fun main() = Unit",
                                    ),
                                ),
                            ),
                        ),
                        addedLines = 1,
                        removedLines = 0,
                    ),
                ),
            )
        }
    }

    /**
     * Инструмент, который сам читает аргумент `risk` модели (имитация схемы с дополнительным
     * полем). В production такой инструмент может существовать, но [ChangeRisk] всё равно
     * получает только [FileChange] и не видит JSON-аргументы.
     */
    private class ProposableToolWithRiskArgument : ProposableTool {

        var describeCalls: Int = 0

        override suspend fun describe(
            arguments: JsonObject,
            context: ToolContext,
            risk: RiskLevel,
        ): ProposeResult {
            describeCalls++
            val modelRisk = when (arguments["risk"]?.jsonPrimitive?.content) {
                "SAFE" -> RiskLevel.SAFE
                "RISKY" -> RiskLevel.RISKY
                else -> RiskLevel.NORMAL
            }
            return ProposeResult.Files(
                listOf(
                    FileChange(
                        path = "src/App.kt",
                        changeKind = FileChangeKind.ADDED,
                        hunks = listOf(
                            Hunk(
                                id = HunkId("hunk-risk-arg"),
                                filePath = "src/App.kt",
                                startLine = 1,
                                kind = HunkKind.ADD,
                                risk = modelRisk,
                                lines = listOf(
                                    HunkLine(
                                        kind = LineKind.ADDED,
                                        newNumber = 1,
                                        text = "fun main() = Unit",
                                    ),
                                ),
                            ),
                        ),
                        addedLines = 1,
                        removedLines = 0,
                    ),
                ),
            )
        }
    }
}
