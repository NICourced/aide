package dev.aide.host.agent

import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.AgentModels
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.host.EmbeddedHost
import dev.aide.host.git.GitCliFixture
import dev.aide.host.workspace.TempRepoFixture
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
 * T-1.18 тем же путём: прогон идёт в ветке задачи `ai/<task-id>`, клиент узнаёт об этом
 * из состояния хоста после переключения, а перезапуск хоста ветку не пересоздаёт.
 *
 * Сеть и реальный провайдер не нужны: модель подставляется скриптованной (О-11).
 * Репозиторий нужен настоящий: ветка задачи создаётся в нём.
 */
class AgentRunProtocolTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    /** Репозиторий с коммитом: прогон начинается в ветке задачи (T-1.18). */
    private val repo = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
        repo.close()
    }

    @Test
    fun `клиент ставит задачу, получает события и доходит до finished`() = runBlocking {
        val host = EmbeddedHost.open(
            databasePath = tempDatabase(),
            models = scripted(TextModel()),
            planner = fixedPlanner(2),
        )
        try {
            val (connection, client) = newClient(host, "agent")
            client.start()
            awaitConnected(connection)
            openRepository(client)

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
                models = scripted(model),
                planner = fixedPlanner(2),
            )
            try {
                val (connection, client) = newClient(host, "control")
                client.start()
                awaitConnected(connection)
                openRepository(client)
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
        val host = EmbeddedHost.open(
            databasePath = tempDatabase(),
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
        try {
            val (firstConnection, first) = newClient(host, "early")
            first.start()
            awaitConnected(firstConnection)
            openRepository(first)
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
        val host = EmbeddedHost.open(
            databasePath = tempDatabase(),
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
        try {
            val (connection, client) = newClient(host, "dedup")
            client.start()
            awaitConnected(connection)
            openRepository(client)

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
            models = scripted(BlockingModel()),
            planner = fixedPlanner(1),
        )
        val interruptedId = try {
            val (connection, client) = newClient(firstHost, "before")
            client.start()
            awaitConnected(connection)
            openRepository(client)
            client.postTask("долгая задача", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

            val running = awaitRun(client) { it.state == RunState.RUNNING }
            assertNotNull(running, "прогон обязан дойти до RUNNING: ${client.session.value.runs}")
            running.id
        } finally {
            firstHost.close()
        }

        // Тот же файл базы: прогон остался незавершённым, и новый хост обязан это заметить.
        val secondHost = EmbeddedHost.open(
            databasePath = database,
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
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

    @Test
    fun `прогон идёт в ветке задачи, и клиент видит её в состоянии хоста`() = runBlocking {
        val host = EmbeddedHost.open(
            databasePath = tempDatabase(),
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
        try {
            val (connection, client) = newClient(host, "branch")
            client.start()
            awaitConnected(connection)
            openRepository(client)

            // Открытие репозитория состояние обновило: дальше клиент не спрашивает хост сам,
            // и ветку задачи он обязан увидеть потому, что хост разослал событие о смене
            // воркспейса после переключения (T-1.18).
            assertEquals("master", awaitBranch(client, "master"), "состояние открытого репозитория доходит до клиента")
            val headBefore = headOfRepo()

            val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
            assertNotNull(awaitRun(client) { it.state == RunState.FINISHED }, "прогон обязан завершиться")
            val taskBranch = "ai/${taskId.value}"

            assertEquals(taskBranch, awaitBranch(client, taskBranch), "шапка обязана показать ветку задачи")
            assertEquals(taskBranch, GitCliFixture.currentBranch(repo.root), "репозиторий остался в ветке задачи")
            assertEquals(headBefore, headOfRepo(), "ветка создана от HEAD: сам HEAD не сдвинулся")
            assertEquals(
                "master",
                assertNotNull(awaitTask(client) { it.id == taskId }).baseBranch,
                "базовая ветка уезжает клиенту вместе с задачей",
            )
        } finally {
            host.close()
        }
    }

    @Test
    fun `после перезапуска хоста ветка задачи на месте и заново не создаётся`() = runBlocking {
        val database = tempDatabase()
        val firstHost = EmbeddedHost.open(
            databasePath = database,
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
        val started = try {
            val (connection, client) = newClient(firstHost, "before-restart")
            client.start()
            awaitConnected(connection)
            openRepository(client)
            val taskId = client.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
            assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })
            assertEquals("ai/${taskId.value}", awaitBranch(client, "ai/${taskId.value}"))
            taskId to refOf("ai/${taskId.value}")
        } finally {
            firstHost.close()
        }
        val (taskId, refBeforeRestart) = started

        // Тот же файл базы: ветка и запись о задаче лежат в репозитории и в базе, а не
        // в памяти хоста, поэтому перезапуск не создаёт ветку заново (T-1.18).
        val secondHost = EmbeddedHost.open(
            databasePath = database,
            models = scripted(TextModel()),
            planner = fixedPlanner(1),
        )
        try {
            val (connection, client) = newClient(secondHost, "after-restart")
            client.start()
            awaitConnected(connection)
            openRepository(client)
            client.agentStatus().getOrThrow()

            val restored = client.session.value.tasks.single { it.id == taskId }
            assertEquals("ai/${taskId.value}", restored.branch)
            assertEquals("master", restored.baseBranch, "записанная база переживает перезапуск хоста")
            assertEquals("ai/${taskId.value}", awaitBranch(client, "ai/${taskId.value}"))
            assertEquals(refBeforeRestart, refOf("ai/${taskId.value}"), "ref ветки не пересоздан")
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

    /**
     * Открывает репозиторий воркспейса.
     *
     * Нужен каждому прогону: ветка задачи создаётся в открытом воркспейсе (T-1.18),
     * а без открытого репозитория задача падает кодом `no_workspace` — переключать нечего.
     */
    private suspend fun openRepository(client: HostClient) {
        assertNotNull(client.openWorkspace(repo.root.toString()), "репозиторий обязан открыться")
    }

    /** Ветка из состояния хоста, каким его видит клиент; null — состояния ещё нет. */
    private fun branchInHeader(client: HostClient): String? = client.session.value.hostState?.branch

    /** Ждёт, пока клиент увидит эту ветку: состояние он перезапрашивает сам, по событию. */
    private suspend fun awaitBranch(client: HostClient, branch: String): String? = withTimeoutOrNull(10_000) {
        while (branchInHeader(client) != branch) delay(20)
        branch
    }

    /** Хеш ссылки ветки по версии git: ref — то, что повторный запуск переписывать не должен. */
    private fun refOf(branch: String): String =
        GitCliFixture.run(listOf("git", "rev-parse", "refs/heads/$branch"), repo.root).output.trim()

    /** Короткий хеш HEAD по версии git: ветка создаётся от него, а не сдвигает его. */
    private fun headOfRepo(): String = GitCliFixture.headShortHash(repo.root)

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

    private fun fixedPlanner(stepCount: Int): RunPlanner = RunPlanner { _, _ ->
        List(stepCount) { index -> PlanStep(index = index, summary = "шаг $index", status = StepStatus.PENDING) }
    }

    /**
     * Источник модели со скриптованным клиентом (T-1.56).
     *
     * Настоящий провайдер подставляется в этот тест моделью, а не реестром: проверяются
     * транспорт и состояния прогона, а не разбор ответа провайдера (О-11). Проверка
     * доступа объявлена явно: у источника без транспорта проверять нечего, и говорить
     * об этом обязан он сам, а не умолчание общего интерфейса.
     */
    private fun scripted(client: LlmClient): AgentModels = object : AgentModels {

        override fun current(): Result<ConfiguredModel> = Result.success(ConfiguredModel("test/scripted", client))

        override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
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
