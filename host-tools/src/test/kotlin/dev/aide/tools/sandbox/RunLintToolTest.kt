package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.TestState
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.FakeChangeSnapshots
import dev.aide.tools.ToolRegistry
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.testInvoker
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.10: `run_lint` через настоящую точку вызова — запуск, разбор и «не изменение».
 *
 * Отдельно от тестов тестов: у линтера своя команда (detekt) и свой отчёт, а замечания
 * в состояние тестов `RED` не переводят — это проверяется здесь же отсутствием состояния.
 */
class RunLintToolTest {

    private val workspace = ToolsWorkspace()
    private val tool = RunLintTool()
    private val runId = RunId("run-lint")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `при ALLOW замечания разобраны, а состояние тестов не тронуто`() {
        runBlocking {
            gradlew()
            val source = workspace.root.resolve("src/main/App.kt")
            val snapshots = FakeChangeSnapshots()

            val result = testInvoker(
                registry = ToolRegistry(listOf(tool)),
                stored = allowAll,
                snapshots = snapshots,
            ).invoke(runId, RunLintTool.TOOL_NAME, "{}", workspace.context)

            assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
            val report = assertNotNull(result.testReport, "разобранный отчёт обязан вернуться точкой вызова")
            assertEquals("src/main/App.kt", report.lintFindings?.single()?.file)
            assertNull(report.state, "линтер не переводит состояние тестов в RED")
            assertFalse(tool.changesRepository, "линтер не меняет репозиторий: коммита шага быть не должно")
            assertEquals(0, snapshots.asked, "точка отката для не-изменяющего вызова не спрашивается")
            assertTrue(source.toFile().exists(), "исходник остался на месте")
        }
    }

    @Test
    fun `таймаут линтера несёт отчёт со состоянием TIMEOUT`() {
        runBlocking {
            gradlew("#!/bin/sh\nsleep 30\n")

            val result = testInvoker(
                registry = ToolRegistry(listOf(RunLintTool(timeoutMillis = TIMEOUT_MILLIS))),
                stored = allowAll,
            ).invoke(runId, RunLintTool.TOOL_NAME, "{}", workspace.context)

            assertEquals(ToolOutcome.TIMEOUT, result.outcome, result.text)
            assertEquals(TestState.TIMEOUT, result.testReport?.state, "отчёт обязан назвать исход таймаута")
        }
    }

    @Test
    fun `незнакомый проект — явный отказ без догадки`() {
        runBlocking {
            workspace.write("package.json", "{}")

            val result = testInvoker(
                registry = ToolRegistry(listOf(tool)),
                stored = allowAll,
            ).invoke(runId, RunLintTool.TOOL_NAME, "{}", workspace.context)

            assertEquals(ToolOutcome.FAILURE, result.outcome, result.text)
            assertTrue(result.text.contains("run_command"), "модель обязана получить подсказку: ${result.text}")
        }
    }

    /** Исполняемая «обёртка Gradle», которая кладёт отчёт detekt с одним замечанием. */
    private fun gradlew(body: String? = null) {
        val file = workspace.root.resolve("src/main/App.kt")
        workspace.write("src/main/App.kt", "val x = 42\n")
        val script = body ?: """
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
        assertTrue(workspace.write("gradlew", script).toFile().setExecutable(true), "бит запуска gradlew")
    }

    private companion object {
        val allowAll: (String) -> ToolPermission = { name ->
            ToolPermission(tool = name, read = Permission.ALLOW, write = Permission.ALLOW)
        }

        /** Предел времени, за который зависшая команда гарантированно не ответит. */
        const val TIMEOUT_MILLIS: Long = 300
    }
}
