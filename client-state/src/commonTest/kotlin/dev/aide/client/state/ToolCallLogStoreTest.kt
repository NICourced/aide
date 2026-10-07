package dev.aide.client.state

import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.HostMessage
import dev.aide.protocol.RequestId
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.3: состояние журнала на клиенте складывается из страниц и живых событий.
 *
 * Ключевое свойство — дедупликация по идентификатору: живое событие и подгруженная
 * страница описывают один вызов с двух сторон, и без отсечения запись появилась бы
 * в списке дважды.
 */
class ToolCallLogStoreTest {

    private val store = ToolCallLogStore()

    @Test
    fun `первая страница наполняет состояние новыми сверху`() {
        store.open(RUN)

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
        store.open(RUN)
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
        store.open(RUN)
        store.page(page(listOf(summary(2))))

        store.recorded(call(3))

        assertEquals(listOf("tc-3", "tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `дубль по идентификатору не появляется`() {
        store.open(RUN)
        store.page(page(listOf(summary(2), summary(1))))

        // Событие приходит для вызова, который уже лежит в подгруженной странице.
        store.recorded(call(2))

        assertEquals(listOf("tc-2", "tc-1"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `событие другого прогона не подмешивается`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))

        store.recorded(call(9, runId = RunId("r-2")))

        assertEquals(listOf("tc-2"), store.log.value.calls.map { it.id.value })
    }

    @Test
    fun `событие до открытия журнала игнорируется`() {
        store.recorded(call(1))

        val log = store.log.value
        assertNull(log.runId)
        assertTrue(log.calls.isEmpty(), "пока прогон не выбран, показывать чужие вызовы нечем")
    }

    @Test
    fun `открытие другого прогона сбрасывает прежние записи`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))

        store.open(OTHER_RUN)

        val log = store.log.value
        assertEquals(OTHER_RUN, log.runId)
        assertTrue(log.calls.isEmpty(), "вызовы прежнего прогона к журналу нового не относятся")
        assertTrue(log.loading)
    }

    @Test
    fun `ошибка запроса помечает состояние, не стирая загруженное`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))

        store.failed()

        val log = store.log.value
        assertTrue(log.failed)
        assertFalse(log.loading)
        assertEquals(listOf("tc-2"), log.calls.map { it.id.value })
    }

    @Test
    fun `потеря связи не стирает загруженные записи`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))

        store.offline()

        val log = store.log.value
        assertTrue(log.offline)
        assertEquals(listOf("tc-2"), log.calls.map { it.id.value })
    }

    @Test
    fun `раскрытие ставит загрузку и возвращает признак раскрытия`() {
        store.open(RUN)
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
        store.open(RUN)
        store.page(page(listOf(summary(2), summary(1))))

        store.toggle(ToolCallId("tc-2"))
        store.toggle(ToolCallId("tc-1"))

        val log = store.log.value
        assertEquals(ToolCallId("tc-1"), log.expanded, "вторая запись занимает место первой")
        assertTrue(log.detailLoading)
    }

    @Test
    fun `повторный тап по той же записи сворачивает её`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))
        store.detailLoaded(call(2))

        val stillOpen = store.toggle(ToolCallId("tc-2"))

        assertFalse(stillOpen, "сворачивание не ходит на хост")
        val log = store.log.value
        assertNull(log.expanded)
        assertNull(log.detail)
        assertFalse(log.detailLoading)
    }

    @Test
    fun `полное содержимое кладётся в раскрытую запись`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))

        store.detailLoaded(call(2))

        val log = store.log.value
        assertEquals("результат-2", log.detail?.result)
        assertFalse(log.detailLoading)
        assertFalse(log.detailFailed)
    }

    @Test
    fun `ошибка загрузки видна у раскрытой записи`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))

        store.detailFailed()

        val log = store.log.value
        assertTrue(log.detailFailed)
        assertFalse(log.detailLoading)
        assertNull(log.detail)
    }

    @Test
    fun `ответ на свёрнутую запись игнорируется`() {
        store.open(RUN)
        store.page(page(listOf(summary(2))))
        store.toggle(ToolCallId("tc-2"))
        store.toggle(ToolCallId("tc-2"))

        store.detailLoaded(call(2))

        val log = store.log.value
        assertNull(log.detail, "после сворачивания ответ на прежний запрос не должен всплывать")
        assertNull(log.expanded)
    }

    /** Страница как её собирает хост: записи от новых к старым и курсор по последней. */
    private fun page(calls: List<ToolCallSummary>, hasMore: Boolean = false): HostMessage.ToolCallPage =
        HostMessage.ToolCallPage(
            requestId = RequestId("req-tool-calls"),
            runId = RUN,
            calls = calls,
            nextCursor = calls.lastOrNull()?.let { ToolCallCursor(it.at, it.id) },
            hasMore = hasMore,
        )

    private fun summary(index: Int): ToolCallSummary = ToolCallSummary(
        id = ToolCallId("tc-$index"),
        runId = RUN,
        tool = "read_file",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = index.toLong(),
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
        argumentsPreview = """{"path":"file-$index"}""",
        argumentsTruncated = false,
        resultPreview = "результат-$index",
    )

    private fun call(index: Int, runId: RunId = RUN): ToolCall = ToolCall(
        id = ToolCallId("tc-$index"),
        runId = runId,
        tool = "read_file",
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
        val OTHER_RUN: RunId = RunId("r-2")
        const val BASE_MILLIS: Long = 1_758_535_200_000
    }
}
