package dev.aide.host.risk

import dev.aide.domain.FileChange
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import dev.aide.domain.risk.RiskEvaluator
import dev.aide.host.store.RunStore
import dev.aide.tools.ports.ChangeRisk

/**
 * Детерминированная оценка риска по правилам § 5.3.2 (T-1.16).
 *
 * Читает прогон из хранилища, берёт последний отчёт тестов/линтера и порог объёма,
 * затем вызывает чистый [RiskEvaluator]. Специальные области определяются по путям
 * файлов консервативно: даже в тестах и документации они дают [RiskLevel.RISKY].
 */
class DeterministicChangeRisk(private val runs: RunStore) : ChangeRisk {

    override suspend fun assess(runId: RunId, tool: String, files: List<FileChange>): RiskLevel {
        val run = runs.load(runId)
        val report = run?.testReport
        val state = report?.state ?: TestState.NOT_RUN
        // Отсутствие линтера в отчёте (null) — не то же самое, что чистый линтер (пустой список):
        // без линтера условие SAFE не выполняется, но само по себе это не делает изменение RISKY.
        val lintFindings = report?.lintFindings
        val lintCount = when {
            report == null -> 0
            lintFindings == null -> 1
            else -> lintFindings.size
        }
        val threshold = run?.safeVolumeThresholdLines ?: RiskEvaluator.DEFAULT_VOLUME_THRESHOLD_LINES
        return RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = files,
                tests = TestStatus(state = state, lintFindings = lintCount),
                totalChangedLines = files.sumOf { it.addedLines + it.removedLines },
                touchesPublicApi = files.any { touchesPublicApi(it.path) },
                touchesDatabaseSchema = files.any { touchesDatabaseSchema(it.path) },
                touchesPermissionsOrSecrets = files.any { touchesPermissionsOrSecrets(it.path) },
            ),
            volumeThresholdLines = threshold,
        )
    }

    private fun touchesPublicApi(path: String): Boolean {
        val normalized = normalized(path)
        val fileName = fileNameOf(normalized)
        return fileName.endsWith(".proto") ||
            fileName.endsWith(".graphql") ||
            fileName.endsWith(".graphqls") ||
            fileName.startsWith("openapi") ||
            fileName.startsWith("swagger")
    }

    private fun touchesDatabaseSchema(path: String): Boolean {
        val normalized = normalized(path)
        val fileName = fileNameOf(normalized)
        val segments = segments(normalized)
        return fileName.endsWith(".sql") ||
            fileName.endsWith(".sq") ||
            fileName.endsWith(".sqm") ||
            segments.any { it == "migration" || it == "migrations" }
    }

    private fun touchesPermissionsOrSecrets(path: String): Boolean {
        val normalized = normalized(path)
        val fileName = fileNameOf(normalized)
        val segments = segments(normalized)
        return fileName.startsWith(".env") ||
            fileName.endsWith(".pem") ||
            fileName.endsWith(".key") ||
            fileName.endsWith(".p12") ||
            fileName.endsWith(".keystore") ||
            fileName.contains("secret") ||
            fileName.contains("credential") ||
            segments.any { it == "permissions" }
    }

    private fun normalized(path: String): String = path.replace('\\', '/').lowercase()

    private fun fileNameOf(normalized: String): String = normalized.substringAfterLast('/')

    private fun segments(normalized: String): List<String> =
        normalized.split('/').filter { it.isNotEmpty() }
}
