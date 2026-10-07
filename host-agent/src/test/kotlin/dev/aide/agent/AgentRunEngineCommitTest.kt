package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmRole
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.Permission
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.domain.TestState
import dev.aide.tools.CommitStepTool
import dev.aide.tools.ports.StepCommit
import dev.aide.tools.sandbox.RunTestsTool
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.11 в движке: изменяющий шаг заканчивается коммитом, а неизменяющий — нет.
 *
 * Модель скриптованная (О-11), файлы настоящие. Проверяется то, чего не видно
 * в инструменте: движок сам решает, когда коммитить, шлёт ветку задачи и сообщение
 * с номером шага, а `commit_step` модели при этом не показывают.
 */
class AgentRunEngineCommitTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val workspace: Path = Files.createTempDirectory("aide-engine-commit").toRealPath()
    private val journal = RecordingToolCalls()
    private val snapshots = EngineChangeSnapshots()
    private val commits = FakeStepCommits()

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    private fun file(relativePath: String, text: String) {
        val path = workspace.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(text)
    }

    private fun engine(
        llm: LlmClient,
        plan: List<PlanStep> = plan("шаг"),
        stored: (String) -> ToolPermission? = writeAllowed,
    ): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(llm),
        planner = RunPlanner { _, _, _ -> plan },
        tools = stepToolsIn(workspace, journal, stored, commits, snapshots = snapshots),
        repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    private suspend fun run(
        llm: LlmClient,
        plan: List<PlanStep> = plan("шаг"),
        stored: (String) -> ToolPermission? = writeAllowed,
    ) {
        val engine = engine(llm, plan, stored)
        engine.postTask("Поправь файл", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))
    }

    @Test
    fun `изменяющий шаг фиксируется коммитом в ветке задачи сообщением шага`() {
        runBlocking {
            val llm = ScriptedLlmClient(listOf(call("write_file", APP_ARGUMENTS), text("готово")))

            run(llm)

            val branch = tasks.all().single().branch
            assertEquals(
                listOf(branch to "Шаг 1: шаг"),
                commits.calls,
                "ветка обязана прийти из задачи, а сообщение — нести номер шага и его описание",
            )
            assertEquals(
                ToolOutcome.SUCCESS,
                journal.calls.single { it.tool == CommitStepTool.TOOL_NAME }.outcome,
                "фиксация шага обязана попасть в журнал вызовов (FR-AGENT-8)",
            )
        }
    }

    @Test
    fun `шаг без изменений коммита не даёт`() {
        runBlocking {
            file("read.txt", "данные\n")
            val llm = ScriptedLlmClient(listOf(call("read_file", """{"path":"read.txt"}"""), text("готово")))

            run(llm)

            assertTrue(commits.calls.isEmpty(), "читающий шаг не фиксируется: изменять было нечего")
            assertTrue(journal.calls.none { it.tool == CommitStepTool.TOOL_NAME })
        }
    }

    @Test
    fun `два изменяющих шага дают два коммита`() {
        runBlocking {
            val llm = ScriptedLlmClient(
                listOf(
                    call("write_file", """{"path":"a.txt","content":"a\n"}"""),
                    text("готово"),
                    call("write_file", """{"path":"b.txt","content":"b\n"}"""),
                    text("готово"),
                ),
            )

            run(llm, plan("первый", "второй"))

            assertEquals(
                listOf("Шаг 1: первый", "Шаг 2: второй"),
                commits.calls.map { it.second },
                "у каждого изменяющего шага свой коммит — иначе шаги слились бы в один",
            )
        }
    }

    @Test
    fun `в запросе к модели commit_step отсутствует`() {
        runBlocking {
            val llm = ScriptedLlmClient(listOf(text("готово")))

            run(llm)

            val definitions = llm.requests.flatMap { it.tools }.map { it.name }
            assertTrue(
                CommitStepTool.TOOL_NAME !in definitions,
                "внутренний инструмент модели не показывают: $definitions",
            )
            assertTrue("write_file" in definitions, "обычные инструменты обязаны остаться: $definitions")
        }
    }

    @Test
    fun `отказ коммита не роняет прогон, но остаётся в журнале`() {
        runBlocking {
            commits.answer(StepCommit.Refused("commit_failed"))
            val llm = ScriptedLlmClient(listOf(call("write_file", APP_ARGUMENTS), text("готово")))

            run(llm)

            assertEquals(RunState.FINISHED, runs.all().single().state, "сбой коммита не отменяет готовый шаг")
            assertEquals(
                ToolOutcome.FAILURE,
                journal.calls.single { it.tool == CommitStepTool.TOOL_NAME }.outcome,
                "отказ обязан быть виден в журнале, а не проглочен",
            )
        }
    }

    @Test
    fun `отклонённая запись коммита не даёт`() {
        runBlocking {
            file("src/App.kt", "старое\n")
            val llm = ScriptedLlmClient(listOf(call("write_file", APP_ARGUMENTS), text("готово")))

            // Настроек нет: у записи объявленное умолчание ASK, и вызов отклоняется (T-1.13 ещё нет).
            run(llm, stored = { null })

            assertTrue(commits.calls.isEmpty(), "шаг без состоявшегося изменения коммитить нечего")
            assertTrue(
                journal.calls.none { it.tool == CommitStepTool.TOOL_NAME },
                "отклонённая запись не должна порождать фиксацию: ${journal.calls.map { it.tool }}",
            )
            assertEquals(
                ToolOutcome.DENIED,
                journal.calls.single { it.tool == "write_file" }.outcome,
                "отклонённая запись обязана быть в журнале",
            )
        }
    }

    @Test
    fun `модель не может выполнить commit_step — отказ, а коммита нет`() {
        runBlocking {
            file("src/App.kt", "старое\n")
            // Модель зовёт внутренний инструмент по имени: определения ей не показывали,
            // но имя ей известно, и точка вызова обязана отказать с хост-пути без модели.
            val llm = ScriptedLlmClient(
                listOf(
                    call(CommitStepTool.TOOL_NAME, """{"branch":"ai/x","message":"Шаг 1: подмена"}"""),
                    text("готово"),
                ),
            )

            run(llm)

            assertTrue(commits.calls.isEmpty(), "модель не фиксирует шаг: это дело движка")
            assertEquals(
                ToolOutcome.FAILURE,
                journal.calls.single { it.tool == CommitStepTool.TOOL_NAME }.outcome,
                "попытка модели обязана остаться в журнале",
            )
            val refusal = llm.requests[1].messages.last { it.role == LlmRole.TOOL }
            assertTrue(refusal.content.contains("внутренн"), "модель обязана понять отказ: ${refusal.content}")
        }
    }

    @Test
    fun `шаг с прогоном тестов не коммитится, а отчёт доходит до состояния прогона`() {
        runBlocking {
            gradlew()
            val llm = ScriptedLlmClient(listOf(call(RunTestsTool.TOOL_NAME, "{}"), text("готово")))

            run(llm, stored = writeAllowed)

            assertTrue(
                commits.calls.isEmpty(),
                "прогон тестов не изменяет репозиторий: коммита шага после него быть не должно",
            )
            val report = assertNotNull(runs.all().single().testReport, "отчёт обязан попасть в прогон")
            assertEquals(TestState.GREEN, report.state)
            assertEquals(
                ToolOutcome.SUCCESS,
                journal.calls.single { it.tool == RunTestsTool.TOOL_NAME }.outcome,
                "вызов тестов обязан попасть в журнал",
            )
            assertTrue(
                sink.events.any { it.testReport != null },
                "отчёт обязан уехать событием смены состояния прогона",
            )
        }
    }

    /** Исполняемая «обёртка Gradle», которая пишет зелёный отчёт: настоящий Gradle тестам не нужен. */
    private fun gradlew() {
        val script = """
            #!/bin/sh
            mkdir -p build/test-results/test
            cat > build/test-results/test/TEST-dev.aide.SampleTest.xml <<'XML'
            <testsuite name="dev.aide.SampleTest" tests="1" failures="0">
              <testcase name="проходит" classname="dev.aide.SampleTest" time="0.01"/>
            </testsuite>
            XML
            exit 0
        """.trimIndent() + "\n"
        val path = workspace.resolve("gradlew")
        path.writeText(script)
        assertTrue(path.toFile().setExecutable(true), "бит запуска gradlew")
    }

    /** Ответ модели с одним вызовом инструмента: так модель просит работу. */
    private fun call(tool: String, arguments: String): LlmResponse.Text = LlmResponse.Text(
        text = "",
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 1,
        toolCalls = listOf(LlmToolCall(id = CALL_ID, name = tool, arguments = arguments)),
    )

    private companion object {

        const val CALL_ID: String = "call-1"

        /** Аргументы записи: файл и полное новое содержимое. */
        const val APP_ARGUMENTS: String = """{"path":"src/App.kt","content":"новое\n"}"""

        /** Настройки прав, разрешающие запись: без них вызов упрётся в ASK раньше файла. */
        val writeAllowed: (String) -> ToolPermission? = { name ->
            ToolPermission(tool = name, read = Permission.ALLOW, write = Permission.ALLOW)
        }
    }
}
