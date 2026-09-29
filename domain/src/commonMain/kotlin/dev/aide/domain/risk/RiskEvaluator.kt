package dev.aide.domain.risk

import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.RiskLevel
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus

/**
 * Вычисление уровня риска изменения строго по правилам § 5.3.2.
 *
 * Функция чистая: одинаковый вход даёт одинаковый выход, обращений к сети и к диску нет.
 * LLM не может понизить уровень — она может добавить пояснение, но не участвует в вычислении
 * (FR-AGENT-6). Список путей, изменения в которых считаются безопасными, намеренно задан
 * явными правилами, а не эвристикой.
 *
 * Результат не ниже риска самого опасного hunk'а [Input.files] (инвариант 5 § 4.1): правила
 * § 5.3.2 классифицируют пакет целиком, но если отдельный блок уже помечен опаснее, пакет
 * не может оказаться безопаснее своего блока.
 */
object RiskEvaluator {

    /** Порог объёма по умолчанию: 50 строк на пакет (§ 5.3.2). Хранение настройки — этап 1, T-1.16. */
    const val DEFAULT_VOLUME_THRESHOLD_LINES: Int = 50

    /** Во сколько раз объём должен превысить порог, чтобы изменение стало [RiskLevel.RISKY]. */
    const val RISKY_VOLUME_MULTIPLIER: Int = 5

    /** Вход вычисления: сам набор изменений и контекст, которого нет в diff'е. */
    data class Input(
        /** Изменения по файлам. */
        val files: List<FileChange>,
        /** Итог последнего прогона тестов и линтера. */
        val tests: TestStatus,
        /** Суммарный объём изменения в строках (добавленные + удалённые). */
        val totalChangedLines: Int,
        /** Затронут публичный API: экспортируемые символы, схемы протокола, публичные интерфейсы. */
        val touchesPublicApi: Boolean,
        /** Затронута схема БД: миграции, определения таблиц. */
        val touchesDatabaseSchema: Boolean,
        /** Затронуты права доступа или секреты. */
        val touchesPermissionsOrSecrets: Boolean,
    )

    /**
     * Вычисляет уровень риска.
     *
     * @param volumeThresholdLines порог объёма; по умолчанию [DEFAULT_VOLUME_THRESHOLD_LINES].
     */
    fun evaluate(changes: Input, volumeThresholdLines: Int = DEFAULT_VOLUME_THRESHOLD_LINES): RiskLevel {
        val byRules = when {
            anyRiskyCondition(changes, volumeThresholdLines) -> RiskLevel.RISKY
            allSafeConditions(changes, volumeThresholdLines) -> RiskLevel.SAFE
            else -> RiskLevel.NORMAL
        }
        return maxOf(byRules, highestHunkRisk(changes.files))
    }

    /** Максимальный риск среди блоков; [RiskLevel.SAFE], если блоков нет. */
    private fun highestHunkRisk(files: List<FileChange>): RiskLevel =
        files.asSequence().flatMap { it.hunks.asSequence() }.maxOfOrNull { it.risk } ?: RiskLevel.SAFE

    private fun anyRiskyCondition(changes: Input, threshold: Int): Boolean =
        changes.files.any { it.changeKind == FileChangeKind.DELETED } ||
            changes.touchesPublicApi ||
            changes.touchesDatabaseSchema ||
            changes.touchesPermissionsOrSecrets ||
            changes.tests.state == TestState.RED ||
            changes.totalChangedLines > threshold * RISKY_VOLUME_MULTIPLIER

    private fun allSafeConditions(changes: Input, threshold: Int): Boolean =
        changes.files.isNotEmpty() &&
            changes.files.all { isSafePath(it.path) } &&
            changes.tests.state == TestState.GREEN &&
            changes.tests.lintFindings == 0 &&
            changes.totalChangedLines in 1..threshold &&
            !changes.touchesPublicApi &&
            !changes.touchesDatabaseSchema &&
            !changes.touchesPermissionsOrSecrets

    /**
     * Путь считается безопасным, если он относится только к тестам, документации или локализации.
     *
     * Результат форматтера отдельного признака не имеет: по пути его отличить нельзя, поэтому
     * в список безопасных такие файлы не попадают (см. § 5.3.2).
     */
    internal fun isSafePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        val fileName = normalized.substringAfterLast('/')
        return isTestPath(normalized, fileName) ||
            isDocumentationPath(normalized, fileName) ||
            isLocalizationPath(normalized, fileName)
    }

    private fun isTestPath(normalized: String, fileName: String): Boolean {
        val directories = listOf("/test/", "/tests/", "/androidtest/", "/commontest/")
        val prefixes = listOf("test/", "tests/")
        val suffixes = listOf("test.kt", "tests.kt", "_test.go", "spec.kt", "spec.js", "spec.ts")
        return directories.any { normalized.contains(it) } ||
            prefixes.any { normalized.startsWith(it) } ||
            suffixes.any { fileName.endsWith(it) } ||
            fileName.startsWith("test_")
    }

    private fun isDocumentationPath(normalized: String, fileName: String): Boolean {
        val inDocsDirectory = normalized.startsWith("docs/") || normalized.contains("/docs/")
        val suffixes = listOf(".md", ".rst")
        return inDocsDirectory ||
            suffixes.any { fileName.endsWith(it) } ||
            fileName == "license" ||
            fileName == "changelog.md"
    }

    private fun isLocalizationPath(normalized: String, fileName: String): Boolean {
        val markers = listOf("/values-", "/i18n/", "/l10n/", "/locales/")
        val suffixes = listOf(".po", ".ftl")
        return markers.any { normalized.contains(it) } ||
            fileName == "strings.xml" ||
            suffixes.any { fileName.endsWith(it) }
    }
}
