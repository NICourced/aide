package dev.aide.tools.sandbox

import dev.aide.domain.MAX_REPORT_ENTRIES
import dev.aide.domain.TestFailure
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import java.nio.file.Path

/**
 * Текст сводки, который уходит модели после прогона тестов или линтера (T-1.10).
 *
 * Отдельно от [TestRunner]: текст — это представление, а не исполнение, и держать его
 * рядом с запуском значило бы смешивать две причины изменения в одном месте. Модель
 * видит код возврата и исход, а когда что-то пошло не так — ещё и вывод команды,
 * иначе инфраструктурный сбой выглядел бы неотличимо от падения тестов.
 */
internal object TestReportText {

    /** Сводка разобранного отчёта: команда, код, состояние, падения и замечания. */
    fun summary(command: List<String>, result: CommandResult, report: TestReport, reports: List<Path>): String =
        buildString {
            append(line(command, result))
            append("отчёт: ").append(reports.joinToString(", ") { it.toString() }).append('\n')
            report.state?.let { append("тесты: ").append(stateWord(it)).append('\n') }
            report.failures.forEach { append("упал: ").append(failureText(it)).append('\n') }
            report.lintFindings?.let { append("замечаний линтера: ").append(it.size).append('\n') }
            if (report.failuresTruncated) append("падения показаны не целиком: предел $MAX_REPORT_ENTRIES записей\n")
            if (report.lintTruncated) append("замечания показаны не целиком: предел $MAX_REPORT_ENTRIES записей\n")
        }

    /** Команду не удалось запустить: ни отчёта, ни кода возврата нет. */
    fun launchFailed(command: List<String>): String =
        "команду «${command.joinToString(" ")}» запустить не удалось: проверьте, что она есть в PATH проекта\n"

    /**
     * Отчёт не получен с причиной [reason]; вывод команды прикладывается, потому что
     * в инфраструктурном сбое он и есть объяснение.
     */
    fun failedReport(command: List<String>, result: CommandResult, reason: String): String = buildString {
        append(line(command, result))
        append(reason).append('\n')
        append(output(result))
    }

    private fun line(command: List<String>, result: CommandResult): String =
        "команда: ${command.joinToString(" ")}\nкод возврата: ${result.exitCode}\n"

    private fun output(result: CommandResult): String = buildString {
        if (result.stdout.isNotBlank()) append("stdout:\n").append(result.stdout).append(terminator(result.stdout))
        if (result.stderr.isNotBlank()) append("stderr:\n").append(result.stderr).append(terminator(result.stderr))
    }

    private fun terminator(text: String): String = if (text.endsWith('\n')) "" else "\n"

    private fun failureText(failure: TestFailure): String {
        val where = failure.file?.let { " ($it)" }.orEmpty()
        val why = failure.message?.let { ": $it" }.orEmpty()
        return "${failure.name}$where$why"
    }

    private fun stateWord(state: TestState): String = when (state) {
        TestState.GREEN -> "пройдены"
        TestState.RED -> "не пройдены"
        TestState.SKIPPED -> "в проекте нет"
        TestState.INFRA_ERROR -> "не запущены: инфраструктурная ошибка"
        TestState.TIMEOUT -> "превышен лимит времени"
        TestState.NOT_RUN -> "не запускались"
    }
}
