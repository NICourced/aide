package dev.aide.tools.ports

import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.tools.ToolContext

/**
 * Доверенная оценка риска изменения (T-1.15, FR-AGENT-6).
 *
 * Риск вычисляется хостом по правилам § 5.3.2; модель не может задать или понизить
 * уровень. Порт объявлен в `host-tools`, а реализация живёт в `host-core`: для оценки
 * нужен контекст воркспейса и, в будущем, отчёты тестов, которые модулю инструментов
 * недоступны (О-1).
 */
fun interface ChangeRisk {

    /**
     * Оценивает риск изменения, которое собирается выполнить вызов.
     *
     * [arguments] — аргументы вызова в том виде, в котором их проверяет схема;
     * [context] даёт доступ к корню воркспейса для чтения старого содержимого.
     */
    suspend fun assess(runId: RunId, tool: String, arguments: String, context: ToolContext): RiskLevel
}
