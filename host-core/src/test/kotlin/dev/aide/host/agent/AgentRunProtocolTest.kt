package dev.aide.host.agent

import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.host.EmbeddedHost
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.RequestId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.1 на настоящем сервере, настоящем клиенте и настоящей базе: постановка задачи
 * доходит событием до сессии, подключившийся позже клиент узнаёт состояние запросом,
 * пауза/продолжение/стоп проходят путь клиент → хост → событие, а после перезапуска
 * хоста незавершённый прогон становится прерванным.
 *
 * Сеть и реальный провайдер не нужны: модель подставляется скриптованной (О-11).
 */
class AgentRunProtocolTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `клиент ставит задачу, получает события и доходит до finished`() = runBlocking {
        val host = EmbeddedHost.open(databasePath = tempDatabase(), llmClient = TextModel(), planner = fixedPlanner(2))
        try {
            val (connection, client) = newClient(host, "agent")
            client.start()
            awaitConnected(connection)

            val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

            val finished = awaitRun(client) { it.state == RunState.FINISHED }
            assertNotNull(finished, "прогон обязан дойти до FINISHED, текущие: ${client.session.value.runs}")
            assertEquals(taskId, finished.taskId)
        } finally {
            host.close()
        }
    }

    /**
     * Главный дефект, найденный ревью: на чистой установке (`NotConfiguredLlmClient`)
     * планирование падает, и без статуса задачи пользователь не видел бы вообще ничего.
     */
    @Test
    fun `при ненастроенной модели задача доходит до failed и это видно событием`() = runBlocking {
        val host = EmbeddedHost.open(databasePath = tempDatabase())
        try {
            val (connection, client) = newClient(host, "no-model")
            client.start()
            awaitConnected(connection)

            client.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

            val failed = awaitTask(client) { it.status == TaskStatus.FAILED }
            assertNotNull(failed, "отказ обязан дойти событием, текущие: ${client.session.value.tasks}")
            assertEquals("NOT_CONFIGURED", failed.failureReason)
            assertTrue(client.session.value.runs.isEmpty(), "до плана прогон не создаётся")
        } finally {
            host.close()
        }
    }

    @Test
    fun `пауза, продолжение и стоп проходят путь клиент — хост — событие`() {
        // Тело блочное: `= runBlocking { … assertNotNull(…) }` вернул бы AgentRun,
        // и JUnit 5 молча не запустил бы тест (класс дефекта из AGENTS.md).
        runBlocking {
            val model = GateModel()
            val host = EmbeddedHost.open(
                databasePath = tempDatabase(),
                llmClient = model,
                planner = fixedPlanner(2),
            )
            try {
                val (connection, client) = newClient(host, "control")
                client.start()
                awaitConnected(connection)
                client.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val running = awaitRun(client) { it.state == RunState.RUNNING }
                assertNotNull(running, "прогон обязан дойти до RUNNING: ${client.session.value.runs}")

                client.controlRun(running.id, RunCommand.PAUSE).getOrThrow()
                model.release()
                assertNotNull(
                    awaitRun(client) { it.id == running.id && it.state == RunState.PAUSED },
                    "пауза — событием",
                )

                client.controlRun(running.id, RunCommand.RESUME).getOrThrow()
                assertNotNull(
                    awaitRun(client) { it.id == running.id && it.state == RunState.FINISHED },
                    "продолжение — событием",
                )

                // Стоп на новом прогоне: модель снова не отвечает, стоп обязан прервать вызов.
                model.block()
                client.postTask("Ещё задача", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val secondRun = awaitRun(client) { it.state == RunState.RUNNING && it.id != running.id }
                assertNotNull(secondRun, "второй прогон обязан начаться: ${client.session.value.runs}")

                client.controlRun(secondRun.id, RunCommand.STOP).getOrThrow()
                assertNotNull(
                    awaitRun(client) { it.id == secondRun.id && it.state == RunState.STOPPED },
                    "стоп — событием",
                )
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `подключившийся позже клиент узнаёт состояние агента запросом`() = runBlocking {
        val host = EmbeddedHost.open(databasePath = tempDatabase(), llmClient = TextModel(), planner = fixedPlanner(1))
        try {
            val (firstConnection, first) = newClient(host, "early")
            first.start()
            awaitConnected(firstConnection)
            first.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
            assertNotNull(awaitRun(first) { it.state == RunState.FINISHED })

            // Второй клиент не видел ни одного события: состояние он получает запросом.
            val (secondConnection, second) = newClient(host, "late")
            second.start()
            awaitConnected(secondConnection)
            second.agentStatus().getOrThrow()

            assertTrue(
                second.session.value.runs.any { it.state == RunState.FINISHED },
                "снимок отдаёт прогоны: ${second.session.value.runs}",
            )
            assertEquals(1, second.session.value.tasks.size, "снимок отдаёт задачи")
        } finally {
            host.close()
        }
    }

    @Test
    fun `повтор PostTask с тем же идентификатором не создаёт вторую задачу`() = runBlocking {
        val host = EmbeddedHost.open(databasePath = tempDatabase(), llmClient = TextModel(), planner = fixedPlanner(1))
        try {
            val (connection, client) = newClient(host, "dedup")
            client.start()
            awaitConnected(connection)

            val requestId = RequestId("post-dup")
            val post = ClientMessage.PostTask(requestId, "Почини", AutonomyMode.ASK_BEFORE_CHANGES)
            val first = connection.request(post)
            val second = connection.request(post)
            assertIs<HostMessage.TaskPosted>(first)
            assertEquals(first, second, "повтор обязан вернуть тот же ответ")

            client.agentStatus().getOrThrow()
            assertEquals(1, client.session.value.tasks.size, "повтор не создаёт вторую задачу")
        } finally {
            host.close()
        }
    }

    @Test
    fun `после перезапуска хоста незавершённый прогон становится interrupted`() = runBlocking {
        val database = tempDatabase()
        val firstHost = EmbeddedHost.open(
            databasePath = database,
            llmClient = BlockingModel(),
            planner = fixedPlanner(1),
        )
        val interruptedId = try {
            val (connection, client) = newClient(firstHost, "before")
            client.start()
            awaitConnected(connection)
            client.postTask("долгая задача", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

            val running = awaitRun(client) { it.state == RunState.RUNNING }
            assertNotNull(running, "прогон обязан дойти до RUNNING: ${client.session.value.runs}")
            running.id
        } finally {
            firstHost.close()
        }

        // Тот же файл базы: прогон остался незавершённым, и новый хост обязан это заметить.
        val secondHost = EmbeddedHost.open(databasePath = database, llmClient = TextModel(), planner = fixedPlanner(1))
        try {
            val (connection, client) = newClient(secondHost, "after")
            client.start()
            awaitConnected(connection)
            client.agentStatus().getOrThrow()
            val restored = client.session.value.runs.single { it.id == interruptedId }

            assertEquals(RunState.INTERRUPTED, restored.state)
            assertNotNull(restored.finishedAt)
            assertEquals("host_restart", restored.interruptReason)
        } finally {
            secondHost.close()
        }
    }

    private fun newClient(host: EmbeddedHost, prefix: String): Pair<KtorHostConnection, HostClient> {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        return connection to HostClient(connection, scope, requestIdPrefix = prefix)
    }

    private suspend fun awaitConnected(connection: KtorHostConnection) {
        val state = withTimeoutOrNull(10_000) {
            while (connection.state.value !is ConnectionState.Connected) delay(20)
            connection.state.value
        }
        assertNotNull(state, "клиент не подключился: ${connection.state.value}")
    }

    private suspend fun awaitRun(client: HostClient, predicate: (AgentRun) -> Boolean): AgentRun? =
        withTimeoutOrNull(10_000) {
            while (!client.session.value.runs.any(predicate)) delay(20)
            client.session.value.runs.first(predicate)
        }

    private suspend fun awaitTask(client: HostClient, predicate: (Task) -> Boolean): Task? =
        withTimeoutOrNull(10_000) {
            while (!client.session.value.tasks.any(predicate)) delay(20)
            client.session.value.tasks.first(predicate)
        }

    private fun fixedPlanner(stepCount: Int): RunPlanner = RunPlanner {
        List(stepCount) { index -> PlanStep(index = index, summary = "шаг $index", status = StepStatus.PENDING) }
    }

    private fun tempDatabase(): Path = Files.createTempFile("aide-agent", ".db")

    /** Модель отвечает сразу: прогон доходит до завершения. */
    private class TextModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }

    /** Модель не отвечает никогда: прогон остаётся RUNNING до перезапуска хоста. */
    private class BlockingModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse = awaitCancellation()
    }

    /**
     * Модель, ждущая явного разрешения: так тест управляет моментом паузы и стопа.
     *
     * `block()` ставит новый барьер, поэтому следующий прогон снова зависает на вызове —
     * именно так проверяется стоп, который обязан прервать незавершённый вызов.
     */
    private class GateModel : LlmClient {
        @Volatile
        private var gate = CompletableDeferred<Unit>()

        fun release() {
            gate.complete(Unit)
        }

        fun block() {
            gate = CompletableDeferred()
        }

        override suspend fun complete(request: LlmRequest): LlmResponse {
            gate.await()
            return LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
        }
    }
}
