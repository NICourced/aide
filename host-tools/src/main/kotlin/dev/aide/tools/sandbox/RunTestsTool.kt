package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolResult
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.toolFailure
import dev.aide.tools.toolSuccess
import kotlinx.serialization.json.JsonObject

/**
 * Запуск тестов проекта (T-1.10, FR-TOOLS-12).
 *
 * Команду и место отчёта определяет хост по маркерам проекта ([ProjectCommands]),
 * а не модель: аргументов у инструмента нет вовсе, и подсунуть свою команду под
 * видом «тестов» нельзя. Разбор отчёта в структуру — дело [TestRunner]; инструмент
 * только переводит его исход в результат вызова.
 *
 * Исполнение, но не изменение репозитория (`changesRepository = false`): ось прав —
 * запись (О-4, NG7), потому что запуск кода опасен не меньше записи, а коммит шага
 * после тестов не ставится — иначе в него уехали бы артефакты сборки (решение 7).
 *
 * Инфраструктурный сбой (команда не стартовала, отчёта нет, отчёт битый) несёт отчёт
 * с состоянием [TestState.INFRA_ERROR]: это не «тесты упали», и путать их нельзя
 * (решение 9). Отчёт при этом всё равно возвращается — прогон обязан увидеть, что
 * тесты не запускались, а не решить, что они не нужны.
 */
class RunTestsTool(
    /** Предел времени вызова; в тестах подменяется, чтобы поймать исход `TIMEOUT`. */
    override val timeoutMillis: Long = REPORT_TOOL_TIMEOUT_MILLIS,
) : AgentTool {

    private val runner: TestRunner = TestRunner()

    override val name: String = TOOL_NAME

    override val description: String =
        "Запустить тесты проекта и получить сводку: состояние, список упавших тестов и их файлов. " +
            "Команду и отчёт хост определяет сам по файлам проекта; аргументов у инструмента нет. " +
            "Для проекта без Gradle и Maven вернётся отказ — используйте run_command."

    override val argumentsSchema: JsonObject = REPORT_ARGUMENTS_SCHEMA

    override val kind: ToolKind = ToolKind.WRITE

    /** Тесты не меняют репозиторий: коммита шага после них не будет (решение 7). */
    override val changesRepository: Boolean = false

    /**
     * Инструмент порождает отчёт — по нему точка вызова отмечает таймаут состоянием
     * `TestState.TIMEOUT` (T-1.10, решение 9); на таймауте инструмент сам ничего вернуть
     * не успевает.
     */
    override val producesTestReport: Boolean = true

    /**
     * Исполнение идёт по оси записи, умолчание `ASK` (О-4, NG7): тесты запускают код
     * проекта, и опасность у них та же, что у команды. Пока диалога подтверждения нет
     * (T-1.13), `ASK` означает отказ — это и есть настоящий барьер этой задачи.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ASK, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult =
        when (val result = runner.runTests(context.root)) {
            TestRunResult.Unrecognized -> toolFailure(UNRECOGNIZED_PROJECT)

            is TestRunResult.Infrastructure ->
                toolFailure(result.text, TestReport(state = TestState.INFRA_ERROR))

            is TestRunResult.Reported -> toolSuccess(result.text, result.report)
        }

    companion object {

        /** Имя инструмента: под ним он виден модели. */
        const val TOOL_NAME: String = "run_tests"
    }
}
