package dev.aide.tools

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.file.ReadFileTool
import dev.aide.tools.permission.ToolKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * T-1.7: единственная точка вызова — проверка схемы, права, таймаут, журнал.
 *
 * Движок работает с инструментами только через неё, поэтому здесь проверяется то,
 * что обязано быть общим для всех: негодные аргументы не доходят до инструмента,
 * запрет возвращается модели результатом (а не исключением), зависший вызов
 * прерывается, а каждый вызов — с любым исходом — остаётся в журнале.
 */
class ToolInvokerTest {

    private val workspace = ToolsWorkspace()
    private val runId = RunId("run-1")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    private fun invoker(
        tool: AgentTool,
        recorder: RecordingToolCalls = RecordingToolCalls(),
        stored: (String) -> ToolPermission? = { null },
    ): Pair<ToolInvoker, RecordingToolCalls> =
        testInvoker(ToolRegistry(listOf(tool)), recorder, stored) to recorder

    @Test
    fun `недостающий аргумент не доходит до инструмента`() {
        val tool = SpyTool()

        val result = runBlocking { invoker(tool).first.invoke(runId, tool.name, EMPTY_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("path"), "модель обязана узнать, какого аргумента не хватает: ${result.text}")
        assertEquals(0, tool.calls, "вызов с негодными аргументами выполняться не должен")
    }

    @Test
    fun `лишний аргумент отклоняется`() {
        val tool = SpyTool()

        val result = runBlocking {
            invoker(tool).first.invoke(runId, tool.name, """{"path":"a.kt","лишнее":"да"}""", workspace.context)
        }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("лишнее"), result.text)
        assertEquals(0, tool.calls)
    }

    @Test
    fun `аргумент неверного типа отклоняется`() {
        val tool = SpyTool()
        val arguments = JsonObject(mapOf("path" to JsonPrimitive(WrongType.VALUE)))

        val result = runBlocking {
            invoker(tool).first.invoke(runId, tool.name, arguments.toString(), workspace.context)
        }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("строкой"), "модель обязана понять, какой тип ожидался: ${result.text}")
        assertEquals(0, tool.calls)
    }

    @Test
    fun `аргументы не JSON-объектом отклоняются`() {
        val tool = SpyTool()

        val result = runBlocking { invoker(tool).first.invoke(runId, tool.name, "не json", workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertEquals(0, tool.calls)
    }

    @Test
    fun `неизвестный инструмент — результат для модели, а не сбой`() {
        val tool = SpyTool()

        val result = runBlocking { invoker(tool).first.invoke(runId, "нет-такого", ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("нет-такого"), result.text)
    }

    @Test
    fun `запрет настройками возвращается модели и не ломает прогон`() {
        val tool = SpyTool()
        val (call, recorder) = invoker(tool, stored = { name -> ToolPermission(name, Permission.DENY, Permission.ASK) })

        // Отказ — результат: исключение здесь означало бы, что прогон падает из-за прав,
        // а не продолжается, объяснив модели, что так делать нельзя.
        val result = runBlocking { call.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome)
        assertTrue(result.text.contains("DENIED_BY_SETTINGS"), "код причины обязан быть в тексте: ${result.text}")
        assertEquals(0, tool.calls, "запрещённый вызов не выполняется")
        assertEquals(ToolOutcome.DENIED, recorder.calls.single().outcome, "отказ обязан остаться в журнале")
    }

    @Test
    fun `новый инструмент без подтверждения не выполняется`() {
        // Умолчание нового инструмента — ASK (FR-TOOLS-9), а диалога подтверждения
        // в этой задаче ещё нет (T-1.13): значит, вызов отклоняется и это видно в журнале.
        val tool = SpyTool(
            declaredPermission = ToolPermission(ToolPermissionName.SPY, Permission.ASK, Permission.ASK),
        )

        val (call, recorder) = invoker(tool)

        val result = runBlocking { call.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome)
        assertEquals(0, tool.calls)
        assertTrue(recorder.calls.single().requiredApproval, "журнал обязан помнить, что вызов требовал подтверждения")
    }

    @Test
    fun `зависший инструмент прерывается по таймауту`() {
        val tool = SpyTool(timeoutMillis = TIMEOUT_MILLIS, work = { delay(HANG_MILLIS); toolSuccess("поздно") })
        val (call, recorder) = invoker(tool)

        val result = runBlocking { call.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.TIMEOUT, result.outcome)
        assertEquals(1, tool.calls, "инструмент был вызван и был прерван, а не пропущен")
        // FR-AGENT-8: журнал доступен всегда — таймаут тоже событие прозрачности, а не пропуск записи.
        assertEquals(ToolOutcome.TIMEOUT, recorder.calls.single().outcome, "таймаут обязан остаться в журнале")
    }

    @Test
    fun `длительность вызова измеряется и попадает в журнал`() {
        val tool = SpyTool(work = { delay(WORK_MILLIS); toolSuccess("готово") })
        val (call, recorder) = invoker(tool)

        runBlocking { call.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        val recorded = recorder.calls.single()
        assertTrue(
            recorded.durationMillis >= WORK_MILLIS,
            "длительность обязана быть настоящей, а не нулём: ${recorded.durationMillis}",
        )
    }

    @Test
    fun `вызов записывается в журнал целиком`() {
        val tool = SpyTool()
        val (call, recorder) = invoker(tool)

        val result = runBlocking { call.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        val recorded = recorder.calls.single()
        assertEquals(runId, recorded.runId)
        assertEquals(tool.name, recorded.tool)
        assertEquals(ARGUMENTS, recorded.arguments)
        assertEquals(result.text, recorded.result)
        assertEquals(ToolOutcome.SUCCESS, recorded.outcome)
        assertNull(recorded.approval, "подтверждения не спрашивали — значит, и ответа нет")
    }

    @Test
    fun `путь за пределами воркспейса становится отказом, а не ошибкой инструмента`() {
        val recorder = RecordingToolCalls()
        val invoker = testInvoker(ToolRegistry(listOf(ReadFileTool)), recorder)

        val result = runBlocking {
            invoker.invoke(runId, ReadFileTool.name, """{"path":"../секрет.txt"}""", workspace.context)
        }

        assertEquals(ToolOutcome.DENIED, result.outcome, "выход за воркспейс — отказ, а не сбой")
        assertTrue(
            result.text.contains("PATH_NOT_ALLOWED"),
            "код жёсткого предела обязан быть в тексте: ${result.text}",
        )
        assertEquals(ToolOutcome.DENIED, recorder.calls.single().outcome)
    }

    @Test
    fun `отмена прогона не превращается в результат инструмента`() {
        runBlocking {
            val tool = SpyTool(work = { awaitCancellation() })
            val invoker = testInvoker(ToolRegistry(listOf(tool)))
            val outcome = CompletableDeferred<Any>()
            val job = launch {
                try {
                    outcome.complete(invoker.invoke(runId, tool.name, ARGUMENTS, workspace.context))
                } catch (error: CancellationException) {
                    outcome.complete(error)
                    throw error
                }
            }
            while (tool.calls == 0) yield()
            job.cancel()

            // Стоп прогона обязан остаться отменой: если точка вызова проглотит её,
            // остановленный прогон «успешно» выполнит инструмент и продолжит цикл.
            assertIs<CancellationException>(outcome.await())
        }
    }

    private companion object {

        const val ARGUMENTS: String = """{"path":"src/App.kt"}"""
        const val EMPTY_ARGUMENTS: String = "{}"

        /** Таймаут, который тест пережидает мгновенно, а инструмент — нет. */
        const val TIMEOUT_MILLIS: Long = 100

        /** Сколько висит инструмент, который обязан быть прерван таймаутом. */
        const val HANG_MILLIS: Long = 10_000

        /** Сколько работает инструмент, у которого проверяется длительность. */
        const val WORK_MILLIS: Long = 60
    }
}

/** Значение неверного типа для проверки схемы: число там, где объявлена строка. */
private object WrongType {
    const val VALUE: Int = 7
}

/** Имя инструмента-шпиона: в объявленном разрешении имя стоит явно. */
private object ToolPermissionName {
    const val SPY: String = "spy"
}

/**
 * Инструмент-шпион: считает вызовы, поэтому по нему видно, дошло ли дело до выполнения.
 *
 * Схема и права у него настоящие — те же, что у инструментов чтения: проверяется
 * поведение точки вызова, а не выдуманный инструмент.
 */
private class SpyTool(
    override val timeoutMillis: Long = DEFAULT_TOOL_TIMEOUT_MILLIS,
    override val declaredPermission: ToolPermission =
        ToolPermission(ToolPermissionName.SPY, Permission.ALLOW, Permission.ASK),
    private val work: suspend () -> ToolResult = { toolSuccess("выполнено") },
) : AgentTool {

    override val name: String = ToolPermissionName.SPY

    override val description: String = "Инструмент для проверок точки вызова"

    override val kind: ToolKind = ToolKind.READ

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("path") { put("type", "string") }
        }
        putJsonArray("required") { add("path") }
    }

    @Volatile
    var calls: Int = 0
        private set

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        calls += 1
        return work()
    }
}
