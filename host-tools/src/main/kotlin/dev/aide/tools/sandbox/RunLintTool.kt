package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolResult
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.toolFailure
import dev.aide.tools.toolSuccess
import kotlinx.serialization.json.JsonObject

/**
 * Запуск линтера проекта (T-1.10, FR-TOOLS-12).
 *
 * Как и `run_tests`, команду и отчёт определяет хост по маркерам проекта: в этапе 1
 * линтер — detekt через Gradle, у иного проекта строки линтера нет и будет явный
 * отказ, а не догадка (решение 2). Модель аргументов не передаёт.
 *
 * Исполнение, но не изменение репозитория (`changesRepository = false`, решение 7):
 * ось прав — запись (О-4, NG7), а коммита шага после линтера не ставится.
 *
 * Замечания линтера не переводят состояние тестов в `RED` (решение 9): линтер — не
 * тесты. Они едут в отчёте отдельным списком и участвуют в правиле safe (`RiskEvaluator`).
 * Инфраструктурный сбой линтера отчёта не несёт: состояния «линтер не запускался» в
 * домене нет, а пустой отчёт стёр бы прежние замечания, которых никто не отменял.
 */
class RunLintTool(
    /** Предел времени вызова; в тестах подменяется, чтобы поймать исход `TIMEOUT`. */
    override val timeoutMillis: Long = REPORT_TOOL_TIMEOUT_MILLIS,
) : AgentTool {

    private val runner: TestRunner = TestRunner()

    override val name: String = TOOL_NAME

    override val description: String =
        "Запустить линтер проекта и получить список замечаний с файлами и строками. " +
            "Команду и отчёт хост определяет сам по файлам проекта; аргументов у инструмента нет. " +
            "Для проекта без Gradle вернётся отказ — используйте run_command."

    override val argumentsSchema: JsonObject = REPORT_ARGUMENTS_SCHEMA

    override val kind: ToolKind = ToolKind.WRITE

    /** Линтер не меняет репозиторий: коммита шага после него не будет (решение 7). */
    override val changesRepository: Boolean = false

    /** Инструмент порождает отчёт: таймаут помечается состоянием `TestState.TIMEOUT` (решение 9). */
    override val producesTestReport: Boolean = true

    /**
     * Исполнение идёт по оси записи, умолчание `ASK` (О-4, NG7): линтер запускает код
     * проекта. Пока диалога подтверждения нет (T-1.13), `ASK` означает отказ.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ASK, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        when (val result = runner.runLint(context.root)) {
            TestRunResult.Unrecognized -> toolFailure(UNRECOGNIZED_PROJECT)

            is TestRunResult.Infrastructure -> toolFailure(result.text)

            is TestRunResult.Reported -> toolSuccess(result.text, result.report)
        }

    companion object {

        /** Имя инструмента: под ним он виден модели. */
        const val TOOL_NAME: String = "run_lint"
    }
}
