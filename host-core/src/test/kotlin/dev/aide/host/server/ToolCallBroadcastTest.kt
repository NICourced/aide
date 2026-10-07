package dev.aide.host.server

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.host.agent.StoreToolCallRecorder
import dev.aide.host.store.HostStore
import dev.aide.host.store.db.HostDatabase
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestDedupCache
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/**
 * T-1.3: сначала база, потом событие; рассылка не блокирует прогон и не теряет записи.
 *
 * Порядок «база, потом событие» (О-8) доказывается чтением базы в момент публикации, а не
 * на глаз: клиент по событию показывает вызов, и показать то, чего нет в истории, значит
 * соврать. Переполнение очереди проверяется на живой очереди без рассылки — так проверяется
 * главное свойство: роняется событие, но не запись.
 */
class ToolCallBroadcastTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        driver.close()
    }

    @Test
    fun `запись доступна в момент публикации события`() {
        val seenAtPublish = mutableListOf<ToolCall?>()
        // Публикация читает базу в момент события: если бы запись шла после публикации,
        // клиент получил бы событие о вызове, которого в журнале ещё нет.
        val recorder = StoreToolCallRecorder(
            store.toolCalls,
            ToolCallEvents { call -> seenAtPublish += store.toolCalls.byId(call.id) },
        )

        val call = call(0)
        recorder.record(call)

        assertEquals<List<ToolCall?>>(
            listOf(call),
            seenAtPublish,
            "событие обязано уходить после записи, а не до неё",
        )
    }

    @Test
    fun `событие о записанном вызове доходит до сессии`() {
        val sessions = ClientSessions()
        val received = CopyOnWriteArrayList<HostEvent>()
        sessions.add(recordingSession(received))

        val broadcaster = ToolCallBroadcaster(sessions)
        broadcaster.start(scope)
        val expected = call(0)
        broadcaster.publish(expected)

        val arrived = runBlocking {
            withTimeoutOrNull(WAIT_MILLIS) {
                while (received.none { it is HostEvent.ToolCallRecorded }) delay(POLL_MILLIS)
                true
            }
        }
        assertTrue(arrived == true, "сессия обязана получить событие о записанном вызове")
        assertEquals(expected, received.filterIsInstance<HostEvent.ToolCallRecorded>().single().call)
    }

    @Test
    fun `при переполнении очереди запись не теряется`() {
        // Рассылка намеренно не запущена: очередь в один слот переполняется сразу,
        // и событие о части вызовов не разойдётся — но записаться обязано каждое.
        val broadcaster = ToolCallBroadcaster(sessions = ClientSessions(), capacity = 1)
        val recorder = StoreToolCallRecorder(store.toolCalls, broadcaster)

        repeat(5) { recorder.record(call(it)) }

        val page = store.toolCalls.page(RUN, before = null, limit = 100)
        assertEquals(5, page.calls.size, "переполнение очереди теряет событие, но не запись в базе")
    }

    /** Сессия, которая раскладывает полученные кадры в [received]; запросы ей не приходят. */
    private fun recordingSession(received: MutableList<HostEvent>): ClientSession = ClientSession(
        handler = ClientMessageHandler { error("тесту не нужны запросы клиента") },
        hostVersion = ProtocolVersion.CURRENT,
        send = { bytes ->
            val message = (ProtocolCodec.decodeHostMessage(bytes) as? DecodeResult.Message)?.message
            if (message is HostMessage.Event) received += message.event
        },
        dedup = RequestDedupCache(),
        broadcast = {},
    )

    private fun call(index: Int): ToolCall = ToolCall(
        id = ToolCallId("tc-$index"),
        runId = RUN,
        tool = "read_file",
        arguments = """{"path":"file-$index"}""",
        result = "результат-$index",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = index.toLong(),
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = Instant.fromEpochMilliseconds(1_758_535_200_000 + index),
    )

    private companion object {
        val RUN: RunId = RunId("r-1")
        const val WAIT_MILLIS: Long = 5_000
        const val POLL_MILLIS: Long = 10
    }
}
