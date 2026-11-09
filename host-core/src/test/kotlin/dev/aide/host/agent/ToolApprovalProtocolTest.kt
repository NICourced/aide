package dev.aide.host.agent

import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmRole
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.provider.AgentModels
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.answerApproval
import dev.aide.domain.AgentRun
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.Permission
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolKind
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.host.EmbeddedHost
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.git.GitCliFixture
import dev.aide.tools.file.WriteFileTool
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.13 сквозным путём: при разрешении ASK вызов write_file останавливается,
 * клиент видит запрос подтверждения, отвечает ALLOW_SESSION, файл записывается,
 * журнал помнит решение, а следующий вызов этого инструмента не спрашивает снова.
 */
class ToolApprovalProtocolTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `write_file при ASK ждет подтверждения и записывает файл после ALLOW_SESSION`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-approval-repo", "hello.txt", CONTENT)
            val database = Files.createTempFile("aide-approval", ".db")

            val model = ApprovalToolCallingModel("write_file", """{"path":"hello.txt","content":"$WRITTEN"}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")

                val approver = launch {
                    val request = awaitPendingApproval(client)
                    assertNotNull(request, "запрос подтверждения обязан появиться у клиента")
                    assertEquals("write_file", request.tool)
                    assertEquals(ToolKind.WRITE, request.kind)
                    client.answerApproval(request.runId, request.callId, ApprovalDecision.ALLOW_SESSION)
                }

                client.postTaskApprovingPlan("Запиши файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                approver.join()
                val finished = finish(client)
                assertSessionAllowWrite(host)
                finished
            } finally {
                host.close()
            }

            assertEquals(WRITTEN, repo.resolve("hello.txt").toFile().readText(), "файл обязан быть записан")

            val recorded = journal(database, run, WriteFileTool.TOOL_NAME)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome)
            assertTrue(recorded.requiredApproval)
            assertEquals(ApprovalDecision.ALLOW_SESSION, recorded.approval)
        }
    }

    @Test
    fun `ALLOW_SESSION не спрашивает подтверждение у второго вызова`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-approval-session-repo", "hello.txt", CONTENT)
            val database = Files.createTempFile("aide-approval-session", ".db")

            val model = ApprovalToolCallingModel("write_file", """{"path":"hello2.txt","content":"$WRITTEN"}""")
            val host = host(database, model)
            try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()))

                launch {
                    val request = awaitPendingApproval(client)
                    assertNotNull(request)
                    client.answerApproval(request.runId, request.callId, ApprovalDecision.ALLOW_SESSION)
                }

                client.postTaskApprovingPlan("Запиши файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val firstRun = finish(client)
                assertSessionAllowWrite(host)

                // Второй прогон с тем же инструментом: разрешение на сессию должно сработать.
                client.postTaskApprovingPlan("Запиши ещё файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val secondRun = finish(client, firstRun)

                val secondRecorded = journal(database, secondRun, WriteFileTool.TOOL_NAME)
                assertEquals(ToolOutcome.SUCCESS, secondRecorded.outcome)
                assertFalse(
                    secondRecorded.requiredApproval,
                    "второй вызов при разрешении на сессию не должен спрашивать",
                )
                assertEquals(WRITTEN, repo.resolve("hello2.txt").toFile().readText())
            } finally {
                host.close()
            }
        }
    }

    /** Ждёт, пока у клиента появится запрос подтверждения; null, если не появился. */
    private suspend fun awaitPendingApproval(client: HostClient) = withTimeoutOrNull(10_000) {
        while (client.session.value.pendingApproval == null) delay(20)
        client.session.value.pendingApproval
    }

    private fun cleanRepoWithFile(prefix: String, name: String, content: String): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory(prefix))
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), repo)
        repo.resolve(name).writeText(content)
        GitCliFixture.run(listOf("git", "add", name), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файл для записи"), repo)
        return repo
    }

    private fun host(database: Path, model: LlmClient): EmbeddedHost = EmbeddedHost.open(
        databasePath = database,
        models = object : AgentModels {
            override fun current(): Result<ConfiguredModel> = Result.success(ConfiguredModel("test/scripted", model))

            override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
        },
        planner = RunPlanner { _, _, _ ->
            listOf(PlanStep(index = 0, summary = "записать файл", status = StepStatus.PENDING))
        },
    )

    private suspend fun openClient(host: EmbeddedHost): HostClient {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        val client = HostClient(connection, scope, requestIdPrefix = "approval")
        client.start()
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            while (connection.state.value !is ConnectionState.Connected) delay(POLL_MILLIS)
        } ?: error("клиент не подключился: ${connection.state.value}")
        return client
    }

    private suspend fun finish(client: HostClient, after: AgentRun? = null): AgentRun {
        val finished = withTimeoutOrNull(RUN_TIMEOUT_MILLIS) {
            while (!client.session.value.runs.any { it.state == RunState.FINISHED && it != after }) delay(POLL_MILLIS)
            client.session.value.runs.first { it.state == RunState.FINISHED && it != after }
        }
        return assertNotNull(finished, "прогон обязан дойти до finished: ${client.session.value.runs}")
    }

    private fun journal(database: Path, run: AgentRun, tool: String): ToolCall {
        val store = DatabaseFactory.open(database)
        return try {
            store.toolCalls.forRun(run.id).single { it.tool == tool }
        } finally {
            store.close()
        }
    }

    private fun assertSessionAllowWrite(host: EmbeddedHost) {
        val permissions = host.graph.koin.get<PendingApprovals>()
        val toolPermission = permissions.sessionPermission(WriteFileTool.TOOL_NAME)
        assertNotNull(toolPermission, "сессионное разрешение для write_file должно быть записано")
        assertEquals(Permission.ALLOW, toolPermission.write, "разрешение на запись должно быть ALLOW")
    }

    private companion object {
        const val CONTENT: String = "исходное"
        const val WRITTEN: String = "изменено"
        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000
    }
}

private const val APPROVAL_MODEL_DELAY_MILLIS: Long = 5

/**
 * Модель, просящая инструмент, а затем заканчивающая шаг.
 *
 * Запросы складываются в потокобезопасный список; второй ответ — пустой текст,
 * чтобы прогон завершился.
 */
private class ApprovalToolCallingModel(private val tool: String, private val arguments: String) : LlmClient {

    val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        delay(APPROVAL_MODEL_DELAY_MILLIS)
        requests += request
        return if (requests.size % 2 == 1) {
            LlmResponse.Text(
                text = "",
                cost = Cost(amountMicros = 0, known = true),
                elapsedMillis = 1,
                toolCalls = listOf(LlmToolCall(id = APPROVAL_CALL_ID, name = tool, arguments = arguments)),
            )
        } else {
            LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 0, known = true), elapsedMillis = 1)
        }
    }
}

private const val APPROVAL_CALL_ID: String = "call-approval-1"
