package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.host.store.db.HostDatabase
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallScope
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.3, T-1.14: страницы журнала по курсору — лимит, порядок, фильтры, стабильность.
 *
 * Страницы проверяются на настоящей базе, а не на списке в памяти: именно SQL задаёт
 * порядок «новые сверху», условие «строго старше курсора» и join по задаче.
 */
class ToolCallStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `страница отдаёт не больше лимита и новые сверху`() {
        repeat(5) { store.toolCalls.save(call(it)) }

        val page = store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 2)

        assertEquals(listOf("tc-4", "tc-3"), page.calls.map { it.id.value }, "новые сверху — не больше лимита")
        assertTrue(page.hasMore, "старше отданных записи есть — значит, есть ещё страница")
    }

    @Test
    fun `страница не смешивает прогоны`() {
        store.toolCalls.save(call(0))
        store.toolCalls.save(call(1, runId = RunId("r-2")))

        val page = store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 20)

        assertEquals(listOf("tc-0"), page.calls.map { it.id.value })
    }

    @Test
    fun `курсор отдаёт строго старше и не повторяет уже полученное`() {
        repeat(5) { store.toolCalls.save(call(it)) }

        val first = store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 2)
        val second = store.toolCalls.page(
            ToolCallScope.Run(RUN),
            tool = null,
            before = cursorOf(first.calls.last()),
            limit = 2,
        )
        val third = store.toolCalls.page(
            ToolCallScope.Run(RUN),
            tool = null,
            before = cursorOf(second.calls.last()),
            limit = 2,
        )

        assertEquals(listOf("tc-4", "tc-3"), first.calls.map { it.id.value })
        assertEquals(listOf("tc-2", "tc-1"), second.calls.map { it.id.value })
        assertEquals(listOf("tc-0"), third.calls.map { it.id.value })
        assertFalse(third.hasMore, "после последней записи страниц больше нет")
        val seen = (first.calls + second.calls + third.calls).map { it.id.value }
        assertEquals(seen.distinct(), seen, "курсор обязан отдавать строго старше — без повторов")
    }

    @Test
    fun `hasMore верен на границе — ровно лимит это конец, лимит плюс один это ещё есть`() {
        repeat(3) { store.toolCalls.save(call(it)) }

        assertFalse(
            store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 3).hasMore,
            "ровно лимит означает конец журнала, а не наличие следующей страницы",
        )
        assertTrue(store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 2).hasMore)
    }

    @Test
    fun `пустой прогон даёт пустую страницу без продолжения`() {
        val page = store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 20)

        assertTrue(page.calls.isEmpty())
        assertFalse(page.hasMore)
    }

    @Test
    fun `запись, добавленная между страницами, не сдвигает выдачу`() {
        repeat(4) { store.toolCalls.save(call(it)) }
        val first = store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 2)

        // Живая запись приходит сверху — при `OFFSET` она сдвинула бы вторую страницу и дала бы повтор.
        store.toolCalls.save(call(9))

        val second = store.toolCalls.page(
            ToolCallScope.Run(RUN),
            tool = null,
            before = cursorOf(first.calls.last()),
            limit = 2,
        )
        assertEquals(
            listOf("tc-1", "tc-0"),
            second.calls.map { it.id.value },
            "вторая страница обязана продолжиться строго старше курсора, а не сдвинуться",
        )
    }

    @Test
    fun `страница с негодным лимитом отвергается`() {
        store.toolCalls.save(call(0))

        // Нулевой или отрицательный размер — ошибка вызывающего, а не «пустой журнал»:
        // молчаливый пустой ответ спрятал бы баг, а take(-1) бросил бы уже внутри.
        assertFailsWith<IllegalArgumentException> {
            store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = 0)
        }
        assertFailsWith<IllegalArgumentException> {
            store.toolCalls.page(ToolCallScope.Run(RUN), tool = null, before = null, limit = -5)
        }
    }

    @Test
    fun `byId читает запись, а неизвестный идентификатор даёт null`() {
        store.toolCalls.save(call(0))

        assertEquals("tc-0", store.toolCalls.byId(ToolCallId("tc-0"))?.id?.value)
        assertNull(store.toolCalls.byId(ToolCallId("нет-такой")))
    }

    @Test
    fun `страница по задаче собирает вызовы двух прогонов и исключает чужую задачу`() {
        val runOther = RunId("r-other")
        store.runs.save(run(runId = RUN, taskId = TASK))
        store.runs.save(run(runId = RunId("r-2"), taskId = TASK))
        store.runs.save(run(runId = runOther, taskId = TaskId("t-other")))

        store.toolCalls.save(call(0, runId = RUN))
        store.toolCalls.save(call(1, runId = RunId("r-2")))
        store.toolCalls.save(call(2, runId = runOther))

        val page = store.toolCalls.page(ToolCallScope.Task(TASK), tool = null, before = null, limit = 20)

        assertEquals(setOf("tc-0", "tc-1"), page.calls.map { it.id.value }.toSet())
    }

    @Test
    fun `фильтр по инструменту работает и с прогоном, и с задачей`() {
        store.runs.save(run(runId = RUN, taskId = TASK))
        store.runs.save(run(runId = RunId("r-2"), taskId = TASK))
        store.toolCalls.save(call(0, runId = RUN, tool = "read_file"))
        store.toolCalls.save(call(1, runId = RUN, tool = "write_file"))
        store.toolCalls.save(call(2, runId = RunId("r-2"), tool = "read_file"))

        val runPage = store.toolCalls.page(ToolCallScope.Run(RUN), tool = "read_file", before = null, limit = 20)
        assertEquals(listOf("tc-0"), runPage.calls.map { it.id.value })

        val taskPage = store.toolCalls.page(ToolCallScope.Task(TASK), tool = "read_file", before = null, limit = 20)
        assertEquals(setOf("tc-0", "tc-2"), taskPage.calls.map { it.id.value }.toSet())
    }

    @Test
    fun `курсор стабилен внутри фильтра по инструменту`() {
        store.runs.save(run(runId = RUN, taskId = TASK))
        store.toolCalls.save(call(0, runId = RUN, tool = "write_file"))
        store.toolCalls.save(call(1, runId = RUN, tool = "read_file"))
        store.toolCalls.save(call(2, runId = RUN, tool = "write_file"))
        store.toolCalls.save(call(3, runId = RUN, tool = "read_file"))

        val first = store.toolCalls.page(ToolCallScope.Run(RUN), tool = "read_file", before = null, limit = 1)
        val second = store.toolCalls.page(
            ToolCallScope.Run(RUN),
            tool = "read_file",
            before = cursorOf(first.calls.last()),
            limit = 1,
        )

        assertEquals(listOf("tc-3"), first.calls.map { it.id.value })
        assertEquals(listOf("tc-1"), second.calls.map { it.id.value })
    }

    @Test
    fun `tools отдаёт distinct-имена области и не зависит от текущего фильтра`() {
        store.runs.save(run(runId = RUN, taskId = TASK))
        store.runs.save(run(runId = RunId("r-2"), taskId = TASK))
        store.toolCalls.save(call(0, runId = RUN, tool = "read_file"))
        store.toolCalls.save(call(1, runId = RUN, tool = "write_file"))
        store.toolCalls.save(call(2, runId = RunId("r-2"), tool = "read_file"))

        assertEquals(listOf("read_file", "write_file"), store.toolCalls.tools(ToolCallScope.Run(RUN)))
        assertEquals(listOf("read_file", "write_file"), store.toolCalls.tools(ToolCallScope.Task(TASK)))
    }

    @Test
    fun `журнал переживает закрытие и повторное открытие файла БД`() {
        val dbFile = java.nio.file.Files.createTempFile("aide-tool-call-", ".db").toFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            JdbcSqliteDriver(url).let { driver ->
                HostDatabase.Schema.create(driver)
                val store = HostStore(HostDatabase(driver))
                store.runs.save(run(runId = RUN, taskId = TASK))
                store.toolCalls.save(call(0, runId = RUN, tool = "read_file"))
                driver.close()
            }

            val driver = JdbcSqliteDriver(url)
            try {
                val store = HostStore(HostDatabase(driver))
                val page = store.toolCalls.page(ToolCallScope.Task(TASK), tool = null, before = null, limit = 20)
                assertEquals(1, page.calls.size)
                assertEquals("tc-0", page.calls.single().id.value)
                assertEquals("read_file", page.calls.single().tool)
            } finally {
                driver.close()
            }
        } finally {
            dbFile.delete()
        }
    }

    /** Курсор по последней отданной записи: так клиент запрашивает следующую страницу. */
    private fun cursorOf(call: ToolCall): ToolCallCursor = ToolCallCursor(call.at, call.id)

    /** Вызов с монотонным временем: [index] больше — значит, вызов новее. */
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
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index * MILLIS_STEP),
    )

    private fun run(runId: RunId, taskId: TaskId): dev.aide.domain.AgentRun = dev.aide.domain.AgentRun(
        id = runId,
        taskId = taskId,
        state = dev.aide.domain.RunState.FINISHED,
        mode = dev.aide.domain.AutonomyMode.ASK_BEFORE_CHANGES,
        startedAt = Instant.fromEpochMilliseconds(BASE_MILLIS),
        elapsedMillis = 0,
        cost = Cost(),
    )

    private companion object {
        val RUN: RunId = RunId("r-1")
        val TASK: TaskId = TaskId("t-1")
        const val BASE_MILLIS: Long = 1_758_535_200_000
        const val MILLIS_STEP: Long = 1_000
    }
}
