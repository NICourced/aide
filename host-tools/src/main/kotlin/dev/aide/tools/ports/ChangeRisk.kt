package dev.aide.tools.ports

import dev.aide.domain.FileChange
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId

/**
 * Доверенная оценка риска изменения (T-1.16, FR-AGENT-6).
 *
 * Риск вычисляется хостом по правилам § 5.3.2; модель не может задать или понизить
 * уровень. Порт объявлен в `host-tools`, а реализация живёт в `host-core`: для оценки
 * нужен контекст воркспейса и отчёты тестов, которые модулю инструментов недоступны (О-1).
 */
fun interface ChangeRisk {

    /**
     * Оценивает риск готового изменения.
     *
     * [files] — файловое изменение, полученное от инструмента через [ProposableTool.describe];
     * сырой JSON аргументов сюда не передаётся, чтобы модель не могла повлиять на уровень.
     */
    suspend fun assess(runId: RunId, tool: String, files: List<FileChange>): RiskLevel
}
