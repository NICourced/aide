package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.TestState
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.FakeChangeSnapshots
import dev.aide.tools.RecordingToolCalls
import dev.aide.tools.ToolRegistry
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.limits.NetworkPolicy
import dev.aide.tools.testInvoker
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.10: `run_tests` через настоящую точку вызова — права, запуск и отчёт.
 *
 * Наблюдаемые факты, а не «код написан»: при `ASK` файл-маркер не появляется
 * (команда не стартовала), при `ALLOW` — появляется вместе с разобранным отчётом,
 * а инструмент не считается изменяющим репозиторий.
 */
class RunTestsToolTest {

    private val workspace = ToolsWorkspace()
    private val tool = RunTestsTool()
    private val runId = RunId("run-tests")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `при ASK тесты не запускаются`() {
        runBlocking {
            gradlew()

            val result = invoke(stored = { null })

            assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
            assertFalse(ran(), "при ASK команда не должна запускаться: $MARKER")
        }
    }

    @Test
    fun `при ALLOW отчёт разобран, а инструмент не изменяет репозиторий`() {
        runBlocking {
            gradlew()
            val journal = RecordingToolCalls()
            val snapshots = FakeChangeSnapshots()

            val result = invoke(stored = allowAll, journal = journal, snapshots = snapshots)

            assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
            val report = assertNotNull(result.testReport, "разобранный отчёт обязан вернуться точкой вызова")
            assertEquals(TestState.GREEN, report.state)
            assertTrue(ran(), "при ALLOW команда обязана запуститься")
            assertFalse(tool.changesRepository, "тесты не меняют репозиторий: коммита шага быть не должно")
            assertEquals(0, snapshots.asked, "точка отката для не-изменяющего вызова не спрашивается")
            assertEquals(ToolOutcome.SUCCESS, journal.calls.single().outcome)
        }
    }

    @Test
    fun `таймаут инструмента с отчётом помечается состоянием TIMEOUT`() {
        runBlocking {
            gradlew("#!/bin/sh\nsleep 30\n")
            val slow = RunTestsTool(timeoutMillis = TIMEOUT_MILLIS)

            val result = invoke(tool = slow, stored = allowAll)

            assertEquals(ToolOutcome.TIMEOUT, result.outcome, result.text)
            assertEquals(TestState.TIMEOUT, result.testReport?.state, "отчёт обязан назвать исход таймаута")
        }
    }

    @Test
    fun `обычный инструмент на таймауте отчёта не несёт`() {
        runBlocking {
            val command = RunCommandTool(NetworkPolicy(), timeoutMillis = TIMEOUT_MILLIS)

            val result = testInvoker(
                registry = ToolRegistry(listOf(command)),
                stored = allowAll,
            ).invoke(runId, RunCommandTool.TOOL_NAME, """{"command":["sleep","30"]}""", workspace.context)

            assertEquals(ToolOutcome.TIMEOUT, result.outcome, result.text)
            assertNull(result.testReport, "у инструмента без отчёта таймаут отчёта не порождает")
        }
    }

    @Test
    fun `незнакомый проект — явный отказ без догадки`() {
        runBlocking {
            workspace.write("package.json", "{}")

            val result = invoke(stored = allowAll)

            assertEquals(ToolOutcome.FAILURE, result.outcome, result.text)
            assertNull(result.testReport, "запускать было нечего — и отчёта нет")
            assertTrue(result.text.contains("run_command"), "модель обязана получить подсказку: ${result.text}")
        }
    }

    private suspend fun invoke(
        stored: (String) -> ToolPermission?,
        journal: RecordingToolCalls = RecordingToolCalls(),
        snapshots: FakeChangeSnapshots = FakeChangeSnapshots(),
        tool: RunTestsTool = this.tool,
    ) = testInvoker(
        registry = ToolRegistry(listOf(tool)),
        recorder = journal,
        stored = stored,
        snapshots = snapshots,
    ).invoke(runId, RunTestsTool.TOOL_NAME, "{}", workspace.context)

    /** Исполняемая «обёртка Gradle»; по умолчанию помечает запуск и пишет зелёный отчёт. */
    private fun gradlew(body: String = GREEN_SCRIPT) {
        assertTrue(workspace.write("gradlew", body).toFile().setExecutable(true), "бит запуска gradlew")
    }

    private fun ran(): Boolean = workspace.root.resolve(MARKER).exists()

    private companion object {

        /** Файл-маркер: его появление доказало бы, что отклонённая команда всё-таки стартовала. */
        const val MARKER: String = "ran-tests.txt"

        /** Предел времени, за который зависшая команда гарантированно не ответит. */
        const val TIMEOUT_MILLIS: Long = 300

        /** Настройки прав, разрешающие запуск: без них вызов упрётся в объявленное умолчание ASK. */
        val allowAll: (String) -> ToolPermission = { name ->
            ToolPermission(tool = name, read = Permission.ALLOW, write = Permission.ALLOW)
        }

        /** Обычная обёртка: отмечает запуск маркером и кладёт зелёный отчёт. */
        val GREEN_SCRIPT: String = """
            #!/bin/sh
            touch $MARKER
            mkdir -p build/test-results/test
            cat > build/test-results/test/TEST-dev.aide.SampleTest.xml <<'XML'
            <testsuite name="dev.aide.SampleTest" tests="1" failures="0">
              <testcase name="проходит" classname="dev.aide.SampleTest" time="0.01"/>
            </testsuite>
            XML
            exit 0
        """.trimIndent() + "\n"
    }
}
