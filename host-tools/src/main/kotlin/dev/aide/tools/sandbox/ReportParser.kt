package dev.aide.tools.sandbox

import dev.aide.domain.LintFinding
import dev.aide.domain.MAX_REPORT_ENTRIES
import dev.aide.domain.TestFailure
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import java.io.ByteArrayInputStream
import java.nio.file.Path
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Разбор XML-отчётов тестов и линтера (T-1.10, решение 2).
 *
 * Форматы выбраны потому, что их уже производят Gradle/Maven (JUnit XML) и
 * detekt/ktlint/checkstyle/eslint (Checkstyle XML): новый формат означал бы свой
 * генератор отчёта в каждом инструменте прогона.
 *
 * Разбор прекращается без исключения: битый отчёт — `null`, а не падение инструмента.
 * Отчёт лежит в воркспейсе, который пишет агент, поэтому XML разбирается **только**
 * с запретом DOCTYPE и внешних сущностей ([secureXml]): иначе наивный парсер превратил
 * бы разбор в XXE/SSRF — читал бы файлы хоста или ходил в сеть.
 */
internal object ReportParser {

    /**
     * Разбирает JUnit XML; [sources] — относительные пути файлов воркспейса для привязки
     * `classname` к файлу.
     *
     * @return отчёт или `null`, если документ не разобран.
     */
    fun parseTests(xml: ByteArray, sources: List<String>): TestReport? {
        val document = secureXml(xml) ?: return null
        val testcases = document.getElementsByTagName(TESTCASE)
        var total = 0
        val failures = mutableListOf<TestFailure>()
        for (index in 0 until testcases.length) {
            val testcase = testcases.item(index) as? Element ?: continue
            total += 1
            val problem = problemOf(testcase)
            if (problem != null) {
                failures += TestFailure(
                    name = testName(testcase),
                    file = sourceFile(testcase.getAttribute(CLASSNAME), sources),
                    message = problem,
                )
            }
        }
        val limited = limited(failures)
        return TestReport(
            state = testState(failures.isNotEmpty(), total),
            failures = limited.entries,
            failuresTruncated = limited.truncated,
        )
    }

    /**
     * Разбирает Checkstyle XML; [root] нужен, чтобы сократить абсолютные пути отчёта
     * до путей относительно корня воркспейса (так их видит агент и ревью).
     */
    fun parseLint(xml: ByteArray, root: Path): TestReport? {
        val document = secureXml(xml) ?: return null
        val files = document.getElementsByTagName(FILE)
        val findings = mutableListOf<LintFinding>()
        for (index in 0 until files.length) {
            val file = files.item(index) as? Element ?: continue
            findings += findingsOf(file, relativeTo(file.getAttribute(NAME), root))
        }
        val limited = limited(findings)
        return TestReport(lintFindings = limited.entries, lintTruncated = limited.truncated)
    }

    private fun findingsOf(file: Element, relative: String): List<LintFinding> {
        val errors = file.getElementsByTagName(ERROR)
        return (0 until errors.length).mapNotNull { index ->
            val error = errors.item(index) as? Element ?: return@mapNotNull null
            LintFinding(
                file = relative,
                line = error.getAttribute(LINE).toIntOrNull(),
                rule = error.getAttribute(SOURCE).takeIf { it.isNotBlank() },
                message = error.getAttribute(MESSAGE).takeIf { it.isNotBlank() },
            )
        }
    }

    /** Состояние тестов: падение важнее всего, отсутствие тестов — `SKIPPED`, иначе `GREEN`. */
    private fun testState(hasFailures: Boolean, total: Int): TestState = when {
        hasFailures -> TestState.RED
        total == 0 -> TestState.SKIPPED
        else -> TestState.GREEN
    }

    /** Сообщение упавшего теста: `<failure>` или `<error>`; `<skipped>` падением не считается. */
    private fun problemOf(testcase: Element): String? = children(testcase)
        .firstOrNull { it.tagName == FAILURE || it.tagName == ERROR }
        ?.let { element ->
            element.getAttribute(MESSAGE).takeIf { it.isNotBlank() }
                ?: element.textContent?.trim()?.takeIf { it.isNotBlank() }
        }

    private fun testName(testcase: Element): String {
        val name = testcase.getAttribute(NAME)
        val className = testcase.getAttribute(CLASSNAME)
        return when {
            className.isBlank() -> name
            name.isBlank() -> className
            else -> "$className.$name"
        }
    }

    /**
     * Привязка `classname` к файлу воркспейса (решение 4).
     *
     * Пакет превращается в каталоги, последний сегмент — в имя файла; среди [sources]
     * ищется путь, оканчивающийся на этот хвост. Не нашлось — `null`: «неизвестно»
     * честнее выдуманного пути. Вложенные классы (`Класс$Вложенный`) сводятся к внешнему:
     * они лежат в том же файле. Это эвристика, и план это признаёт.
     */
    private fun sourceFile(classname: String, sources: List<String>): String? {
        val outer = classname.substringBefore('$')
        if (outer.isBlank()) return null
        val tail = outer.replace('.', '/')
        return sources.firstOrNull { source -> matchesSource(source, tail) }
    }

    /** Путь из отчёта относительно корня: отчёт может нести абсолютный путь файла. */
    private fun relativeTo(name: String, root: Path): String {
        if (name.isBlank()) return name
        return runCatching {
            val path = Path.of(name)
            if (path.isAbsolute) root.relativize(path.normalize()).joinToString("/") { it.toString() } else name
        }.getOrElse { name }
    }

    private const val TESTCASE: String = "testcase"
    private const val CLASSNAME: String = "classname"
    private const val FILE: String = "file"
    private const val ERROR: String = "error"
    private const val FAILURE: String = "failure"
    private const val NAME: String = "name"
    private const val MESSAGE: String = "message"
    private const val LINE: String = "line"
    private const val SOURCE: String = "source"
}

/** Совпадает ли путь файла с хвостом «пакет/Класс»; расширение файла не важно. */
private fun matchesSource(source: String, tail: String): Boolean {
    val withoutExtension = source.substringBeforeLast('.', source)
    return withoutExtension == tail || withoutExtension.endsWith("/$tail")
}

/** Записи с пределом: всё, что сверх [MAX_REPORT_ENTRIES], отбрасывается с пометкой. */
private fun <T> limited(entries: List<T>): Limited<T> =
    if (entries.size <= MAX_REPORT_ENTRIES) {
        Limited(entries, truncated = false)
    } else {
        Limited(entries.take(MAX_REPORT_ENTRIES), truncated = true)
    }

private fun children(element: Element): List<Element> {
    val nodes = element.childNodes
    return (0 until nodes.length).mapNotNull { index ->
        (nodes.item(index) as? Element)
    }
}

/**
 * Разбор с обязательным запретом DOCTYPE и внешних сущностей.
 *
 * `disallow-doctype-decl` отклоняет документ с `<!DOCTYPE …>` целиком — вместе с любыми
 * сущностями; три остальных настройки закрывают разбор по частям, если фабрику заменят.
 * `ACCESS_EXTERNAL_DTD`/`ACCESS_EXTERNAL_SCHEMA` пусты: внешних схем и DTD парсер не тянет.
 */
private fun secureXml(xml: ByteArray): Document? = runCatching {
    val factory = DocumentBuilderFactory.newInstance()
    factory.setFeature(DISALLOW_DOCTYPE, true)
    factory.setFeature(EXTERNAL_GENERAL_ENTITIES, false)
    factory.setFeature(EXTERNAL_PARAMETER_ENTITIES, false)
    factory.setFeature(LOAD_EXTERNAL_DTD, false)
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "")
    factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "")
    factory.isXIncludeAware = false
    factory.isExpandEntityReferences = false
    factory.newDocumentBuilder().parse(ByteArrayInputStream(xml))
}.getOrNull()

/** Список с пределом и признаком, что показано не всё. */
private class Limited<T>(val entries: List<T>, val truncated: Boolean)

private const val DISALLOW_DOCTYPE: String = "http://apache.org/xml/features/disallow-doctype-decl"
private const val EXTERNAL_GENERAL_ENTITIES: String = "http://xml.org/sax/features/external-general-entities"
private const val EXTERNAL_PARAMETER_ENTITIES: String = "http://xml.org/sax/features/external-parameter-entities"
private const val LOAD_EXTERNAL_DTD: String = "http://apache.org/xml/features/nonvalidating/load-external-dtd"
