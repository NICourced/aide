package dev.aide.client.state

import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.HostMessage
import dev.aide.protocol.RequestId
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallScope
import dev.aide.protocol.ToolCallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.3, T-1.14: состояние журнала на клиенте складывается из страниц и живых событий
 * с учётом области (run/task) и фильтра по инструменту.
 *
 * Ключевые свойства:
 * - дедупликация по идентификатору;
 * - поздняя страница старого scope или tool игнорируется;
 * - живое событие добавляется только в совпадающую область и при совпадающем tool;
 * - неизвестный run в task-фильтре игнорируется.
 */
class ToolCallLogStoreTest {

    private val store = ToolCallLogStore()

    @Test
    fun `первая страница наполняет состояние новыми сверху`() {
        store.open(RUN_SCOPE)

        store.page(page(listOf(summary(3), summary(2)), hasMore = true))

        val log = store.log.value
        assertEquals(listOf("tc-3", "tc-2"), log.calls.map { it.id.value })
        assertEquals(ToolCallCursor(summary(2).at, ToolCallId("tc-2")), log.cursor)
        assertTrue(log.hasMore)
        assertTrue(log.loaded)
        assertFalse(log.loading)
    }

    @Test
    fun `догрузка добавляет старые записи в конец`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(3), summary(2)), hasMore = true))

        store.loading()
        store.page(page(listOf(summary(1), summary(0))))

        val log = store.log.value
        assertEquals(listOf("tc-3", "tc-2", "tc-1", "tc-0"), log.calls.map { it.id.value })
        assertFalse(log.hasMore)
        assertFalse(log.loading)
    }

    @Test
    fun `живое событие добавляет запись в начало`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.recorded(call(3), runToTask = { TASK })

        assertEquals(listOf("tc-3", "tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `дубль по идентификатору не появляется`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2), summary(1))))

        // Событие приходит для вызова, который уже лежит в подгруженной странице.
        store.recorded(call(2), runToTask = { TASK })

        assertEquals(listOf("tc-2", "tc-1"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `событие другого прогона не подмешивается`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.recorded(call(9, runId = RunId("r-2")), runToTask = { TASK })

        assertEquals(listOf("tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `событие до открытия журнала игнорируется`() {
        store.recorded(call(1), runToTask = { TASK })

        val log = store.log.value
        assertNull(log.scope)
        assertTrue(log.calls.isEmpty(), "пока область не выбрана, показывать чужие вызовы нечем")
    }

    @Test
    fun `открытие другой области сбрасывает прежние записи`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.open(OTHER_RUN_SCOPE)

        val log = store.log.value
        assertEquals(OTHER_RUN_SCOPE, log.scope)
        assertTrue(log.calls.isEmpty(), "вызовы прежней области к журналу новой не относятся")
        assertTrue(log.loading)
    }

    @Test
    fun `ошибка запроса помечает состояние, не стирая загруженное`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.failed()

        val log = store.log.value
        assertTrue(log.failed)
        assertFalse(log.loading)
        assertEquals(listOf("tc-2"), log.calls.map { it.id.value })
    }

    @Test
    fun `потеря связи не стирает загруженные записи`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.setOffline(true)

        val log = store.log.value
        assertTrue(log.offline)
        assertEquals(listOf("tc-2"), log.calls.map { it.id.value })
    }

    @Test
    fun `раскрытие ставит загрузку и возвращает признак раскрытия`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2), summary(1))))

        val opened = store.toggle(ToolCallId("tc-2"))

        assertTrue(opened, "признак нужен вызывающему: только при раскрытии запрашивается деталь")
        val log = store.log.value
        assertEquals(ToolCallId("tc-2"), log.expanded)
        assertTrue(log.detailLoading)
        assertNull(log.detail)
    }

    @Test
    fun `раскрыта не более одной записи`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2), summary(1))))

        store.toggle(ToolCallId("tc-2"))
        store.toggle(ToolCallId("tc-1"))

        val log = store.log.value
        assertEquals(ToolCallId("tc-1"), log.expanded, "вторая запись занимает место первой")
        assertTrue(log.detailLoading)
    }

    @Test
    fun `повторный тап по той же записи сворачивает её`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))
        store.detailResult(Result.success(call(2)))

        val stillOpen = store.toggle(ToolCallId("tc-2"))

        assertFalse(stillOpen, "сворачивание не ходит на хост")
        val log = store.log.value
        assertNull(log.expanded)
        assertNull(log.detail)
        assertFalse(log.detailLoading)
    }

    @Test
    fun `полное содержимое кладётся в раскрытую запись`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))

        store.detailResult(Result.success(call(2)))

        val log = store.log.value
        assertEquals("результат-2", log.detail?.result)
        assertFalse(log.detailLoading)
        assertFalse(log.detailFailed)
    }

    @Test
    fun `ошибка загрузки видна у раскрытой записи`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))

        store.detailResult(Result.failure(RuntimeException("boom")))

        val log = store.log.value
        assertTrue(log.detailFailed)
        assertFalse(log.detailLoading)
        assertNull(log.detail)
    }

    @Test
    fun `ответ на свёрнутую запись игнорируется`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))
        store.toggle(ToolCallId("tc-2"))

        store.detailResult(Result.success(call(2)))

        val log = store.log.value
        assertNull(log.detail, "после сворачивания ответ на прежний запрос не должен всплывать")
        assertNull(log.expanded)
    }

    @Test
    fun `страница с неподходящим scope или tool игнорируется`() {
        store.open(RUN_SCOPE, tool = "read_file")
        store.page(page(listOf(summary(2), summary(1)), tool = "read_file", hasMore = true))

        // Поздний ответ с другим scope.
        store.page(
            HostMessage.ToolCallPage(
                requestId = RequestId("req-other"),
                scope = OTHER_RUN_SCOPE,
                tool = "read_file",
                calls = listOf(summary(0)),
                hasMore = false,
            ),
        )
        // Поздний ответ с другим tool.
        store.page(
            HostMessage.ToolCallPage(
                requestId = RequestId("req-other-tool"),
                scope = RUN_SCOPE,
                tool = "write_file",
                calls = listOf(summary(5)),
                hasMore = false,
            ),
        )

        val log = store.log.value
        assertEquals(listOf("tc-2", "tc-1"), log.calls.map { it.id.value })
        assertTrue(log.hasMore)
    }

    @Test
    fun `смена инструмента сбрасывает записи и показывает загрузку`() {
        store.open(RUN_SCOPE)
        store.page(page(listOf(summary(2))))

        store.filter("write_file")

        val log = store.log.value
        assertEquals("write_file", log.tool)
        assertTrue(log.calls.isEmpty())
        assertTrue(log.loading)
        assertFalse(log.loaded)
        assertNull(log.cursor)
    }

    @Test
    fun `страница заполняет список доступных инструментов`() {
        store.open(RUN_SCOPE)

        store.page(page(listOf(summary(2)), tools = listOf("read_file", "write_file")))

        assertEquals(listOf("read_file", "write_file"), store.log.value.availableTools)
    }

    @Test
    fun `живое событие по задаче добавляется при совпадающем run`() {
        store.selectTask(TASK)
        store.page(taskPage(listOf(summary(2, runId = RUN))))

        store.recorded(call(3, runId = RUN, tool = "read_file"), runToTask = { TASK })

        assertEquals(listOf("tc-3", "tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `живое событие по задаче игнорируется для неизвестного run`() {
        store.selectTask(TASK)
        store.page(taskPage(listOf(summary(2, runId = RUN))))

        store.recorded(call(3, runId = RunId("r-unknown")), runToTask = { null })

        assertEquals(listOf("tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `живое событие по задаче игнорируется при несовпадающем tool`() {
        store.selectTask(TASK)
        store.filter("read_file")
        store.page(taskPage(listOf(summary(2, runId = RUN, tool = "read_file")), tool = "read_file"))

        store.recorded(call(3, runId = RUN, tool = "write_file"), runToTask = { TASK })

        assertEquals(listOf("tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `выбор задачи сбрасывает фильтр и записи`() {
        store.selectTask(TASK)
        store.filter("read_file")
        store.page(taskPage(listOf(summary(2))))

        store.selectTask(TaskId("t-2"))

        val log = store.log.value
        assertEquals(ToolCallScope.Task(TaskId("t-2")), log.scope)
        assertNull(log.tool)
        assertTrue(log.calls.isEmpty())
        assertTrue(log.loading)
    }

    /** Страница как её собирает хост: записи от новых к старым и курсор по последней. */
    private fun page(
        calls: List<ToolCallSummary>,
        tool: String? = null,
        hasMore: Boolean = false,
        tools: List<String> = emptyList(),
    ): HostMessage.ToolCallPage = HostMessage.ToolCallPage(
        requestId = RequestId("req-tool-calls"),
        scope = RUN_SCOPE,
        tool = tool,
        calls = calls,
        nextCursor = calls.lastOrNull()?.let { ToolCallCursor(it.at, it.id) },
        hasMore = hasMore,
        tools = tools,
    )

    private fun taskPage(
        calls: List<ToolCallSummary>,
        tool: String? = null,
    ): HostMessage.ToolCallPage = HostMessage.ToolCallPage(
        requestId = RequestId("req-tool-calls"),
        scope = ToolCallScope.Task(TASK),
        tool = tool,
        calls = calls,
        nextCursor = calls.lastOrNull()?.let { ToolCallCursor(it.at, it.id) },
        hasMore = false,
        tools = calls.map { it.tool }.distinct(),
    )

    private fun summary(
        index: Int,
        runId: RunId = RUN,
        tool: String = "read_file",
    ): ToolCallSummary = ToolCallSummary(
        id = ToolCallId("tc-$index"),
        runId = runId,
        tool = tool,
        outcome = ToolOutcome.SUCCESS,
        durationMillis = index.toLong(),
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
        argumentsPreview = """{"path":"file-$index"}""",
        argumentsTruncated = false,
        resultPreview = "результат-$index",
    )

    private fun call(
        index: Int,
        runId: RunId = RUN,
        tool: String = "read_file",
    ): ToolCall = ToolCall(
        id = ToolCallId("tc-$index"),
        runId = runId,
        tool = tool,
        arguments = """{"path":"file-$index"}""",
        result = "результат-$index",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = index.toLong(),
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
    )

    private companion object {
        val RUN: RunId = RunId("r-1")
        val TASK: TaskId = TaskId("t-1")
        val RUN_SCOPE: ToolCallScope.Run = ToolCallScope.Run(RUN)
        val OTHER_RUN_SCOPE: ToolCallScope.Run = ToolCallScope.Run(RunId("r-2"))
        const val BASE_MILLIS: Long = 1_758_535_200_000
    }
}
