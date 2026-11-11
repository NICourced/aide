package dev.aide.tools

import dev.aide.domain.ChangeProposal
import dev.aide.domain.FileChange
import dev.aide.domain.Hunk
import dev.aide.domain.ProposalId
import dev.aide.domain.RiskLevel
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.RunId
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

    /**
     * Один trusted diff на вызов (T-1.16).
     *
     * Описывает изменение с нейтральной меткой [RiskLevel.SAFE], оценивает готовые файлы
     * доверенным классификатором и заменяет риск hunk'ов на итоговый. При сбое описания
     * возвращается исход вызова ([ToolOutcome.FAILURE], [ToolOutcome.TIMEOUT] или
     * [ToolOutcome.DENIED]), а не [ToolOutcome.PROPOSED].
     */
    @Suppress("TooGenericExceptionCaught")
    suspend fun evaluate(tool: ProposableTool, input: ProposalInput): EvaluatedChange = try {
        when (val described = withTimeout(input.timeoutMillis) {
            tool.describe(input.arguments, input.context, RiskLevel.SAFE)
        }) {
            is ProposeResult.Failure -> EvaluatedChange.Failed(described.result)
            is ProposeResult.Files -> {
                val level = assessedRisk(input.runId, input.toolName, described.files)
                EvaluatedChange.Ready(filesWithRisk(described.files, level), level)
            }
        }
    } catch (error: TimeoutCancellationException) {
        logger.warn("Описание предложения «${input.toolName}» не уложилось в лимит", error)
        EvaluatedChange.Failed(
            ToolResult(
                text = "описание предложения «${input.toolName}» не уложилось в лимит ${input.timeoutMillis} мс",
                outcome = ToolOutcome.TIMEOUT,
            ),
        )
    } catch (error: HardLimitViolation) {
        EvaluatedChange.Failed(
            ToolResult(
                text = "вызов отклонён жёстким пределом (${error.reason.name}): ${error.detail}",
                outcome = ToolOutcome.DENIED,
            ),
        )
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Не удалось описать предложение «${input.toolName}»", error)
        EvaluatedChange.Failed(
            ToolResult(
                text = "не удалось описать предложение «${input.toolName}»: ${error.message}",
                outcome = ToolOutcome.FAILURE,
            ),
        )
    }

    /** Сохраняет уже оценённое изменение; [ToolOutcome.PROPOSED] возвращается только после успешной записи. */
    @Suppress("TooGenericExceptionCaught")
    fun propose(input: ProposalInput, files: List<FileChange>, risk: RiskLevel): ToolResult = try {
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
        ToolResult(
            text = "изменение сохранено как предложение (${files.size} файл(ов))",
            outcome = ToolOutcome.PROPOSED,
        )
    } catch (error: Exception) {
        logger.warn("Не удалось сохранить предложение «${input.toolName}»", error)
        ToolResult(
            text = "не удалось сохранить предложение «${input.toolName}»: ${error.message}",
            outcome = ToolOutcome.FAILURE,
        )
    }

    /** Сбой классификатора консервативно даёт NORMAL, а не разрешение авто-применения. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun assessedRisk(runId: RunId, tool: String, files: List<FileChange>): RiskLevel = try {
        risk.assess(runId, tool, files)
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        logger.warn("Не удалось оценить риск вызова $tool: ${error.message}", error)
        RiskLevel.NORMAL
    }

    private fun filesWithRisk(files: List<FileChange>, level: RiskLevel): List<FileChange> =
        files.map { file ->
            file.copy(hunks = file.hunks.map { hunk -> hunk.copy(risk = level) })
        }
}

/** Результат оценки изменения. */
internal sealed interface EvaluatedChange {
    data class Ready(val files: List<FileChange>, val risk: RiskLevel) : EvaluatedChange
    data class Failed(val result: ToolResult) : EvaluatedChange
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
