package dev.aide.tools.sandbox

import dev.aide.domain.MAX_REPORT_ENTRIES
import dev.aide.domain.TestState
import dev.aide.tools.ToolsWorkspace
import kotlin.io.path.absolute
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.10: разбор JUnit XML и Checkstyle XML в доменную структуру.
 *
 * Проверяется наблюдаемый факт: падение находит свой файл, замечание — свою строку
 * и правило, а документ с внешней сущностью не читает файл хоста (иначе разбор отчёта
 * из воркспейса агента превратился бы в XXE/SSRF).
 */
class ReportParserTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `зелёный прогон даёт GREEN без падений`() {
        val report = assertNotNull(ReportParser.parseTests(greenXml().toByteArray(), SOURCES))

        assertEquals(TestState.GREEN, report.state)
        assertTrue(report.failures.isEmpty(), "падений быть не должно: ${report.failures}")
        assertNull(report.lintFindings, "разбор тестов замечаний не даёт")
    }

    @Test
    fun `падение и ошибка дают RED с привязкой к файлу, пропущенный — нет`() {
        val report = assertNotNull(ReportParser.parseTests(failingXml().toByteArray(), SOURCES))

        assertEquals(TestState.RED, report.state)
        assertEquals(2, report.failures.size, "падение и ошибка — два падения: ${report.failures}")
        val failing = report.failures.single { it.name == "dev.aide.SampleTest.падает" }
        assertEquals("src/test/kotlin/dev/aide/SampleTest.kt", failing.file, "класс обязан найти свой файл")
        assertEquals("ожидалось 1", failing.message)
        assertTrue(
            report.failures.none { it.name.contains("пропущен") },
            "пропущенный тест падением не считается: ${report.failures}",
        )
    }

    @Test
    fun `вложенный класс находит файл внешнего`() {
        val report = assertNotNull(ReportParser.parseTests(nestedClassXml().toByteArray(), SOURCES))

        assertEquals("src/test/kotlin/dev/aide/SampleTest.kt", report.failures.single().file)
    }

    @Test
    fun `неизвестный класс оставляет файл пустым, а не выдумывает путь`() {
        val report = assertNotNull(ReportParser.parseTests(failingXml().toByteArray(), SOURCES))

        val stranger = report.failures.single { it.name.contains("OtherTest") }
        assertNull(stranger.file, "пути такого файла в воркспейсе нет — честное «неизвестно»")
    }

    @Test
    fun `документ без тестов даёт SKIPPED`() {
        val report = assertNotNull(ReportParser.parseTests("""<testsuite name="s"/>""".toByteArray(), SOURCES))

        assertEquals(TestState.SKIPPED, report.state)
    }

    @Test
    fun `checkstyle даёт замечания с файлом, строкой и правилом`() {
        val report = assertNotNull(ReportParser.parseLint(checkstyleXml(), workspace.root))

        val finding = report.lintFindings?.single()
        assertNotNull(finding)
        assertEquals("src/main/App.kt", finding.file, "абсолютный путь отчёта обязан стать относительным")
        assertEquals(4, finding.line)
        assertEquals("detekt.MagicNumber", finding.rule)
        assertEquals("Магическое число", finding.message)
        assertNull(report.state, "разбор линтера о состоянии тестов ничего не говорит")
    }

    @Test
    fun `битый XML и пустой файл не бросают исключение`() {
        assertNull(ReportParser.parseTests("<testsuite><testcase".toByteArray(), SOURCES), "битый XML")
        assertNull(ReportParser.parseTests(ByteArray(0), SOURCES), "пустой файл")
        assertNull(ReportParser.parseLint(ByteArray(0), workspace.root), "пустой отчёт линтера")
    }

    @Test
    fun `список падений сверх предела обрезается и помечается`() {
        val xml = manyFailuresXml(MAX_REPORT_ENTRIES + 10)

        val report = assertNotNull(ReportParser.parseTests(xml.toByteArray(), SOURCES))

        assertEquals(MAX_REPORT_ENTRIES, report.failures.size, "список ограничен пределом")
        assertTrue(report.failuresTruncated, "обрезка обязана быть помечена")
        assertTrue(report.truncated)
        assertFalse(report.lintTruncated, "обрезка тестов — не обрезка линтера")
    }

    @Test
    fun `документ с внешней сущностью не читает файл`() {
        val secret = workspace.write("secret.txt", MARKER)
        val xml = """<?xml version="1.0"?>""" +
            """<!DOCTYPE testsuite [<!ENTITY xxe SYSTEM "file://${secret.absolute()}">]>""" +
            """<testsuite name="s"><testcase name="a" classname="dev.aide.SampleTest">""" +
            """<failure>стек &xxe; конец</failure></testcase></testsuite>"""

        val report = ReportParser.parseTests(xml.toByteArray(), SOURCES)

        val leaked = report?.failures?.any { it.message?.contains(MARKER) == true } ?: false
        assertFalse(leaked, "внешняя сущность не должна попасть в отчёт: $report")
    }

    private fun greenXml(): String = """
        <testsuite name="dev.aide.SampleTest" tests="2" failures="0" errors="0" skipped="0">
          <testcase name="один" classname="dev.aide.SampleTest" time="0.01"/>
          <testcase name="два" classname="dev.aide.SampleTest" time="0.01"/>
        </testsuite>
    """.trimIndent()

    private fun failingXml(): String = """
        <testsuite name="s" tests="3" failures="1" errors="1" skipped="1">
          <testcase name="падает" classname="dev.aide.SampleTest" time="0.01">
            <failure message="ожидалось 1">стек</failure>
          </testcase>
          <testcase name="ошибка" classname="dev.aide.OtherTest" time="0.01">
            <error message="NullPointer">стек</error>
          </testcase>
          <testcase name="пропущен" classname="dev.aide.SampleTest" time="0.01">
            <skipped/>
          </testcase>
        </testsuite>
    """.trimIndent()

    private fun nestedClassXml(): String = """
        <testsuite name="s"><testcase name="вложенный" classname="dev.aide.SampleTest${'$'}Inner">
          <failure message="провал"/>
        </testcase></testsuite>
    """.trimIndent()

    /** Отчёт с [count] упавшими тестами: нужен, чтобы перейти предел списка. */
    private fun manyFailuresXml(count: Int): String = buildString {
        append("""<testsuite name="s" tests="$count" failures="$count">""")
        repeat(count) { index ->
            append("""<testcase name="тест$index" classname="dev.aide.SampleTest">""")
            append("""<failure message="провал"/></testcase>""")
        }
        append("</testsuite>")
    }

    private fun checkstyleXml(): ByteArray {
        val file = workspace.root.resolve("src/main/App.kt")
        file.parent.createDirectories()
        file.writeText("val x = 42\n")
        return """
            <checkstyle version="8.0">
              <file name="${file.absolute()}">
                <error line="4" column="1" severity="warning" message="Магическое число" source="detekt.MagicNumber"/>
              </file>
            </checkstyle>
        """.trimIndent().toByteArray()
    }

    private companion object {

        const val MARKER: String = "СЕКРЕТ-ХОСТА"

        /** Файлы воркспейса для привязки `classname`: файл существует, а его имя видно парсеру. */
        val SOURCES: List<String> = listOf("src/test/kotlin/dev/aide/SampleTest.kt", "src/main/kotlin/dev/aide/App.kt")
    }
}
