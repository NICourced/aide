package dev.aide.tools

import dev.aide.domain.ChangeProposal
import dev.aide.domain.ProposalId
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.tools.file.ProposableTool
import dev.aide.tools.file.ProposeResult
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.ports.ChangeProposals
import dev.aide.tools.ports.ChangeRisk
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** Оценивает риск и сохраняет неприменённые предложения отдельно от основного цикла вызова. */
internal class ProposalExecutor(
    private val risk: ChangeRisk,
    private val proposals: ChangeProposals,
    private val clock: () -> Instant = Clock.System::now,
) {

    private val logger = LoggerFactory.getLogger(ProposalExecutor::class.java)

    /** Сбой классификатора консервативно даёт NORMAL, а не разрешение авто-применения. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun assess(runId: RunId, tool: String, arguments: String, context: ToolContext): RiskLevel = try {
        risk.assess(runId, tool, arguments, context)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Не удалось оценить риск вызова $tool: ${error.message}", error)
        RiskLevel.NORMAL
    }

    /** Сохраняет описанное изменение; PROPOSED возвращается только после успешной записи. */
    @Suppress("TooGenericExceptionCaught")
    suspend fun propose(tool: ProposableTool, input: ProposalInput, riskLevel: RiskLevel): ToolResult = try {
        when (val described = withTimeout(input.timeoutMillis) {
            tool.describe(input.arguments, input.context, riskLevel)
        }) {
            is ProposeResult.Failure -> described.result
            is ProposeResult.Files -> save(input, described.files, riskLevel)
        }
    } catch (error: TimeoutCancellationException) {
        logger.warn("Описание предложения «${input.toolName}» не уложилось в лимит", error)
        ToolResult(
            text = "описание предложения «${input.toolName}» не уложилось в лимит ${input.timeoutMillis} мс",
            outcome = ToolOutcome.TIMEOUT,
        )
    } catch (error: HardLimitViolation) {
        ToolResult(
            text = "вызов отклонён жёстким пределом (${error.reason.name}): ${error.detail}",
            outcome = ToolOutcome.DENIED,
        )
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Не удалось сохранить предложение «${input.toolName}»", error)
        ToolResult(
            text = "не удалось сохранить предложение «${input.toolName}»: ${error.message}",
            outcome = ToolOutcome.FAILURE,
        )
    }

    private fun save(input: ProposalInput, files: List<dev.aide.domain.FileChange>, risk: RiskLevel): ToolResult {
        proposals.save(
            ChangeProposal(
                id = ProposalId(UUID.randomUUID().toString()),
                runId = input.runId,
                callId = input.callId,
                tool = input.toolName,
                files = files,
                risk = risk,
                createdAt = clock(),
            ),
        )
        return ToolResult(
            text = "изменение сохранено как предложение (${files.size} файл(ов))",
            outcome = ToolOutcome.PROPOSED,
        )
    }
}

/** Данные одного вызова, необходимые для построения и сохранения предложения. */
internal data class ProposalInput(
    val runId: RunId,
    val callId: ToolCallId,
    val toolName: String,
    val arguments: JsonObject,
    val context: ToolContext,
    val timeoutMillis: Long,
)
