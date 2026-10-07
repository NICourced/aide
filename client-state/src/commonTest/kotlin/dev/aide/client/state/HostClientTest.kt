package dev.aide.client.state

import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanDecision
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostEvent
import dev.aide.protocol.ToolCallCursor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/**
 * Обновление сессии после открытия воркспейса проверяется на обоих порядках: ответ раньше
 * события и событие раньше ответа. Второй порядок — не экзотика: хост отправляет ответ
 * и рассылает [HostEvent.WorkspaceChanged] подряд, а клиент разбирает их в разных корутинах,
 * и порядок обработки не определён.
 */
class HostClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = FakeHostConnection()
    private lateinit var client: HostClient

    @BeforeTest
    fun setUp() {
        client = HostClient(connection, scope, requestIdPrefix = "test")
        client.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `событие об открытии, пришедшее раньше ответа, не теряется`() {
        runBlocking {
            connection.beforeRespond = { message ->
                if (message is ClientMessage.OpenWorkspace) {
                    // Порядок «событие раньше ответа» держит барьер, а не задержка: обработчик
                    // разбирает события по порядку, поэтому реакция на следующее событие означает,
                    // что WorkspaceChanged уже обработан — и обработан при сессии ещё без открытого
                    // воркспейса. Именно в этом порядке событие раньше и терялось.
                    connection.emit(HostEvent.WorkspaceChanged(connection.workspaceId))
                    connection.emit(HostEvent.HostShuttingDown)
                    client.hostShuttingDown.first { shuttingDown -> shuttingDown }
                }
            }

            val opened = client.openWorkspace(REPO_PATH)

            assertEquals(connection.workspaceId, opened)
            assertTrue(
                awaitSessionRefresh(),
                "Событие, пришедшее раньше ответа, обязано обновить сессию, а не потеряться",
            )
            assertNotNull(client.session.value.hostState, "То же обновление запрашивает и состояние хоста")

            val requests = connection.takeRequests()
            assertEquals(1, requests.count { it is ClientMessage.FileTree }, "Дерево запрашивается один раз")
            assertEquals(1, requests.count { it is ClientMessage.HostState }, "Состояние запрашивается один раз")
        }
    }

    @Test
    fun `событие об открытии обновляет сессию ровно один раз`() {
        runBlocking {
            client.openWorkspace(REPO_PATH)

            // Ответ пришёл раньше события: клиент ещё ничего не дозапрашивал.
            assertEquals(
                0,
                connection.takeRequests().count { it is ClientMessage.FileTree },
                "Открытие воркспейса не заменяет обновление по событию",
            )

            connection.emit(HostEvent.WorkspaceChanged(connection.workspaceId))

            assertTrue(awaitSessionRefresh(), "Обновление по событию обязано дойти до сессии")
            assertEquals(
                1,
                connection.takeRequests().count { it is ClientMessage.FileTree },
                "Повторное обновление того же открытия — лишний запрос дерева",
            )
        }
    }

    /** Ждёт первого обновления сессии; false означает, что обновления не было вовсе. */
    private suspend fun awaitSessionRefresh(): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
        while (client.session.value.tree == null) delay(POLL_MILLIS)
        true
    } == true

    @Test
    fun `каждая страница журнала уходит с новым RequestId`() {
        runBlocking {
            val runId = RunId("r-1")

            client.toolLogClient.toolCalls(runId)
            client.toolLogClient.toolCalls(runId, ToolCallCursor(TOOL_CALL_AT, ToolCallId("tc-1")), limit = 50)

            val pages = connection.takeRequests().filterIsInstance<ClientMessage.ToolCalls>()
            assertEquals(2, pages.size, "оба запроса журнала обязаны дойти до хоста")
            assertEquals(
                2,
                pages.map { it.requestId }.distinct().size,
                "повтор с тем же RequestId хост принял бы за повтор и вернул прежнюю страницу",
            )
            assertEquals(ToolCallId("tc-1"), pages[1].cursor?.id, "курсор обязан уехать на хост")
            assertEquals(50, pages[1].limit)
        }
    }

    @Test
    fun `решение по плану уходит с новым RequestId`() {
        runBlocking {
            val runId = RunId("r-1")

            client.decidePlan(runId, PlanDecision.Approve)
            client.decidePlan(runId, PlanDecision.Replan("уточни шаги"))

            val decisions = connection.takeRequests().filterIsInstance<ClientMessage.PlanDecision>()
            assertEquals(2, decisions.size, "оба решения обязаны дойти до хоста")
            assertEquals(
                2,
                decisions.map { it.requestId }.distinct().size,
                "повтор с тем же RequestId хост принял бы за повтор и вернул прежний ответ",
            )
            assertEquals(PlanDecision.Approve, decisions[0].decision)
            assertEquals(PlanDecision.Replan("уточни шаги"), decisions[1].decision, "комментарий обязан уехать целиком")
        }
    }

    @Test
    fun `смена состояния прогона событием обновляет сессию`() {
        runBlocking {
            // Состояние после решения приходит не ответом, а событием; экран живёт сессией.
            val run = runFixture()
            connection.emit(HostEvent.RunStateChanged(run))

            val stored = withTimeoutOrNull(WAIT_MILLIS) {
                while (client.session.value.runs.none { it.id == run.id }) delay(POLL_MILLIS)
                client.session.value.runs.first { it.id == run.id }
            }

            assertEquals(run, stored, "прогон из события обязан попасть в сессию")
        }
    }

    /** Прогон для проверки события: состояние и минимальные поля. */
    private fun runFixture(): AgentRun = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.RUNNING,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        startedAt = TOOL_CALL_AT,
    )

    @Test
    fun `живая запись журнала доходит до подписчика`() {
        runBlocking {
            val expected = toolCall()
            val subscribed = CompletableDeferred<Unit>()
            val received = async {
                client.toolCallEvents.onSubscription { subscribed.complete(Unit) }.first()
            }

            subscribed.await()
            connection.emit(HostEvent.ToolCallRecorded(expected))

            assertEquals(expected, withTimeoutOrNull(WAIT_MILLIS) { received.await() })
        }
    }

    private fun toolCall(): ToolCall = ToolCall(
        id = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "read_file",
        arguments = """{"path":"src/App.kt"}""",
        result = "содержимое",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 1,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = TOOL_CALL_AT,
    )

    private companion object {
        const val REPO_PATH = "/projects/aide"
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        val TOOL_CALL_AT: Instant = Instant.fromEpochMilliseconds(1_758_535_200_000)
    }
}
