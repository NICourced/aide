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
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolOutcome
import dev.aide.host.EmbeddedHost
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.git.GitCliFixture
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * T-1.7 сквозным путём: клиент открывает воркспейс на настоящем хосте, агент читает
 * файл, результат доходит до модели и попадает в журнал.
 *
 * Это проверка критерия задачи, а не отдельного класса: инструменты собраны в `HostApp`
 * так, как их собирает приложение, воркспейс открыт сообщением клиента, а журнал
 * читается из той же базы после остановки хоста — то есть вызов действительно записан,
 * а не «записался бы, если бы его позвали».
 *
 * Сеть и провайдер не нужны: модель подставляется скриптованной (О-11). Git нужен
 * затем, что открытие воркспейса открывает и репозиторий (T-0.12), а прогон ставится
 * в ветку задачи от его коммита (T-1.18).
 */
class ToolCallEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `агент читает файл воркспейса, и вызов попадает в журнал`() {
        runBlocking {
            val repo = repoWithFile("hello.txt", CONTENT)
            val database = Files.createTempFile("aide-tools", ".db")
            val model = ToolCallingModel("read_file", """{"path":"hello.txt"}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Прочитай файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            val second = model.requests[1]
            val result = assertNotNull(
                second.messages.lastOrNull { it.role == LlmRole.TOOL },
                "результат вызова обязан вернуться модели: ${second.messages}",
            )
            assertEquals(CALL_ID, result.toolCallId, "результат обязан ссылаться на вызов модели")
            assertTrue(result.content.contains(CONTENT), "модель обязана получить содержимое файла: ${result.content}")

            val recorded = journal(database, run)
            assertEquals("read_file", recorded.tool)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome)
            assertTrue(recorded.arguments.contains("hello.txt"), recorded.arguments)
            assertTrue(
                recorded.result?.contains(CONTENT) == true,
                "в журнале обязан быть результат: ${recorded.result}",
            )
            assertTrue(recorded.durationMillis >= 0, "длительность обязана быть измерена")
        }
    }

    @Test
    fun `файл за пределами воркспейса не читается, и отказ виден и модели, и журналу`() {
        runBlocking {
            val repo = repoWithFile("hello.txt", CONTENT)
            val database = Files.createTempFile("aide-tools-outside", ".db")
            val model = ToolCallingModel("read_file", """{"path":"../секрет.txt"}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Прочитай файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            val result = assertNotNull(model.requests[1].messages.lastOrNull { it.role == LlmRole.TOOL })
            assertTrue(result.content.contains("PATH_NOT_ALLOWED"), "отказ обязан быть виден модели: ${result.content}")

            val recorded = journal(database, run)
            assertEquals(ToolOutcome.DENIED, recorded.outcome, "отказ по границе обязан остаться в журнале")
        }
    }

    private fun repoWithFile(name: String, content: String): Path {
        // Репозиторий с коммитом: ветка задачи создаётся от HEAD (T-1.18), а в репозитории
        // без коммитов ответвлять не от чего — прогон отказался бы до первого чтения.
        val repo = GitCliFixture.createRepo(Files.createTempDirectory("aide-tools-repo"))
        repo.resolve(name).writeText(content)
        // Файл коммитится: прогон начинается с чистого дерева (T-1.59), и незакоммиченный
        // новый файл уехал бы в отложенные правки, а не достался агенту.
        GitCliFixture.run(listOf("git", "add", name), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файл для чтения"), repo)
        return repo
    }

    private fun host(database: Path, model: LlmClient): EmbeddedHost = EmbeddedHost.open(
        databasePath = database,
        models = object : AgentModels {
            override fun current(): Result<ConfiguredModel> = Result.success(ConfiguredModel("test/scripted", model))

            override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
        },
        planner = RunPlanner { _, _ ->
            listOf(PlanStep(index = 0, summary = "прочитать файл", status = StepStatus.PENDING))
        },
    )

    private suspend fun openClient(host: EmbeddedHost): HostClient {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        val client = HostClient(connection, scope, requestIdPrefix = "tools")
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

    /**
     * Вызов из журнала: база открывается заново после остановки хоста.
     *
     * Так проверяется именно запись: если бы вызов оставался в памяти, второго открытия
     * базы он бы не пережил.
     */
    private fun journal(database: Path, run: AgentRun): ToolCall {
        val store = DatabaseFactory.open(database)
        return try {
            store.toolCalls.forRun(run.id).single()
        } finally {
            store.close()
        }
    }

    private companion object {

        const val CONTENT: String = "привет из воркспейса"
        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000
    }
}

/**
 * Модель, просящая инструмент, а затем заканчивающая шаг.
 *
 * Запросы складываются в список, который читает тест из другой корутины, поэтому список
 * потокобезопасный: обычный `mutableListOf` терял бы записи при гонке (класс дефектов
 * из AGENTS.md, найденный на красном CI).
 */
private class ToolCallingModel(private val tool: String, private val arguments: String) : LlmClient {

    val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        requests += request
        return if (requests.size == 1) {
            LlmResponse.Text(
                text = "",
                cost = Cost(amountMicros = 0, known = true),
                elapsedMillis = 1,
                toolCalls = listOf(LlmToolCall(id = CALL_ID, name = tool, arguments = arguments)),
            )
        } else {
            LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 0, known = true), elapsedMillis = 1)
        }
    }
}

/** Идентификатор вызова, которым модель просит инструмент: по нему находится результат. */
private const val CALL_ID: String = "call-1"
