package dev.aide.client.state

import dev.aide.domain.AgentRun
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanDecision
import dev.aide.domain.RunId
import dev.aide.domain.RunCommand
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolKind
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ToolCallCursor
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
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
                    assertTrue(
                        withTimeoutOrNull(WAIT_MILLIS) { client.hostShuttingDown.first { it } } == true,
                        "событие остановки хоста обязано дойти до клиента",
                    )
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

    /**
     * Команда управления прогоном уходит на хост отдельным запросом с новым идентификатором.
     *
     * Тестов на `controlRun` не было вовсе: пауза, продолжение и стоп проверялись на уровне
     * хоста, а клиентская половина пути — нет. Повтор с тем же `RequestId` хост принял бы
     * за повтор и вернул прежний ответ, поэтому уникальность здесь — часть контракта.
     */
    @Test
    fun `команда управления прогоном уходит с новым RequestId`() {
        runBlocking {
            val runId = RunId("r-1")

            client.controlRun(runId, RunCommand.PAUSE)
            client.controlRun(runId, RunCommand.RESUME)
            client.controlRun(runId, RunCommand.STOP)

            val commands = connection.takeRequests().filterIsInstance<ClientMessage.RunControl>()
            assertEquals(3, commands.size, "все три команды обязаны дойти до хоста")
            assertEquals(
                3,
                commands.map { it.requestId }.distinct().size,
                "повтор с тем же RequestId хост принял бы за повтор и вернул прежний ответ",
            )
            assertEquals(
                listOf(RunCommand.PAUSE, RunCommand.RESUME, RunCommand.STOP),
                commands.map { it.command },
                "порядок команд обязан сохраниться",
            )
            assertEquals(listOf(runId, runId, runId), commands.map { it.runId }, "команда адресуется прогону")
        }
    }

    /** Отказ хоста виден вызывающему, а следующая команда проходит: клиент не залипает. */
    @Test
    fun `отказ управления прогоном возвращается ошибкой и не ломает клиент`() {
        runBlocking {
            var refuseFirst = true
            connection.respond = { message ->
                val control = assertIs<ClientMessage.RunControl>(message)
                if (refuseFirst) {
                    refuseFirst = false
                    HostMessage.Failure(control.requestId, ProtocolError.NotFound("нет такого прогона"))
                } else {
                    HostMessage.RunControlled(control.requestId, control.runId)
                }
            }

            val refused = client.controlRun(RunId("нет-такого"), RunCommand.STOP)
            val error = assertIs<HostCallException>(refused.exceptionOrNull()).error
            assertIs<ProtocolError.NotFound>(error, "типизированный отказ обязан доехать до вызывающего")

            assertTrue(
                client.controlRun(RunId("r-1"), RunCommand.PAUSE).isSuccess,
                "после отказа клиент обязан работать дальше",
            )
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
    fun `прогресс обновляет метрики своего прогона и не создаёт новый`() {
        runBlocking {
            val run = runFixture()
            connection.emit(HostEvent.RunStateChanged(run))
            assertTrue(
                awaitProcessed { client.session.value.runs.any { it.id == run.id } },
                "прогон обязан появиться в сессии до прогресса",
            )

            connection.emit(HostEvent.RunProgress(run.id, elapsedMillis = 1_500, cost = Cost(25, known = true)))
            assertTrue(
                awaitProcessed { client.session.value.runs.singleOrNull()?.elapsedMillis == 1_500L },
                "прогресс обязан дойти до сессии: ${client.session.value.runs}",
            )

            val updated = client.session.value.runs.single()
            assertEquals(1, client.session.value.runs.size, "прогресс не создаёт второй прогон")
            assertEquals(run.id, updated.id)
            assertEquals(1_500, updated.elapsedMillis, "время из события обязано попасть в сессию")
            assertEquals(Cost(25, known = true), updated.cost, "стоимость из события обязана попасть в сессию")
            assertEquals(RunState.RUNNING, updated.state, "прогресс не подменяет состояние прогона")
        }
    }

    @Test
    fun `прогресс неизвестного прогона игнорируется`() {
        runBlocking {
            connection.emit(HostEvent.RunProgress(RunId("чужой"), elapsedMillis = 500, cost = Cost()))
            // Событие за ним обрабатывается тем же сборщиком: дождавшись его, проверяем,
            // что прогресс уже обработан. Ожидание ограничено, иначе регрессия повесила бы тест.
            connection.emit(HostEvent.HostShuttingDown)
            assertTrue(awaitShuttingDown(), "событие-маркер обязано дойти до клиента")

            assertTrue(
                client.session.value.runs.isEmpty(),
                "прогресс не рисует прогон, о котором клиент не знает: ${client.session.value.runs}",
            )
        }
    }

    @Test
    fun `запоздавший прогресс не откатывает итог завершённого прогона`() {
        runBlocking {
            val finished = runFixture().copy(
                state = RunState.FINISHED,
                finishedAt = TOOL_CALL_AT,
                elapsedMillis = 9_000,
                cost = Cost(40, known = true),
            )
            connection.emit(HostEvent.RunStateChanged(finished))
            assertTrue(
                awaitProcessed { client.session.value.runs.any { it.id == finished.id } },
                "завершённый прогон обязан появиться в сессии",
            )

            connection.emit(HostEvent.RunProgress(finished.id, elapsedMillis = 1_000, cost = Cost(5, known = true)))
            connection.emit(HostEvent.HostShuttingDown)
            assertTrue(awaitShuttingDown(), "событие-маркер обязано дойти до клиента")

            val stored = client.session.value.runs.single()
            assertEquals(9_000, stored.elapsedMillis, "итог завершённого прогона не переписывается")
            assertEquals(Cost(40, known = true), stored.cost)
        }
    }

    /**
     * Ждёт, пока клиент выполнит [condition]; false — ожидание истекло.
     *
     * Предел обязателен: без него регрессия в обработке событий повесила бы тест, а не
     * уронила его с внятным сообщением.
     */
    private suspend fun awaitProcessed(condition: () -> Boolean): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
        while (!condition()) delay(POLL_MILLIS)
        true
    } == true

    /** Ждёт события остановки хоста; false — оно не дошло за [WAIT_MILLIS]. */
    private suspend fun awaitShuttingDown(): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
        client.hostShuttingDown.first { shuttingDown -> shuttingDown }
        true
    } == true

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

    @Test
    fun `событие ApprovalRequested ставит pendingApproval в сессию`() = runBlocking {
        val request = approvalRequest()

        connection.emit(HostEvent.ApprovalRequested(request))

        assertTrue(
            awaitProcessed { client.session.value.pendingApproval == request },
            "запрос подтверждения обязан появиться в сессии",
        )
    }

    @Test
    fun `answerApproval уходит с новым RequestId и очищает pendingApproval`() = runBlocking {
        val request = approvalRequest()
        connection.emit(HostEvent.ApprovalRequested(request))
        awaitProcessed { client.session.value.pendingApproval == request }

        client.answerApproval(request.runId, request.callId, ApprovalDecision.ALLOW_ONCE)

        val answers = connection.takeRequests().filterIsInstance<ClientMessage.ApprovalAnswer>()
        assertEquals(1, answers.size, "ответ обязан уйти на хост")
        assertEquals(request.runId, answers.single().runId)
        assertEquals(request.callId, answers.single().callId)
        assertEquals(ApprovalDecision.ALLOW_ONCE, answers.single().decision)
        assertEquals(
            1,
            answers.map { it.requestId }.distinct().size,
            "повтор с тем же RequestId хост принял бы за повтор и вернул прежний ответ",
        )
        assertEquals(null, client.session.value.pendingApproval, "диалог обязан закрыться сразу")
    }

    @Test
    fun `при реконнекте клиент запрашивает ожидающие подтверждения`() = runBlocking {
        val request = approvalRequest()
        connection.emit(HostEvent.ApprovalRequested(request))
        awaitProcessed { client.session.value.pendingApproval == request }
        connection.takeRequests()

        connection.setConnected(reconnected = true)

        val pending = mutableListOf<ClientMessage.PendingApprovals>()
        val found = withTimeoutOrNull(WAIT_MILLIS) {
            while (pending.isEmpty()) {
                pending.addAll(connection.takeRequests().filterIsInstance<ClientMessage.PendingApprovals>())
                delay(POLL_MILLIS)
            }
            true
        } == true
        assertTrue(found, "после реконнекта обязан уйти запрос ожидающих подтверждений")
        assertEquals(1, pending.size, "запрос ожидающих подтверждений обязан уйти ровно один раз")
    }

    @Test
    fun `при первом подключении клиент запрашивает ожидающие подтверждения`() = runBlocking {
        val initialConnection = FakeHostConnection()
        val initialClient = HostClient(initialConnection, scope, requestIdPrefix = "initial")
        initialClient.start()

        val found = withTimeoutOrNull(WAIT_MILLIS) {
            while (initialConnection.takeRequests().none { it is ClientMessage.PendingApprovals }) {
                delay(POLL_MILLIS)
            }
            true
        } == true

        assertTrue(found, "первое подключение тоже обязано спросить ожидающие подтверждения")
    }

    private fun approvalRequest(): ToolApprovalRequest = ToolApprovalRequest(
        callId = ToolCallId("tc-approval-1"),
        runId = RunId("r-1"),
        tool = "write_file",
        arguments = """{"path":"src/App.kt","content":"x"}""",
        kind = ToolKind.WRITE,
        changesRepository = true,
    )

    private companion object {
        const val REPO_PATH = "/projects/aide"
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
        val TOOL_CALL_AT: Instant = Instant.fromEpochMilliseconds(1_758_535_200_000)
    }
}
