package dev.aide.tools

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.permission.ToolKind
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * T-1.11: внутренний инструмент в точке вызова — кому доступен и что обходит.
 *
 * Хост-путь (`invokeInternal`) исполняет внутренний инструмент без прав и без точки отката:
 * пользователь одобряет изменение (`write_file`), а не бухгалтерию хоста, и второй вопрос
 * за то же действие запрещает О-4. Модельный путь (`invoke`) тот же инструмент исполнить
 * не должен: «не показывать определение» — не то же, что «нельзя позвать по имени».
 * Проверяется и то, что облегчённый вход не открыт обычным инструментам.
 */
class ToolInvokerInternalTest {

    private val workspace = ToolsWorkspace()
    private val runId = RunId("run-1")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `хост-путь исполняет внутренний инструмент и при пользовательском запрете`() {
        val tool = InternalSpyTool()
        val invoker = testInvoker(
            registry = ToolRegistry(listOf(tool)),
            stored = { name -> ToolPermission(name, Permission.DENY, Permission.DENY) },
        )

        val result = runBlocking { invoker.invokeInternal(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, "права хост-путь не спрашивает (О-4)")
        assertEquals(1, tool.calls, "запрет настроек внутренний вызов не останавливает")
    }

    @Test
    fun `хост-путь не ставит точку отката внутреннему инструменту`() {
        val tool = InternalSpyTool()
        val snapshots = FakeChangeSnapshots()
        val invoker = testInvoker(ToolRegistry(listOf(tool)), snapshots = snapshots)

        val result = runBlocking { invoker.invokeInternal(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertEquals(0, snapshots.asked, "снапшот принадлежит изменению и уже поставлен перед записью (T-1.8)")
    }

    @Test
    fun `вызов внутреннего инструмента с любым исходом попадает в журнал`() {
        val tool = InternalSpyTool(outcome = ToolOutcome.FAILURE)
        val recorder = RecordingToolCalls()
        val invoker = testInvoker(ToolRegistry(listOf(tool)), recorder)

        runBlocking { invoker.invokeInternal(runId, tool.name, ARGUMENTS, workspace.context) }

        val recorded = recorder.calls.single()
        assertEquals(tool.name, recorded.tool, "внутренний вызов обязан быть виден в журнале (FR-AGENT-8)")
        assertEquals(ToolOutcome.FAILURE, recorded.outcome)
    }

    @Test
    fun `модельный путь внутренний инструмент не исполняет, а отказ виден в журнале`() {
        val tool = InternalSpyTool()
        val recorder = RecordingToolCalls()
        val invoker = testInvoker(ToolRegistry(listOf(tool)), recorder)

        val result = runBlocking { invoker.invoke(runId, tool.name, ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome, "внутренний инструмент модели недоступен")
        assertEquals(0, tool.calls, "модель не исполняет внутренний инструмент — иначе обошла бы права")
        assertTrue(result.text.contains(tool.name), "отказ обязан назвать инструмент: ${result.text}")
        assertEquals(
            ToolOutcome.FAILURE,
            recorder.calls.single().outcome,
            "попытка модели обязана остаться в журнале",
        )
    }

    @Test
    fun `хост-путь не открывает облегчённый вход обычному инструменту`() {
        // `write` объявлен ASK: если бы хост-путь обходил права для всех, запрет был бы не виден.
        val tool = ExternalWriteSpyTool()
        val invoker = testInvoker(ToolRegistry(listOf(tool)))

        val result = runBlocking { invoker.invokeInternal(runId, tool.name, EXTERNAL_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome, "обычный инструмент проходит общий порядок прав")
        assertEquals(0, tool.calls)
    }

    private companion object {
        const val ARGUMENTS: String = """{"branch":"ai/t-1"}"""
        const val EXTERNAL_ARGUMENTS: String = """{"path":"a.txt"}"""
    }
}

/** Внутренний инструмент-шпион: изменяющий по виду, но не спрашивающий ни прав, ни снапшота. */
private class InternalSpyTool(private val outcome: ToolOutcome = ToolOutcome.SUCCESS) : AgentTool {

    override val name: String = "commit_step"

    override val description: String = "Инструмент для проверок внутреннего вызова"

    override val kind: ToolKind = ToolKind.WRITE

    override val visibility: ToolVisibility = ToolVisibility.INTERNAL

    override val declaredPermission: ToolPermission =
        ToolPermission(name, read = Permission.ASK, write = Permission.ASK)

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject("branch") { put("type", "string") }
        }
        putJsonArray("required") { add("branch") }
    }

    @Volatile
    var calls: Int = 0
        private set

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        calls += 1
        return ToolResult(text = "выполнено", outcome = outcome)
    }
}

/** Обычный изменяющий инструмент: права и снапшот у него спрашиваются, чем бы его ни звали. */
private class ExternalWriteSpyTool : AgentTool {

    override val name: String = "write_file"

    override val description: String = "Инструмент для проверки общего порядка прав"

    override val kind: ToolKind = ToolKind.WRITE

    override val declaredPermission: ToolPermission =
        ToolPermission(name, read = Permission.ASK, write = Permission.ASK)

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
        return toolSuccess("выполнено")
    }
}
