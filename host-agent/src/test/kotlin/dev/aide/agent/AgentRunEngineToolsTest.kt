package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmRole
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.ports.RunPorts
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.ToolOutcome
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
 * T-1.7: шаг прогона — цикл «модель просит инструмент, инструмент отвечает».
 *
 * Модель скриптованная (О-11), файлы — настоящие: проверяется, что результат вызова
 * доходит до модели, отказ возвращается ей результатом, а не сбоем прогона, и что
 * зацикленная модель останавливается пределом витков.
 */
class AgentRunEngineToolsTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val workspace: Path = Files.createTempDirectory("aide-engine-tools").toRealPath()
    private val journal = RecordingToolCalls()

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    private fun file(relativePath: String, text: String) {
        val path = workspace.resolve(relativePath)
        path.parent?.createDirectories()
        path.writeText(text)
    }

    private fun engine(llm: LlmClient, plan: List<PlanStep> = plan("шаг")): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(llm),
        planner = RunPlanner { _, _ -> plan },
        tools = stepToolsIn(workspace, journal),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    private suspend fun run(llm: LlmClient) {
        val engine = engine(llm)
        engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext())
    }

    @Test
    fun `модель просит файл, получает содержимое и завершает шаг`() {
        runBlocking {
            file("src/App.kt", "fun main() = Unit\n")
            val llm = ScriptedLlmClient(
                listOf(call("read_file", """{"path":"src/App.kt"}"""), text("готово")),
            )

            run(llm)

            assertEquals(RunState.FINISHED, runs.all().single().state)
            assertEquals(2, llm.callCount, "после вызова инструмента модель спрашивается снова")

            // Диалог продолжается, а не начинается заново: ответ ассистента с вызовом
            // и результат вызова с тем же идентификатором обязаны уехать модели.
            val second = llm.requests[1].messages
            assertTrue(
                second.any { it.role == LlmRole.ASSISTANT && it.toolCalls.singleOrNull()?.id == CALL_ID },
                "ответ с вызовом инструмента обязан остаться в диалоге: $second",
            )
            val result = assertNotNull(second.lastOrNull { it.role == LlmRole.TOOL })
            assertEquals(CALL_ID, result.toolCallId, "результат обязан ссылаться на тот же вызов")
            assertTrue(result.content.contains("fun main() = Unit"), "модель обязана получить содержимое: $result")
        }
    }

    @Test
    fun `вызов попадает в журнал с исходом, длительностью и аргументами`() {
        runBlocking {
            file("src/App.kt", "fun main() = Unit\n")
            val llm = ScriptedLlmClient(listOf(call("read_file", """{"path":"src/App.kt"}"""), text("готово")))

            run(llm)

            val recorded = journal.calls.single()
            assertEquals("read_file", recorded.tool)
            assertEquals("""{"path":"src/App.kt"}""", recorded.arguments)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome)
            assertTrue(
                recorded.result?.contains("fun main()") == true,
                "в журнале обязан быть результат: ${recorded.result}",
            )
            assertTrue(recorded.durationMillis >= 0, "длительность обязана быть измерена: ${recorded.durationMillis}")
            assertEquals(runs.all().single().id, recorded.runId, "вызов принадлежит прогону")
        }
    }

    @Test
    fun `вызов за пределами воркспейса возвращается модели отказом, и прогон доходит до finished`() {
        runBlocking {
            val llm = ScriptedLlmClient(listOf(call("read_file", """{"path":"../секрет.txt"}"""), text("готово")))

            run(llm)

            assertEquals(
                RunState.FINISHED,
                runs.all().single().state,
                "отказ инструмента не должен ломать прогон: он обязан дойти до конца",
            )
            val result = assertNotNull(llm.requests[1].messages.lastOrNull { it.role == LlmRole.TOOL })
            assertTrue(
                result.content.contains("PATH_NOT_ALLOWED"),
                "модель обязана увидеть код жёсткого предела: ${result.content}",
            )
            assertEquals(ToolOutcome.DENIED, journal.calls.single().outcome, "отказ обязан остаться в журнале")
        }
    }

    @Test
    fun `два вызова в одном ответе выполняются оба и возвращаются по порядку`() {
        runBlocking {
            file("a.txt", "первый\n")
            file("b.txt", "второй\n")
            val llm = ScriptedLlmClient(
                listOf(
                    LlmResponse.Text(
                        text = "",
                        cost = Cost(amountMicros = 0, known = true),
                        elapsedMillis = 1,
                        toolCalls = listOf(
                            LlmToolCall(CALL_ID, "read_file", """{"path":"a.txt"}"""),
                            LlmToolCall(SECOND_CALL_ID, "read_file", """{"path":"b.txt"}"""),
                        ),
                    ),
                    text("готово"),
                ),
            )

            run(llm)

            assertEquals(2, journal.calls.size, "оба вызова обязаны быть выполнены")
            val results = llm.requests[1].messages.filter { it.role == LlmRole.TOOL }
            assertEquals(listOf(CALL_ID, SECOND_CALL_ID), results.map { it.toolCallId })
            assertTrue(results[0].content.contains("первый"), results[0].content)
            assertTrue(results[1].content.contains("второй"), results[1].content)
        }
    }

    @Test
    fun `стоимость и время всех вызовов шага попадают в прогон`() {
        runBlocking {
            file("a.txt", "текст\n")
            val llm = ScriptedLlmClient(
                listOf(
                    LlmResponse.Text(
                        text = "",
                        cost = Cost(amountMicros = 5, known = true),
                        elapsedMillis = 3,
                        toolCalls = listOf(LlmToolCall(CALL_ID, "read_file", """{"path":"a.txt"}""")),
                    ),
                    LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 7, known = true), elapsedMillis = 4),
                ),
            )

            run(llm)

            val run = runs.all().single()
            // Промежуточный вызов стоит тех же денег, что и последний: потерять его
            // значило бы показать пользователю стоимость меньше настоящей (FR-COST-2).
            assertEquals(Cost(amountMicros = 12, known = true), run.cost)
            assertEquals(7, run.elapsedMillis)
        }
    }

    @Test
    fun `зацикленная на инструментах модель останавливается пределом витков`() {
        runBlocking {
            file("a.txt", "текст\n")
            // Один и тот же ответ на любой запрос: модель не заканчивает шаг никогда.
            val llm = ScriptedLlmClient(listOf(call("read_file", """{"path":"a.txt"}""")))

            run(llm)

            val run = runs.all().single()
            assertEquals(RunState.FAILED, run.state, "зацикленный шаг — отказ, а не бесконечный прогон")
            assertEquals(RunInterruptReason.TOOL_LOOP_LIMIT, run.interruptReason)
            assertEquals(MAX_STEP_TURNS, journal.calls.size, "витков ровно столько, сколько разрешено")
            assertEquals(MAX_STEP_TURNS + 1, llm.callCount)
        }
    }

    companion object {

        /** Идентификаторы вызовов: по ним результат находит свой вызов в диалоге. */
        const val CALL_ID: String = "call-1"
        const val SECOND_CALL_ID: String = "call-2"
    }
}

/** Ответ модели с одним вызовом инструмента: так модель просит работу. */
private fun call(tool: String, arguments: String): LlmResponse.Text = LlmResponse.Text(
    text = "",
    cost = Cost(amountMicros = 0, known = true),
    elapsedMillis = 1,
    toolCalls = listOf(LlmToolCall(id = AgentRunEngineToolsTest.CALL_ID, name = tool, arguments = arguments)),
)
