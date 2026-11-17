package dev.aide.host.agent

import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.provider.AgentModels
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.domain.Cost
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Permission
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.ToolPermission
import dev.aide.host.EmbeddedHost
import dev.aide.host.git.GitCliFixture
import dev.aide.host.store.DatabaseFactory
import dev.aide.tools.file.WriteFileTool
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.onSubscription
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.6 сквозным путём: клиент получает список снапшотов, предпросмотр изменений
 * и откатывает рабочее дерево к выбранной точке.
 *
 * Модель подставлена скриптованной: она просит записать файл, что создаёт
 * before-/after-снапшоты. После завершения прогона клиент откатывается к снапшоту
 * до шага, и файл возвращается к исходному содержимому.
 */
class SnapshotRollbackEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `клиент получает список снапшотов, предпросмотр и откатывается к снапшоту`() {
        runBlocking {
            val repo = repoWithFile()
            val database = Files.createTempFile("aide-rollback", ".db")
            allowWriteFile(database)
            val host = host(database)

            try {
                val (connection, client) = openClient(host)
                val connected = CompletableDeferred<Unit>()
                scope.launch {
                    client.session.onSubscription { connected.complete(Unit) }.collect {}
                }
                connected.await()

                val workspaceId = assertNotNull(
                    client.openWorkspace(repo.toString()),
                    "воркспейс обязан открыться",
                )

                val workspaceChanged = mutableListOf<HostEvent.WorkspaceChanged>()
                scope.launch {
                    connection.events.collect { message ->
                        val event = (message as? HostMessage.Event)?.event
                        if (event is HostEvent.WorkspaceChanged) workspaceChanged += event
                    }
                }

                client.postTaskApprovingPlan("Запиши файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val run = finish(client)

                val snapshots = client.snapshots.list(workspaceId).getOrThrow()
                assertTrue(snapshots.isNotEmpty(), "после изменяющего шага снапшоты обязаны появиться")
                val beforeSnapshot = snapshots.first { it.label == "before-agent-step" }

                val preview = client.snapshots.preview(workspaceId, beforeSnapshot.ref).getOrThrow()
                assertTrue(preview.headCommit.isNotBlank(), "предпросмотр обязан содержать хеш HEAD")
                assertEquals(1, preview.files.size)
                val change = preview.files.single()
                assertEquals("a.txt", change.path)
                assertEquals(FileChangeKind.MODIFIED, change.changeKind)

                workspaceChanged.clear()
                val revertResult = client.snapshots
                    .revert(workspaceId, beforeSnapshot.ref, preview.headCommit).getOrThrow()
                assertTrue(revertResult.commit.isNotBlank(), "ответ обязан содержать хеш обратного коммита")

                // Как в UI: после успеха запрашиваем состояние хоста, чтобы обновить ветку.
                client.hostState().getOrThrow()
                assertTrue(
                    client.session.value.hostState?.branch?.isNotBlank() == true,
                    "hostState обязан обновиться после отката",
                )

                assertEquals(
                    "original",
                    repo.resolve("a.txt").readText().trim(),
                    "откат к снапшоту до шага обязан вернуть исходное содержимое файла",
                )

                // Откат рассылает ровно одно событие об изменении воркспейса.
                assertEquals(1, workspaceChanged.size, "должно быть ровно одно WorkspaceChanged")
                assertEquals(workspaceId, workspaceChanged.single().workspaceId)

                // Прогон завершился — снапшот должен остаться доступным.
                assertEquals(
                    run.id,
                    client.session.value.runs.single { it.state == RunState.FINISHED }.id,
                )
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `два изменяющих шага в полном автомате дают два чекпоинта и последовательный откат`() {
        runBlocking {
            val repo = repoWithFile()
            val database = Files.createTempFile("aide-rollback-two", ".db")
            allowWriteFile(database)
            val host = host(database, TwoWritesThenFinishModel, steps = 2)

            try {
                val (connection, client) = openClient(host)
                val connected = CompletableDeferred<Unit>()
                scope.launch {
                    client.session.onSubscription { connected.complete(Unit) }.collect {}
                }
                connected.await()

                val workspaceId = assertNotNull(
                    client.openWorkspace(repo.toString()),
                    "воркспейс обязан открыться",
                )

                client.postTaskApprovingPlan(
                    "Запиши файл дважды",
                    AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS,
                ).getOrThrow()
                val run = finish(client)

                // Стартовый снапшот + два чекпоинта перед изменяющими шагами.
                assertEquals(
                    3,
                    run.snapshots.size,
                    "прогон должен содержать стартовый снапшот и два before-agent-step",
                )

                val listed = client.snapshots.list(workspaceId).getOrThrow()
                val stepSnapshots = listed
                    .filter { it.label == "before-agent-step" && it.taskId == run.taskId }
                    .sortedBy { it.ref.value }
                    .takeLast(2)
                assertEquals(
                    2,
                    stepSnapshots.size,
                    "список должен содержать оба чекпоинта изменяющих шагов",
                )

                val first = stepSnapshots.first()
                val second = stepSnapshots.last()

                // Откат ко второму чекпоинту возвращает intermediate.
                val previewSecond = client.snapshots.preview(workspaceId, second.ref).getOrThrow()
                assertEquals(
                    "final",
                    repo.resolve("a.txt").readText().trim(),
                    "перед первым откатом файл должен быть в состоянии final",
                )
                val revertSecond = client.snapshots
                    .revert(workspaceId, second.ref, previewSecond.headCommit).getOrThrow()
                assertTrue(
                    revertSecond.commit.isNotBlank(),
                    "ответ обязан содержать полный хеш обратного коммита",
                )
                client.hostState().getOrThrow()
                assertEquals(
                    "intermediate",
                    repo.resolve("a.txt").readText().trim(),
                    "откат ко второму чекпоинту должен вернуть intermediate",
                )

                // Откат к первому чекпоинту возвращает original.
                val previewFirst = client.snapshots.preview(workspaceId, first.ref).getOrThrow()
                val revertFirst = client.snapshots
                    .revert(workspaceId, first.ref, previewFirst.headCommit).getOrThrow()
                assertTrue(
                    revertFirst.commit.isNotBlank(),
                    "ответ обязан содержать полный хеш обратного коммита",
                )
                client.hostState().getOrThrow()
                assertEquals(
                    "original",
                    repo.resolve("a.txt").readText().trim(),
                    "откат к первому чекпоинту должен вернуть original",
                )
            } finally {
                host.close()
            }
        }
    }

    /** Репозиторий с одним закоммиченным файлом. */
    private fun repoWithFile(): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory("aide-rollback-repo"))
        repo.resolve("a.txt").writeText("original\n")
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "исходный файл"), repo)
        return repo
    }

    /** Разрешает write_file без подтверждения, иначе прогон встанет на диалоге. */
    private fun allowWriteFile(database: Path) {
        val seed = DatabaseFactory.open(database)
        try {
            seed.permissions.save(ToolPermission(WriteFileTool.TOOL_NAME, Permission.ALLOW, Permission.ALLOW))
        } finally {
            seed.close()
        }
    }

    private fun host(
        database: Path,
        model: LlmClient = WriteThenFinishModel,
        steps: Int = 1,
    ): EmbeddedHost = EmbeddedHost.open(
        databasePath = database,
        models = object : AgentModels {
            override fun current(): Result<ConfiguredModel> =
                Result.success(ConfiguredModel("test/scripted", model))

            override suspend fun check(alias: String): dev.aide.domain.ModelCheckFailure? =
                dev.aide.domain.ModelCheckFailure.Unsupported
        },
        planner = RunPlanner { _, _, _ ->
            (0 until steps).map { index ->
                PlanStep(index = index, summary = "записать файл", status = StepStatus.PENDING)
            }
        },
    )

    private suspend fun openClient(host: EmbeddedHost): Pair<KtorHostConnection, HostClient> {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        val client = HostClient(connection, scope, requestIdPrefix = "rollback")
        client.start()
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            while (connection.state.value !is ConnectionState.Connected) delay(POLL_MILLIS)
        } ?: error("клиент не подключился: ${connection.state.value}")
        return connection to client
    }

    /** Ждёт завершения прогона и возвращает его. */
    private suspend fun finish(client: HostClient): AgentRun {
        val finished = withTimeoutOrNull(RUN_TIMEOUT_MILLIS) {
            while (!client.session.value.runs.any { it.state == RunState.FINISHED }) delay(POLL_MILLIS)
            client.session.value.runs.first { it.state == RunState.FINISHED }
        }
        return assertNotNull(finished, "прогон обязан дойти до finished: ${client.session.value.runs}")
    }

    private companion object {
        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000
    }
}

/**
 * Модель, которая один раз просит записать файл, а потом завершает шаг текстом.
 *
 * Движок вызовет её дважды: первый раз — инструмент write_file, второй — финальный ответ.
 */
private object WriteThenFinishModel : LlmClient {

    @Volatile
    private var calls: Int = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        return if (calls == 1) {
            writeFileToolCall("""{"path":"a.txt","content":"changed\n"}""")
        } else {
            textResponse("готово")
        }
    }
}

/**
 * Модель, которая дважды просит записать файл — intermediate и final — а потом завершает шаг.
 *
 * Движок вызовет её четыре раза: write_file, финал первого шага, write_file, финал второго шага.
 */
private object TwoWritesThenFinishModel : LlmClient {

    @Volatile
    private var calls: Int = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        return when (calls) {
            1 -> writeFileToolCall("""{"path":"a.txt","content":"intermediate\n"}""")
            2 -> textResponse("шаг 1 готов")
            3 -> writeFileToolCall("""{"path":"a.txt","content":"final\n"}""")
            else -> textResponse("готово")
        }
    }
}

private fun writeFileToolCall(arguments: String): LlmResponse.Text = LlmResponse.Text(
    text = "",
    cost = Cost(amountMicros = 0, known = true),
    elapsedMillis = 1,
    toolCalls = listOf(
        LlmToolCall(
            id = "call-${System.nanoTime()}",
            name = WriteFileTool.TOOL_NAME,
            arguments = arguments,
        ),
    ),
)

private fun textResponse(text: String): LlmResponse.Text =
    LlmResponse.Text(text = text, cost = Cost(amountMicros = 0, known = true), elapsedMillis = 1)
