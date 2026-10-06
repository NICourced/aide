package dev.aide.tools.sandbox

import dev.aide.domain.TestState
import dev.aide.tools.ToolsWorkspace
import java.nio.file.Files
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.10: запуск команды проекта, поиск отчёта и разбор — целиком (решение 1).
 *
 * Настоящий Gradle в тестах не запускается (О-11): фикстура-«проект» содержит
 * исполняемый `gradlew`, который сам пишет отчёт и возвращает нужный код. Так
 * проверяется весь путь — команда, поиск отчёта по глобу, разбор, состояние, —
 * а сеть и кеш зависимостей не нужны.
 */
class TestRunnerTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `зелёный прогон разбирается в отчёт с состоянием GREEN`() {
        runBlocking {
            gradlew(reportScript(failures = 0, exitCode = 0))

            val result = assertIs<TestRunResult.Reported>(TestRunner().runTests(workspace.root))

            assertEquals(TestState.GREEN, result.report.state)
            assertTrue(result.report.failures.isEmpty(), "${result.report.failures}")
            assertTrue(result.text.contains("тесты: пройдены"), "сводка обязана назвать исход: ${result.text}")
        }
    }

    @Test
    fun `красный прогон несёт упавший тест с файлом`() {
        runBlocking {
            workspace.write("src/test/kotlin/dev/aide/SampleTest.kt", "class SampleTest\n")
            gradlew(reportScript(failures = 1, exitCode = 1))

            val result = assertIs<TestRunResult.Reported>(TestRunner().runTests(workspace.root))

            assertEquals(TestState.RED, result.report.state)
            val failure = result.report.failures.single()
            assertEquals("dev.aide.SampleTest.падает", failure.name)
            assertEquals("src/test/kotlin/dev/aide/SampleTest.kt", failure.file)
            assertTrue(result.text.contains("упал:"), "сводка обязана назвать падение: ${result.text}")
        }
    }

    @Test
    fun `отчёт не найден — инфраструктурная ошибка с выводом команды`() {
        runBlocking {
            gradlew("#!/bin/sh\necho сломалось-в-выводе\nexit 1\n")

            val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

            assertTrue(result.text.contains("отчёт не найден"), result.text)
            assertTrue(result.text.contains("сломалось-в-выводе"), "модель обязана увидеть вывод: ${result.text}")
        }
    }

    @Test
    fun `битый отчёт — инфраструктурная ошибка, а не падение разбора`() {
        runBlocking {
            gradlew(reportScriptBody("<testsuite><testcase", exitCode = 0))

            val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

            assertTrue(result.text.contains("отчёт не разобран"), result.text)
        }
    }

    @Test
    fun `незнакомый проект запускать нечего`() {
        runBlocking {
            workspace.write("package.json", "{}")

            assertIs<TestRunResult.Unrecognized>(TestRunner().runTests(workspace.root))
        }
    }

    @Test
    fun `линтер разбирается из detekt-отчёта`() {
        runBlocking {
            gradlew(lintScript())
            val source = workspace.root.resolve("src/main/App.kt")
            workspace.write("src/main/App.kt", "val x = 42\n")

            val result = assertIs<TestRunResult.Reported>(TestRunner().runLint(workspace.root))

            val finding = result.report.lintFindings?.single()
            assertEquals("src/main/App.kt", finding?.file)
            assertEquals("detekt.MagicNumber", finding?.rule)
            assertTrue(result.text.contains("замечаний линтера: 1"), result.text)
            assertTrue(source.toFile().exists(), "исходник остаётся на месте")
        }
    }

    @Test
    fun `код возврата не ноль без падений не даёт GREEN`() {
        runBlocking {
            gradlew(reportScript(failures = 0, exitCode = 1))

            val result = assertIs<TestRunResult.Reported>(TestRunner().runTests(workspace.root))

            assertEquals(TestState.INFRA_ERROR, result.report.state, "код ≠ 0 без падений — прогон не дошёл до тестов")
        }
    }

    @Test
    fun `старый отчёт при коде ноль — это нормальный up-to-date прогон`() {
        runBlocking {
            val stale = workspace.write("build/test-results/test/TEST-old.xml", reportXml(0))
            Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - STALE_AGE_MILLIS))
            // Up-to-date задача Gradle отчёт не переписывает, а команда завершается нулём.
            gradlew("#!/bin/sh\nexit 0\n")

            val result = assertIs<TestRunResult.Reported>(TestRunner().runTests(workspace.root))

            assertEquals(TestState.GREEN, result.report.state, "непереписанный отчёт отражает последний результат")
        }
    }

    @Test
    fun `отчёт прошлого прогона не считается отчётом нового`() {
        runBlocking {
            val stale = workspace.write("build/test-results/test/TEST-old.xml", reportXml(0))
            Files.setLastModifiedTime(stale, FileTime.fromMillis(System.currentTimeMillis() - STALE_AGE_MILLIS))
            gradlew("#!/bin/sh\necho сборка упала\nexit 1\n")

            val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

            assertTrue(
                result.text.contains("отчёт не найден"),
                "старый отчёт не должен выдаваться за новый: ${result.text}",
            )
        }
    }

    @Test
    fun `отчёт сверх предела не читается и не роняет хост`() {
        runBlocking {
            gradlew(oversizedScript(MAX_REPORT_FILE_BYTES + 1))

            val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

            assertTrue(result.text.contains("превышает предел"), "причина обязана быть названа: ${result.text}")
        }
    }

    @Test
    fun `суммарный предел отчётов за прогон тоже действует`() {
        runBlocking {
            // Каждый файл под персональным пределом, но вместе они перебирают общий.
            gradlew(totalLimitScript(FILE_PAD_BYTES))

            val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

            assertTrue(result.text.contains("превышает предел"), "причина обязана быть названа: ${result.text}")
        }
    }

    @Test
    fun `симлинк-каталог наружу не читается`() {
        runBlocking {
            val outside = Files.createTempDirectory("aide-outside").toRealPath()
            try {
                val report = outside.resolve("test-results/test/TEST-out.xml")
                report.parent.createDirectories()
                report.writeText(reportXml(0))
                // Отметка свежая: иначе файл отсеялся бы по давности, и проверка симлинка
                // прошла бы, ничего не доказав.
                Files.setLastModifiedTime(report, FileTime.fromMillis(System.currentTimeMillis()))
                Files.createSymbolicLink(workspace.root.resolve("build"), outside)
                gradlew("#!/bin/sh\nexit 0\n")

                val result = assertIs<TestRunResult.Infrastructure>(TestRunner().runTests(workspace.root))

                assertTrue(result.text.contains("отчёт не найден"), "файл за корнем не должен читаться: ${result.text}")
            } finally {
                outside.toFile().deleteRecursively()
            }
        }
    }

    /** Пишет исполняемый `gradlew` с телом [body]: без бита запуска детект обёрткой его не признает. */
    private fun gradlew(body: String) {
        val file = workspace.write("gradlew", body).toFile()
        assertTrue(file.setExecutable(true), "не удалось выставить бит запуска на gradlew")
    }

    private fun reportScript(failures: Int, exitCode: Int): String =
        reportScriptBody(reportXml(failures), exitCode)

    /** Скрипт, который кладёт переданный отчёт в каталог Gradle и возвращает код. */
    private fun reportScriptBody(report: String, exitCode: Int): String = """
        #!/bin/sh
        mkdir -p build/test-results/test
        cat > build/test-results/test/TEST-dev.aide.SampleTest.xml <<'XML'
        $report
        XML
        exit $exitCode
    """.trimIndent() + "\n"

    /** Скрипт, который кладёт в каталог отчётов файл ровно на [size] байт. */
    private fun oversizedScript(size: Long): String = """
        #!/bin/sh
        mkdir -p build/test-results/test
        head -c $size /dev/zero > build/test-results/test/TEST-big.xml
        exit 0
    """.trimIndent() + "\n"

    /**
     * Пять валидных XML-отчётов по [padBytes] байт: каждый под персональным пределом,
     * а суммарно они перебирают общий предел прогона.
     */
    private fun totalLimitScript(padBytes: Long): String = """
        #!/bin/sh
        mkdir -p build/test-results/test
        for i in 0 1 2 3 4; do
          { echo '<testsuite name="s">'; yes x | head -c $padBytes; echo '</testsuite>'; } \
              > build/test-results/test/TEST-${'$'}i.xml
        done
        exit 0
    """.trimIndent() + "\n"

    private fun reportXml(failures: Int): String {
        val failing = if (failures == 0) "" else """<failure message="провал"/>"""
        val name = if (failures == 0) "проходит" else "падает"
        return """<testsuite name="dev.aide.SampleTest" tests="1" failures="$failures">""" +
            """<testcase name="$name" classname="dev.aide.SampleTest" time="0.01">$failing</testcase>""" +
            """</testsuite>"""
    }

    /** Скрипт, который кладёт отчёт detekt: замечание с абсолютным путём файла. */
    private fun lintScript(): String {
        val file = workspace.root.resolve("src/main/App.kt")
        return """
            #!/bin/sh
            mkdir -p build/reports/detekt
            cat > build/reports/detekt/detekt.xml <<'XML'
            <checkstyle version="8.0">
              <file name="$file">
                <error line="1" column="5" severity="warning" message="Магия" source="detekt.MagicNumber"/>
              </file>
            </checkstyle>
            XML
            exit 1
        """.trimIndent() + "\n"
    }

    private companion object {

        /** Возраст отчёта прошлого прогона: заведомо больше окна свежести. */
        const val STALE_AGE_MILLIS: Long = 600_000

        /** Размер одного файла в проверке суммарного предела: 5 таких перебирают общий предел. */
        const val FILE_PAD_BYTES: Long = 7_000_000
    }
}
