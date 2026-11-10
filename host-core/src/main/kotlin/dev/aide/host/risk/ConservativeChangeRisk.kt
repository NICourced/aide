package dev.aide.host.risk

import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.tools.ToolContext
import dev.aide.tools.ports.ChangeRisk

/**
 * Консервативная оценка риска изменения (T-1.15).
 *
 * Пока нет отчётов тестов и истории правок, любое изменение уровня NORMAL.
 * Порт объявлен в `host-tools`, а реализация живёт здесь: в будущем оценка
 * будет читать воркспейс, git-историю и отчёты тестов, что модулю инструментов
 * недоступно (О-1).
 */
object ConservativeChangeRisk : ChangeRisk {

    override suspend fun assess(runId: RunId, tool: String, arguments: String, context: ToolContext): RiskLevel =
        RiskLevel.NORMAL
}
