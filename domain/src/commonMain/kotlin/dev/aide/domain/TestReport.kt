package dev.aide.domain

import kotlinx.serialization.Serializable

/**
 * Сколько записей падений и замечаний несёт отчёт прогона (T-1.10).
 *
 * Число с причиной: отчёт едет в событии `RunStateChanged` (О-5) и лежит в `payload`
 * прогона, а список падений большого проекта измеряется тысячами — их хватит, чтобы
 * раздуть кадр и базу. Пятьдесят записей дают модели и ревью картину «что именно
 * сломалось», а полный список всегда доступен в самом файле отчёта. Показанное
 * не всё помечается [TestReport.failuresTruncated], а не умалчивается: обрезанный
 * список не должен выглядеть исчерпывающим.
 */
const val MAX_REPORT_ENTRIES: Int = 50

/**
 * Разобранный отчёт тестов и линтера прогона (T-1.10, FR-TOOLS-12).
 *
 * Две части независимы: тесты и линтер запускаются разными инструментами, и отчёт
 * каждого — отдельное обновление одного и того же прогона. Поэтому часть, которой
 * инструмент не касался, помечена `null` («этот отчёт о ней не говорит»), а не пустым
 * списком: пустой список — это честный результат «линтер чист, замечаний нет», и
 * путать его с «линтер не запускался» значило бы стирать прежние замечания при каждом
 * прогоне тестов. Слияние частей выполняет [merge] в домене.
 *
 * Признак обрезки у каждой части свой: прогон тестов, показавший не все падения,
 * не делает обрезанным список замечаний линтера, и наоборот — единый флаг оставался бы
 * взведённым после чистого прогона и врал бы в интерфейсе (T-1.10, решение 6).
 *
 * Поле аддитивное: у `AgentRun`, записанного до T-1.10, отчёта нет вовсе.
 */
@Serializable
data class TestReport(
    /** Итог последнего прогона тестов; `null` — тесты в этом отчёте не запускались. */
    val state: TestState? = null,
    /** Упавшие тесты последнего прогона; пусто, если падений нет или тесты не шли. */
    val failures: List<TestFailure> = emptyList(),
    /** Замечания линтера; `null` — линтер в этом отчёте не запускался, пустой список — замечаний нет. */
    val lintFindings: List<LintFinding>? = null,
    /** Падений было больше [MAX_REPORT_ENTRIES]: список усечён. */
    val failuresTruncated: Boolean = false,
    /** Замечаний было больше [MAX_REPORT_ENTRIES]: список усечён. */
    val lintTruncated: Boolean = false,
) {
    /** Показано не всё хотя бы в одной части — этот флаг читает сводка и интерфейс. */
    val truncated: Boolean get() = failuresTruncated || lintTruncated
}

/** Один упавший тест с привязкой к файлу, где он объявлен. */
@Serializable
data class TestFailure(
    /** Имя теста, как его вернул раннер: класс и метод. */
    val name: String,
    /** Путь к файлу теста относительно корня воркспейса; `null` — сопоставить не удалось. */
    val file: String? = null,
    /** Сообщение об ошибке; `null`, если раннер его не дал. */
    val message: String? = null,
)

/** Одно замечание линтера с местом и правилом. */
@Serializable
data class LintFinding(
    /** Путь к файлу относительно корня воркспейса. */
    val file: String,
    /** Номер строки; `null`, если замечание относится к файлу целиком. */
    val line: Int? = null,
    /** Имя правила линтера. */
    val rule: String? = null,
    /** Текст замечания. */
    val message: String? = null,
)

/**
 * Сливает обновление в накопленный отчёт прогона (решение 8).
 *
 * `run_tests` заменяет тестовую часть, `run_lint` — замечания; часть, о которой
 * обновление молчит (`null`), остаётся прежней. Признак обрезки заменяется **только
 * у той части, которую обновление переписало**: чистый прогон тестов сбрасывает
 * собственную пометку и не трогает пометку замечаний линтера.
 */
fun TestReport.merge(update: TestReport): TestReport = copy(
    state = update.state ?: state,
    failures = if (update.state == null) failures else update.failures,
    failuresTruncated = if (update.state == null) failuresTruncated else update.failuresTruncated,
    lintFindings = update.lintFindings ?: lintFindings,
    lintTruncated = if (update.lintFindings == null) lintTruncated else update.lintTruncated,
)

/** Состояние тестов прогона; [TestState.NOT_RUN], если тесты в отчёте не запускались. */
fun TestReport.reportState(): TestState = state ?: TestState.NOT_RUN
