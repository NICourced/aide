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
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.ToolCall
import dev.aide.host.EmbeddedHost
import dev.aide.protocol.ToolCallScope
import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.host.git.GitCliFixture
import dev.aide.host.store.DatabaseFactory
import dev.aide.tools.file.ReadFileTool
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
 * T-1.3 сквозным путём: записи журнала доходят до клиента живым событием и страницей.
 *
 * Это проверка критерия задачи, а не отдельного класса: настоящий хост, прогон с несколькими
 * вызовами, клиент получает событие и запрашивает страницу, а после остановки хоста страница
 * читается из базы. Модель подставлена скриптованной (О-11), сеть провайдера не нужна.
 */
class ToolCallLogEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `вызовы прогона приходят клиенту событием и страницей и читаются из базы после перезапуска`() {
        runBlocking {
            val repo = repoWithFiles()
            val database = Files.createTempFile("aide-log", ".db")
            val host = host(database)
            val run = try {
                val client = openClient(host)
                val events = CopyOnWriteArrayList<ToolCall>()
                val subscribed = CompletableDeferred<Unit>()
                scope.launch {
                    client.toolCallEvents.onSubscription { subscribed.complete(Unit) }.collect { events += it }
                }
                subscribed.await()

                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan("Прочитай файлы", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val finished = finish(client)

                assertTrue(
                    awaitEventCount(events) == true,
                    "клиент обязан получить событие о каждом вызове, а получил: ${events.map { it.tool }}",
                )

                // Страница отдаёт те же вызовы: подключившийся позже клиент узнаёт их запросом.
                val page = client.toolLogClient.toolCalls(ToolCallScope.Run(finished.id)).getOrThrow()
                assertEquals(
                    setOf("a.txt", "b.txt"),
                    page.calls.map { pathOf(it.argumentsPreview) }.toSet(),
                    "страница обязана отдать оба вызова: ${page.calls.map { it.argumentsPreview }}",
                )
                assertTrue(page.calls.all { it.tool == READ_FILE })

                // Полное содержимое отдаётся отдельным запросом: страница несёт только превью,
                // и раскрытие вызова обязано получить запись целиком (решение 4).
                val detail = client.toolLogClient.toolCallDetail(page.calls.first().id).getOrThrow()
                assertEquals(READ_FILE, detail.tool)
                assertTrue(
                    detail.arguments.contains("path"),
                    "полная запись обязана нести аргументы: ${detail.arguments}",
                )
                finished
            } finally {
                host.close()
            }

            // Перезапуск: страница читается из базы после остановки хоста.
            val store = DatabaseFactory.open(database)
            try {
                val page = store.toolCalls.page(ToolCallScope.Run(run.id), tool = null, before = null, limit = 20)
                assertEquals(
                    2,
                    page.calls.size,
                    "журнал обязан пережить перезапуск хоста: ${page.calls.map { it.tool }}",
                )
                assertEquals(
                    setOf("a.txt", "b.txt"),
                    page.calls.map { pathOf(it.arguments) }.toSet(),
                    "после перезапуска обязаны читаться оба вызова",
                )
            } finally {
                store.close()
            }
        }
    }

    /**
     * T-1.14 сквозным путём: фильтр по задаче видит вызовы прогона,
     * фильтр по инструменту оставляет только нужный инструмент,
     * и выдача сохраняется после перезапуска хоста.
     */
    @Test
    fun `фильтр по задаче и инструменту работает в живом прогоне и после перезапуска`() {
        runBlocking {
            val repo = repoWithFiles()
            val database = Files.createTempFile("aide-log-filter", ".db")
            allow(database, WriteFileTool.TOOL_NAME)
            val host = hostWithTwoStepModel(database)
            val run = try {
                val client = openClient(host)
                val events = CopyOnWriteArrayList<ToolCall>()
                val subscribed = CompletableDeferred<Unit>()
                scope.launch {
                    client.toolCallEvents.onSubscription { subscribed.complete(Unit) }.collect { events += it }
                }
                subscribed.await()

                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan("Запиши и прочитай", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                val finished = finish(client)

                assertTrue(
                    awaitEventTools(events) == true,
                    "клиент обязан получить события о записи и чтении: ${events.map { it.tool }}",
                )

                val taskPage = client.toolLogClient.toolCalls(ToolCallScope.Task(finished.taskId)).getOrThrow()
                assertEquals(
                    setOf(WriteFileTool.TOOL_NAME, ReadFileTool.TOOL_NAME),
                    taskPage.calls.filter { it.tool == WriteFileTool.TOOL_NAME || it.tool == ReadFileTool.TOOL_NAME }
                        .map { it.tool }.toSet(),
                    "страница задачи обязана содержать оба инструмента: ${taskPage.calls.map { it.tool }}",
                )

                val readPage = client.toolLogClient.toolCalls(
                    ToolCallScope.Task(finished.taskId),
                    tool = ReadFileTool.TOOL_NAME,
                ).getOrThrow()
                assertEquals(
                    listOf(ReadFileTool.TOOL_NAME),
                    readPage.calls.map { it.tool }.distinct(),
                    "фильтр по инструменту обязан оставить только чтение: ${readPage.calls.map { it.tool }}",
                )
                assertEquals(1, readPage.calls.size, "ровно один вызов read_file")

                finished
            } finally {
                host.close()
            }

            val store = DatabaseFactory.open(database)
            try {
                val taskPage = store.toolCalls.page(
                    ToolCallScope.Task(run.taskId),
                    tool = null,
                    before = null,
                    limit = 20,
                )
                assertTrue(
                    taskPage.calls.any { it.tool == WriteFileTool.TOOL_NAME },
                    "после перезапуска в журнале задачи должна остаться запись write_file",
                )
                assertTrue(
                    taskPage.calls.any { it.tool == ReadFileTool.TOOL_NAME },
                    "после перезапуска в журнале задачи должна остаться запись read_file",
                )

                val readPage = store.toolCalls.page(
                    ToolCallScope.Task(run.taskId),
                    tool = ReadFileTool.TOOL_NAME,
                    before = null,
                    limit = 20,
                )
                assertEquals(1, readPage.calls.size)
                assertEquals(ReadFileTool.TOOL_NAME, readPage.calls.single().tool)
            } finally {
                store.close()
            }
        }
    }

    /** Ждёт события о двух вызовах; false означает, что событий не дождались. */
    private suspend fun awaitEventCount(events: List<ToolCall>): Boolean? = withTimeoutOrNull(EVENT_TIMEOUT_MILLIS) {
        while (events.count { it.tool == READ_FILE } < 2) delay(POLL_MILLIS)
        true
    }

    /** Ждёт события о записи и о чтении; false означает, что событий не дождались. */
    private suspend fun awaitEventTools(events: List<ToolCall>): Boolean? = withTimeoutOrNull(EVENT_TIMEOUT_MILLIS) {
        while (
            !events.any { it.tool == WriteFileTool.TOOL_NAME } ||
            !events.any { it.tool == ReadFileTool.TOOL_NAME }
        ) delay(POLL_MILLIS)
        true
    }

    /** Путь из превью аргументов: страница несёт превью, а не разобранный JSON. */
    private fun pathOf(argumentsPreview: String): String =
        argumentsPreview.substringAfter("path\":\"").substringBefore("\"")

    /** Репозиторий с двумя закоммиченными файлами: ветка задачи создаётся от HEAD (T-1.18). */
    private fun repoWithFiles(): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory("aide-log-repo"))
        repo.resolve("a.txt").writeText("файл A")
        repo.resolve("b.txt").writeText("файл B")
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файлы для чтения"), repo)
        return repo
    }

    private fun host(database: Path): EmbeddedHost = EmbeddedHost.open(
        databasePath = database,
        models = object : AgentModels {
            override fun current(): Result<ConfiguredModel> =
                Result.success(ConfiguredModel("test/scripted", TwoCallModel))

            override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
        },
        planner = RunPlanner { _, _, _ ->
            listOf(PlanStep(index = 0, summary = "прочитать файлы", status = StepStatus.PENDING))
        },
    )

    private suspend fun openClient(host: EmbeddedHost): HostClient {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        val client = HostClient(connection, scope, requestIdPrefix = "log")
        client.start()
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            while (connection.state.value !is ConnectionState.Connected) delay(POLL_MILLIS)
        } ?: error("клиент не подключился: ${connection.state.value}")
        return client
    }

    /** Ждёт завершения прогона и возвращает его: журнал читается по идентификатору прогона. */
    private suspend fun finish(client: HostClient): AgentRun {
        val finished = withTimeoutOrNull(RUN_TIMEOUT_MILLIS) {
            while (!client.session.value.runs.any { it.state == RunState.FINISHED }) delay(POLL_MILLIS)
            client.session.value.runs.first { it.state == RunState.FINISHED }
        }
        return assertNotNull(finished, "прогон обязан дойти до finished: ${client.session.value.runs}")
    }

    private companion object {
        const val READ_FILE: String = "read_file"
        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000
        const val EVENT_TIMEOUT_MILLIS: Long = 10_000
    }
}

/**
 * Модель, просящая два чтения одним ответом, а затем завершающая шаг.
 *
 * Два вызова в одном ответе дают прогону больше одного вызова — так проверяется, что
 * страница и события несут их оба, а не только последний.
 */
private object TwoCallModel : LlmClient {

    @Volatile
    private var calls: Int = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        return if (calls == 1) {
            LlmResponse.Text(
                text = "",
                cost = Cost(amountMicros = 0, known = true),
                elapsedMillis = 1,
                toolCalls = listOf(
                    LlmToolCall(id = "call-1", name = READ_FILE_NAME, arguments = """{"path":"a.txt"}"""),
                    LlmToolCall(id = "call-2", name = READ_FILE_NAME, arguments = """{"path":"b.txt"}"""),
                ),
            )
        } else {
            LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 0, known = true), elapsedMillis = 1)
        }
    }

    private const val READ_FILE_NAME: String = "read_file"
}

/** Хост со скриптованной моделью, которая пишет файл на первом шаге и читает на втором. */
private fun hostWithTwoStepModel(database: Path): EmbeddedHost = EmbeddedHost.open(
    databasePath = database,
    models = object : AgentModels {
        override fun current(): Result<ConfiguredModel> =
            Result.success(ConfiguredModel("test/scripted", TwoToolModel))

        override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
    },
    planner = RunPlanner { _, _, _ ->
        listOf(
            PlanStep(index = 0, summary = "записать файл", status = StepStatus.PENDING),
            PlanStep(index = 1, summary = "прочитать файл", status = StepStatus.PENDING),
        )
    },
)

/** Разрешает инструмент в базе до старта хоста: без этого write_file упрётся в ASK. */
private fun allow(database: Path, tool: String) {
    val seed = DatabaseFactory.open(database)
    try {
        seed.permissions.save(ToolPermission(tool, Permission.ALLOW, Permission.ALLOW))
    } finally {
        seed.close()
    }
}

/**
 * Модель, просящая `write_file` на первом вызове, `read_file` на третьем
 * и завершающая шаг финальным текстом на чётных вызовах.
 */
private object TwoToolModel : LlmClient {

    @Volatile
    private var calls: Int = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        return when (calls) {
            1 -> toolResponse(WriteFileTool.TOOL_NAME, """{"path":"c.txt","content":"записано"}""")
            3 -> toolResponse(ReadFileTool.TOOL_NAME, """{"path":"a.txt"}""")
            else -> LlmResponse.Text(
                text = "готово",
                cost = Cost(amountMicros = 0, known = true),
                elapsedMillis = 1,
            )
        }
    }

    private fun toolResponse(tool: String, arguments: String): LlmResponse.Text = LlmResponse.Text(
        text = "",
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 1,
        toolCalls = listOf(LlmToolCall(id = "call-$tool", name = tool, arguments = arguments)),
    )
}
