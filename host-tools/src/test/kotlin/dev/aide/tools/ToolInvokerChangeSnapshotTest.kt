package dev.aide.tools

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.file.ReadFileTool
import dev.aide.tools.file.WriteFileTool
import dev.aide.tools.ports.ChangeSnapshot
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.8 в точке вызова: изменяющий вызов без точки отката не выполняется.
 *
 * Проверяется именно гарантия точки вызова, а не инструмент: точку отката спрашивает она,
 * и обойти её нечем (О-3). Реализация самой точки — порт и его адаптер в host-core.
 */
class ToolInvokerChangeSnapshotTest {

    private val workspace = ToolsWorkspace()
    private val runId = RunId("run-1")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    private fun invoker(
        snapshots: FakeChangeSnapshots,
        tool: AgentTool = WriteFileTool,
        stored: (String) -> ToolPermission? = writeAllowed,
    ): Pair<ToolInvoker, RecordingToolCalls> {
        val recorder = RecordingToolCalls()
        return testInvoker(ToolRegistry(listOf(tool)), recorder, stored, snapshots) to recorder
    }

    @Test
    fun `изменяющий вызов с точкой отката выполняется, и ссылка доходит до результата`() {
        val snapshots = FakeChangeSnapshots()
        val (call, recorder) = invoker(snapshots)

        val result = runBlocking { call.invoke(runId, WriteFileTool.name, WRITE_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertEquals(SnapshotRef(TEST_SNAPSHOT_REF), result.snapshotRef, "ссылка обязана ехать в результате")
        assertEquals("fun main() = Unit\n", workspace.writeTarget().readText())
        assertEquals(1, snapshots.asked, "точка отката спрашивается у каждого изменяющего вызова")
        assertEquals(
            listOf(runId),
            snapshots.runs,
            "порт узнаёт прогон: кэш ссылок не должен переезжать между прогонами",
        )
        assertEquals(ToolOutcome.SUCCESS, recorder.calls.single().outcome)
    }

    @Test
    fun `ссылка прикладывается и к неуспешному выполненному изменяющему вызову`() {
        workspace.directory("src/main")
        val snapshots = FakeChangeSnapshots()
        val (call, recorder) = invoker(snapshots)

        val result = runBlocking {
            call.invoke(runId, WriteFileTool.name, """{"path":"src/main","content":"не файл"}""", workspace.context)
        }

        // Снапшот поставлен до выполнения и остаётся в репозитории; прогон обязан его увидеть,
        // иначе вытеснение сочтёт ссылку брошенной, хотя она настоящая.
        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertEquals(SnapshotRef(TEST_SNAPSHOT_REF), result.snapshotRef)
        assertEquals(ToolOutcome.FAILURE, recorder.calls.single().outcome)
    }

    @Test
    fun `нет коммита — изменяющий вызов не выполняется, отказ остаётся в журнале`() {
        val snapshots = FakeChangeSnapshots().apply { answer(ChangeSnapshot.NoHead) }
        val (call, recorder) = invoker(snapshots)

        val result = runBlocking { call.invoke(runId, WriteFileTool.name, WRITE_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome, "без точки отката изменения быть не должно")
        assertNull(result.snapshotRef)
        assertFalse(workspace.writeTarget().exists(), "файл не имеет права появиться без точки отката")
        val recorded = recorder.calls.single()
        assertEquals(ToolOutcome.DENIED, recorded.outcome, "отказ обязан остаться в журнале")
        assertFalse(recorded.requiredApproval, "подтверждения здесь не спрашивали — отказ не в правах")
        assertTrue(result.text.contains("коммита"), "модель обязана понять причину: ${result.text}")
    }

    @Test
    fun `сбой точки отката — отказ с кодом причины, файл не создан`() {
        val snapshots = FakeChangeSnapshots().apply { answer(ChangeSnapshot.Failed("snapshot_failed")) }
        val (call, recorder) = invoker(snapshots)

        val result = runBlocking { call.invoke(runId, WriteFileTool.name, WRITE_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome)
        assertTrue(result.text.contains("snapshot_failed"), "код причины обязан быть в тексте: ${result.text}")
        assertFalse(workspace.writeTarget().exists())
        assertEquals(ToolOutcome.DENIED, recorder.calls.single().outcome)
    }

    @Test
    fun `читающий вызов точку отката не спрашивает`() {
        workspace.write("src/App.kt", "fun main() = Unit\n")
        val snapshots = FakeChangeSnapshots()
        val (call, _) = invoker(snapshots, tool = ReadFileTool, stored = { null })

        val result = runBlocking {
            call.invoke(runId, ReadFileTool.name, """{"path":"src/App.kt"}""", workspace.context)
        }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertNull(result.snapshotRef, "у чтения точки отката нет")
        assertEquals(0, snapshots.asked, "читать без снапшота можно, и спрашивать его незачем")
    }

    @Test
    fun `запись без подтверждения не выполняется, и точка отката не спрашивается`() {
        val snapshots = FakeChangeSnapshots()
        val recorder = RecordingToolCalls()
        val call = testInvoker(ToolRegistry(listOf(WriteFileTool)), recorder, stored = { null }, snapshots = snapshots)

        val result = runBlocking { call.invoke(runId, WriteFileTool.name, WRITE_ARGUMENTS, workspace.context) }

        assertEquals(ToolOutcome.DENIED, result.outcome, "у записи объявленное умолчание — ASK")
        assertFalse(workspace.writeTarget().exists())
        assertTrue(recorder.calls.single().requiredApproval, "журнал обязан помнить про подтверждение")
        assertEquals(0, snapshots.asked, "отказ по правам не ставит снапшот")
    }

    private companion object {

        const val WRITE_ARGUMENTS: String = """{"path":"src/App.kt","content":"fun main() = Unit\n"}"""

        /** Настройки прав, разрешающие запись: иначе вызов упрётся в ASK раньше снапшота. */
        val writeAllowed: (String) -> ToolPermission? = { name ->
            ToolPermission(tool = name, read = Permission.ALLOW, write = Permission.ALLOW)
        }
    }
}

/** Путь, по которому пишет [WriteFileTool] в этих проверках. */
private fun ToolsWorkspace.writeTarget() = root.resolve("src/App.kt")
