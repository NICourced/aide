package dev.aide.tools.sandbox

import dev.aide.domain.MAX_REPORT_ENTRIES
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import java.io.IOException
import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes
import org.slf4j.LoggerFactory

/**
 * Сколько байт одного файла отчёта читает хост (T-1.10).
 *
 * Число с причиной: отчёт — это файл в воркспейсе, который пишет агент, и ничто, кроме
 * этого предела, не мешает положить в `build/test-results/**/*.xml` документ на гигабайты
 * и уронить хост по памяти. Восемь МиБ с запасом покрывают отчёт большого набора тестов,
 * а всё, что больше, — почти наверняка не отчёт; предел проверяется **до** чтения, по
 * размеру файла.
 */
internal const val MAX_REPORT_FILE_BYTES: Long = 8L * 1024 * 1024

/**
 * Сколько байт всех отчётов читает хост за один прогон (T-1.10).
 *
 * Граница на сумму, а не только на файл: глобов может быть несколько, а файлов по глобу —
 * десятки, и по отдельности каждый под пределом. Тридцать два МиБ — четыре предельных
 * файла; больше за один запуск тестов не бывает.
 */
internal const val MAX_REPORT_TOTAL_BYTES: Long = 32L * 1024 * 1024

/**
 * Поправка на свежесть отметки файла относительно старта команды.
 *
 * Отчёт нового прогона обязан быть изменён не раньше запуска, но отметки времени файловой
 * системы грубые (у FAT шаг — две секунды), а часы процесса и диска могут расходиться.
 * Две секунды — тот зазор, в котором отчёт, записанный сразу после старта, не сочли бы
 * старым; отчёт прошлого прогона отстоит на минуты и в него не попадает.
 */
private const val FRESHNESS_TOLERANCE_MILLIS: Long = 2_000

/** Что дал запуск тестов или линтера. */
internal sealed interface TestRunResult {

    /** Проект не распознан: запускать нечего, об этом инструмент и скажет явным отказом. */
    data object Unrecognized : TestRunResult

    /** Отчёт найден и разобран. */
    data class Reported(val report: TestReport, val text: String) : TestRunResult

    /** Команда не стартовала, отчёта нет или он битый: это не «тесты упали». */
    data class Infrastructure(val text: String) : TestRunResult
}

/**
 * Запуск тестов и линтера проекта целиком (T-1.10, решение 1).
 *
 * Порядок один на оба инструмента: определить команду проекта ([ProjectCommands]),
 * выполнить её через [CommandRunner] (та же песочница, что у `run_command`: рабочая
 * директория внутри воркспейса, урезанное окружение, убийство дерева по отмене),
 * найти отчёт и разобрать его.
 *
 * Таймаут задаёт точка вызова ([dev.aide.tools.AgentTool.timeoutMillis]); по нему
 * [CommandRunner] убивает дерево, а `TestRunner` результата не увидит вовсе — исход
 * `TIMEOUT` формирует инвокер.
 *
 * Поиск, разбор и текст сводки — функции файла, а не методы класса: у них нет общего
 * состояния с запуском, и держать их внутри значило бы упереться в предел числа
 * функций на класс (детект), а не в существо задачи.
 */
internal class TestRunner(private val runner: CommandRunner = CommandRunner()) {

    /** Запускает тесты проекта; `Infrastructure`, если отчёта нет или он битый. */
    suspend fun runTests(root: Path): TestRunResult = run(root, ProjectTool.TESTS)

    /** Запускает линтер проекта; `Unrecognized`, если у проекта нет строки линтера. */
    suspend fun runLint(root: Path): TestRunResult = run(root, ProjectTool.LINT)

    private suspend fun run(root: Path, tool: ProjectTool): TestRunResult {
        val project = ProjectCommands.detect(root, tool) ?: return TestRunResult.Unrecognized
        // Момент старта запоминается до запуска: по нему решается, отчёт какого прогона
        // свежий. Сам фильтр свежести зависит от кода возврата — см. evaluate.
        return evaluate(root, tool, project, System.currentTimeMillis())
    }

    /**
     * Запускает команду и берёт отчёт — со строгостью по коду возврата (решение 9).
     *
     * Код возврата — признак того, дошёл ли прогон до тестов. Он 0 — команда отработала
     * нормально (в том числе когда задача Gradle/Maven оказалась up-to-date и **не
     * переписала** отчёт): тогда найденный отчёт отражает последний известный результат,
     * и брать его можно независимо от времени изменения. Он не 0 — доверять можно только
     * отчёту, изменённому не раньше старта: старый отчёт мог остаться от прошлого прогона
     * и дать ложный `GREEN` при провалившейся сборке. Поэтому фильтр свежести не убирают,
     * а включают ровно на ненулевом коде.
     */
    private suspend fun evaluate(
        root: Path,
        tool: ProjectTool,
        project: ProjectCommand,
        startedAt: Long,
    ): TestRunResult {
        val result = launch(project.command, root)
        val reports = result
            ?.let { findReports(root, project.reportGlobs, startedAt, freshOnly = it.exitCode != 0) }
            .orEmpty()
        return when {
            result == null -> TestRunResult.Infrastructure(TestReportText.launchFailed(project.command))
            reports.isEmpty() -> TestRunResult.Infrastructure(missingReportText(project.command, result))
            else -> readAndReport(root, tool, project, result, reports)
        }
    }

    /**
     * Выполняет команду; `null` — процесс не удалось запустить (нет программы, нет прав).
     *
     * Причина уходит в лог, а не в текст для модели: «программы нет» и «права не те»
     * одинаковы для прогона, но различаются при разборе жалобы.
     */
    private suspend fun launch(command: List<String>, root: Path): CommandResult? = try {
        runner.run(command, root, commandEnvironment(root))
    } catch (error: IOException) {
        runnerLogger.warn("Команда «${command.firstOrNull()}» не запустилась: ${error.message}")
        null
    }
}

private val runnerLogger = LoggerFactory.getLogger(TestRunner::class.java)

/** Чтение отчётов: либо готовый отчёт прогона, либо причина, по которой он не получен. */
private sealed interface ReadReport {

    /** Файл прочитан и разобран. */
    data class Ok(val report: TestReport, val size: Long) : ReadReport

    /** Файл не дал отчёта: [file] — о каком файле речь, `null` — ни об одном конкретном. */
    data class Failed(val problem: ReportProblem, val file: Path?) : ReadReport
}

/** Почему отчёт не получен — от этого зависит текст модели. */
private enum class ReportProblem { UNREADABLE, TOO_LARGE, UNPARSABLE }

private const val MISSING_REPORT: String = "отчёт не найден: тесты могли не запуститься или писать отчёт в другое место"

private const val BROKEN_REPORT: String = "отчёт не разобран: файл повреждён или имеет неизвестный формат"

/**
 * Читает отчёты по одному и собирает отчёт прогона, либо сообщает причину отказа.
 *
 * Строгий, а не «пропустить непрочитанный»: неполный отчёт выглядел бы как полный,
 * и зелёный прогон, собранный из уцелевших файлов, скрыл бы поломку ровно там, где она
 * важна. Поэтому неполнота — всегда инфраструктурная ошибка, а не «частичный `GREEN`»,
 * из которого ниже по течению получился бы ложный `safe` (NFR-SAFE-4).
 */
private fun readAndReport(
    root: Path,
    tool: ProjectTool,
    project: ProjectCommand,
    result: CommandResult,
    reports: List<Path>,
): TestRunResult {
    val sources = if (tool == ProjectTool.TESTS) workspaceSources(root) else emptyList()
    val parsed = mutableListOf<TestReport>()
    var budget = MAX_REPORT_TOTAL_BYTES
    var failure: ReadReport.Failed? = null
    for (file in reports) {
        if (failure != null) break
        when (val read = readOne(file, tool, root, sources, budget)) {
            is ReadReport.Failed -> failure = read
            is ReadReport.Ok -> {
                budget -= read.size
                parsed += read.report
            }
        }
    }
    val failed = failure
    return if (failed != null) {
        TestRunResult.Infrastructure(failureText(project.command, result, failed))
    } else {
        val report = withExitCode(combine(parsed, tool), tool, result.exitCode)
        TestRunResult.Reported(report, TestReportText.summary(project.command, result, report, reports))
    }
}

/** Читает один файл, сверяясь с пределами по размеру **до** чтения. */
private fun readOne(
    file: Path,
    tool: ProjectTool,
    root: Path,
    sources: List<String>,
    budget: Long,
): ReadReport {
    val size = runCatching { Files.size(file) }.getOrNull()
    return when {
        size == null -> ReadReport.Failed(ReportProblem.UNREADABLE, file)
        size > MAX_REPORT_FILE_BYTES || size > budget -> ReadReport.Failed(ReportProblem.TOO_LARGE, file)
        else -> parseFile(file, tool, root, sources, size)
    }
}

private fun parseFile(
    file: Path,
    tool: ProjectTool,
    root: Path,
    sources: List<String>,
    size: Long,
): ReadReport {
    val bytes = readBytes(file)
    val report = bytes?.let { content ->
        when (tool) {
            ProjectTool.TESTS -> ReportParser.parseTests(content, sources)
            ProjectTool.LINT -> ReportParser.parseLint(content, root)
        }
    }
    return when {
        bytes == null -> ReadReport.Failed(ReportProblem.UNREADABLE, file)
        report == null -> ReadReport.Failed(ReportProblem.UNPARSABLE, file)
        else -> ReadReport.Ok(report, size)
    }
}

private fun readBytes(file: Path): ByteArray? = try {
    Files.readAllBytes(file)
} catch (error: IOException) {
    runnerLogger.warn("Отчёт «$file» не прочитан: ${error.message}")
    null
}

private fun combine(reports: List<TestReport>, tool: ProjectTool): TestReport =
    if (tool == ProjectTool.TESTS) combineTests(reports) else combineLint(reports)

private fun combineTests(reports: List<TestReport>): TestReport {
    val failures = reports.flatMap { it.failures }
    return TestReport(
        state = combinedState(reports.mapNotNull { it.state }),
        failures = failures.take(MAX_REPORT_ENTRIES),
        failuresTruncated = reports.any { it.failuresTruncated } || failures.size > MAX_REPORT_ENTRIES,
    )
}

private fun combineLint(reports: List<TestReport>): TestReport {
    val findings = reports.flatMap { it.lintFindings.orEmpty() }
    return TestReport(
        lintFindings = findings.take(MAX_REPORT_ENTRIES),
        lintTruncated = reports.any { it.lintTruncated } || findings.size > MAX_REPORT_ENTRIES,
    )
}

/** Побеждает худшее: `RED` перекрывает `GREEN`, а `SKIPPED` остаётся, только если тестов нет нигде. */
private fun combinedState(states: List<TestState>): TestState = when {
    states.contains(TestState.RED) -> TestState.RED
    states.contains(TestState.GREEN) -> TestState.GREEN
    else -> TestState.SKIPPED
}

/**
 * Приводит состояние тестов в согласие с кодом возврата (решение 9).
 *
 * Падения в отчёте важнее кода: Gradle и Maven возвращают ненулевой код и на упавших
 * тестах. А вот код ≠ 0 **без** падений означает, что прогон не дошёл до тестов
 * (ошибка сборки) или найденный отчёт не об этом прогоне, — это инфраструктурная
 * ошибка, а не `GREEN`, иначе клиент получит ложный зелёный статус.
 */
private fun withExitCode(report: TestReport, tool: ProjectTool, exitCode: Int): TestReport =
    if (tool == ProjectTool.TESTS && exitCode != 0 && report.state != TestState.RED) {
        report.copy(state = TestState.INFRA_ERROR)
    } else {
        report
    }

/**
 * Файлы отчёта: внутри корня воркспейса, без разыменования ссылок и — при [freshOnly] —
 * изменённые не раньше старта прогона.
 *
 * Спуск идёт **только по каталогам шаблона**, а не обходом всего дерева. Симлинки
 * не разыменовываются: `build -> /внешний/каталог` иначе заставил бы **хост** прочитать
 * файл за корнем и отдать его содержимое модели — обход воркспейса обязан оставаться
 * внутри него (§ 10.1, как у файловых инструментов T-1.8).
 *
 * [freshOnly] включается на ненулевом коде возврата: тогда отчёт обязан быть этого
 * прогона, иначе он даст ложный `GREEN` при провалившейся сборке. На коде 0 фильтр не
 * нужен и вреден: up-to-date задача Gradle/Maven отчёт не переписывает, и отсечение по
 * времени превратило бы нормальный прогон в ложный `INFRA_ERROR`.
 */
private fun findReports(root: Path, globs: List<String>, startedAtMillis: Long, freshOnly: Boolean): List<Path> {
    val canonicalRoot = runCatching { root.toRealPath() }.getOrDefault(root)
    return globs.flatMap { glob -> descend(root, glob.split('/').filter { it.isNotEmpty() }) }
        .filter { Files.isRegularFile(it, LinkOption.NOFOLLOW_LINKS) }
        .filter { isInside(canonicalRoot, it) }
        .filter { !freshOnly || modifiedAt(it) >= startedAtMillis - FRESHNESS_TOLERANCE_MILLIS }
}

private fun descend(dir: Path, segments: List<String>): List<Path> = when {
    segments.isEmpty() -> listOf(dir)
    !Files.isDirectory(dir, LinkOption.NOFOLLOW_LINKS) -> emptyList()
    else -> {
        val matcher = dir.fileSystem.getPathMatcher("glob:${segments.first()}")
        children(dir)
            .filter { !Files.isSymbolicLink(it) && matcher.matches(it.fileName) }
            .flatMap { descend(it, segments.drop(1)) }
    }
}

private fun children(dir: Path): List<Path> = try {
    Files.list(dir).use { stream -> stream.toList() }
} catch (error: IOException) {
    runnerLogger.warn("Каталог «$dir» не прочитан: ${error.message}")
    emptyList()
}

/** Лежит ли файл внутри корня по своему реальному пути; битая ссылка — «нет». */
private fun isInside(canonicalRoot: Path, path: Path): Boolean =
    runCatching { path.toRealPath().startsWith(canonicalRoot) }.getOrDefault(false)

/** Отметка изменения файла в миллисекундах; недоступная — ноль, и файл считается старым. */
private fun modifiedAt(path: Path): Long =
    runCatching { Files.getLastModifiedTime(path).toMillis() }.getOrDefault(0)

/**
 * Относительные пути исходников воркспейса для привязки `classname` к файлу (решение 4).
 *
 * Обходятся только исходники, а не всё дерево: каталоги сборки и кешей пропускаются
 * поддеревом — в них нет тестов, зато есть тысячи скомпилированных файлов.
 */
private fun workspaceSources(root: Path): List<String> {
    val sources = mutableListOf<String>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val skip = dir != root && (dir.fileName?.toString() in SKIPPED_DIRS || attrs.isSymbolicLink)
                return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                val extension = file.fileName?.toString()?.substringAfterLast('.', "")
                if (attrs.isRegularFile && !attrs.isSymbolicLink && extension in SOURCE_EXTENSIONS) {
                    sources.add(root.relativize(file).joinToString("/") { it.toString() })
                }
                return FileVisitResult.CONTINUE
            }
        },
    )
    return sources.sorted()
}

/** Текст отказа по причине, когда файл нашёлся, но отчёт из него не получен. */
private fun failureText(command: List<String>, result: CommandResult, failure: ReadReport.Failed): String =
    when (failure.problem) {
        ReportProblem.UNPARSABLE -> TestReportText.failedReport(command, result, BROKEN_REPORT)
        ReportProblem.TOO_LARGE -> TestReportText.failedReport(command, result, oversizedReason(failure.file))
        ReportProblem.UNREADABLE -> TestReportText.failedReport(command, result, unreadableReason(failure.file))
    }

/** Отчёта нет вовсе: команда отработала, но файлов, которые хост умеет читать, не оставила. */
private fun missingReportText(command: List<String>, result: CommandResult): String =
    TestReportText.failedReport(command, result, MISSING_REPORT)

private fun oversizedReason(file: Path?): String =
    "файл отчёта «${file ?: "неизвестный"}» превышает предел $MAX_REPORT_FILE_BYTES байт: отчёт не читается"

private fun unreadableReason(file: Path?): String =
    "файл отчёта «${file ?: "неизвестный"}» не прочитан"

/** Каталоги, обход которых для поиска исходников бессмысленен. */
private val SKIPPED_DIRS: Set<String> = setOf(".git", "build", "target", ".gradle", ".kotlin", "out", "node_modules")

/** Расширения файлов, в которых хост ищет объявление тестового класса. */
private val SOURCE_EXTENSIONS: Set<String> = setOf("kt", "java", "kts")
