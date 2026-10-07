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
import dev.aide.domain.SnapshotRef
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.ports.ChangeSnapshot
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.8 в движке: изменение файла через шаг прогона и попадание точки отката в прогон.
 *
 * Модель скриптованная (О-11), файлы — настоящие. Проверяется то, чего не видно в точке
 * вызова: отказ не ломает прогон, ссылка снапшота доходит до `AgentRun.snapshots` (иначе
 * защита снапшотов незакрытой задачи её не увидит), а повторная ссылка не дублируется.
 */
class AgentRunEngineWriteTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val workspace: Path = Files.createTempDirectory("aide-engine-write").toRealPath()
    private val journal = RecordingToolCalls()
    private val snapshots = EngineChangeSnapshots()

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
        stored: (String) -> ToolPermission? = writeAllowed,
    ): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(llm),
        planner = RunPlanner { _, _, _ -> plan("шаг") },
        tools = stepToolsIn(workspace, journal, stored, snapshots = snapshots),
        repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    private suspend fun run(llm: LlmClient, stored: (String) -> ToolPermission? = writeAllowed) {
        val engine = engine(llm, stored)
        engine.postTask("Поправь файл", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs))
    }

    @Test
    fun `при разрешении записи файл меняется, и точка отката попадает в прогон`() {
        runBlocking {
            file("src/App.kt", "старое\n")
            val llm = ScriptedLlmClient(listOf(call("write_file", APP_ARGUMENTS), text("готово")))

            run(llm)

            assertEquals("новое\n", workspace.resolve("src/App.kt").readText(), "агент обязан изменить файл")
            val run = runs.all().single()
            assertEquals(RunState.FINISHED, run.state)
            assertEquals(
                listOf(SnapshotRef(ENGINE_SNAPSHOT_REF)),
                run.snapshots,
                "ссылка обязана попасть в прогон: по ней защита снапшотов отличает свои от вытесняемых",
            )
            assertEquals(ToolOutcome.SUCCESS, journal.calls.single { it.tool == WRITE_FILE }.outcome)
        }
    }

    @Test
    fun `при ASK файл не меняется, отказ виден модели, прогон доходит до finished`() {
        runBlocking {
            file("src/App.kt", "старое\n")
            val llm = ScriptedLlmClient(listOf(call("write_file", APP_ARGUMENTS), text("ладно")))

            // Настроек нет: у записи объявленное умолчание ASK, а диалога подтверждения (T-1.13) ещё нет.
            run(llm, stored = { null })

            assertEquals(
                "старое\n",
                workspace.resolve("src/App.kt").readText(),
                "без подтверждения файл трогать нельзя",
            )
            val tool = llm.requests[1].messages.last { it.role == LlmRole.TOOL }
            assertTrue(
                tool.content.contains("подтверждени"),
                "модель обязана увидеть причину отказа: ${tool.content}",
            )
            assertEquals(
                RunState.FINISHED,
                runs.all().single().state,
                "отказ не ломает прогон: он обязан дойти до конца",
            )
            assertEquals(0, snapshots.asked, "отказ по правам снапшот не ставит")
            assertTrue(journal.calls.single { it.tool == WRITE_FILE }.requiredApproval)
        }
    }

    @Test
    fun `две записи в одном шаге оставляют в прогоне одну точку отката`() {
        runBlocking {
            val llm = ScriptedLlmClient(
                listOf(
                    LlmResponse.Text(
                        text = "",
                        cost = Cost(amountMicros = 0, known = true),
                        elapsedMillis = 1,
                        toolCalls = listOf(
                            LlmToolCall(CALL_ID, "write_file", """{"path":"a.txt","content":"первый\n"}"""),
                            LlmToolCall(SECOND_CALL_ID, "write_file", """{"path":"b.txt","content":"второй\n"}"""),
                        ),
                    ),
                    text("готово"),
                ),
            )

            run(llm)

            assertEquals(2, journal.calls.count { it.tool == WRITE_FILE }, "обе записи обязаны выполниться")
            assertEquals("первый\n", workspace.resolve("a.txt").readText())
            assertEquals("второй\n", workspace.resolve("b.txt").readText())
            assertEquals(
                listOf(SnapshotRef(ENGINE_SNAPSHOT_REF)),
                runs.all().single().snapshots,
                "до коммита шага HEAD не двигается: вторая ссылка — дубль той же точки отката",
            )
        }
    }

    @Test
    fun `без коммита изменяющие вызовы отклонены, читающие выполнены, прогон завершается`() {
        runBlocking {
            snapshots.answer(ChangeSnapshot.NoHead)
            file("read.txt", "данные\n")
            val llm = ScriptedLlmClient(
                listOf(
                    LlmResponse.Text(
                        text = "",
                        cost = Cost(amountMicros = 0, known = true),
                        elapsedMillis = 1,
                        toolCalls = listOf(
                            LlmToolCall(CALL_ID, "read_file", """{"path":"read.txt"}"""),
                            LlmToolCall(SECOND_CALL_ID, "write_file", APP_ARGUMENTS),
                        ),
                    ),
                    text("готово"),
                ),
            )

            run(llm)

            val results = llm.requests[1].messages.filter { it.role == LlmRole.TOOL }
            assertTrue(results[0].content.contains("данные"), "чтение без коммита разрешено: ${results[0].content}")
            assertTrue(
                results[1].content.contains("коммита"),
                "запись без точки отката запрещена: ${results[1].content}",
            )
            assertFalse(workspace.resolve("src/App.kt").exists(), "файл без точки отката не создаётся")
            assertTrue(runs.all().single().snapshots.isEmpty(), "снапшота нет — и это видно в прогоне")
            assertEquals(RunState.FINISHED, runs.all().single().state)
        }
    }

    private companion object {

        const val CALL_ID: String = "call-1"
        const val SECOND_CALL_ID: String = "call-2"

        /** Имя записывающего инструмента: по нему вызов отличается от внутренней фиксации шага. */
        const val WRITE_FILE: String = "write_file"

        const val APP_ARGUMENTS: String = """{"path":"src/App.kt","content":"новое\n"}"""

        /** Настройки прав, разрешающие запись: без них вызов упрётся в ASK раньше файла. */
        val writeAllowed: (String) -> ToolPermission? = { name ->
            ToolPermission(tool = name, read = Permission.ALLOW, write = Permission.ALLOW)
        }
    }
}

/** Ответ модели с одним вызовом инструмента: так модель просит работу. */
private fun call(tool: String, arguments: String): LlmResponse.Text = LlmResponse.Text(
    text = "",
    cost = Cost(amountMicros = 0, known = true),
    elapsedMillis = 1,
    toolCalls = listOf(LlmToolCall(id = "call-1", name = tool, arguments = arguments)),
)
