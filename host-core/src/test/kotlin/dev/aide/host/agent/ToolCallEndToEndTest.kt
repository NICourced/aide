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
import dev.aide.domain.Permission
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.host.EmbeddedHost
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.git.GitCliFixture
import dev.aide.tools.file.WriteFileTool
import dev.aide.tools.sandbox.RunCommandTool
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

            val recorded = journal(database, run, READ_FILE)
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

            val recorded = journal(database, run, READ_FILE)
            assertEquals(ToolOutcome.DENIED, recorded.outcome, "отказ по границе обязан остаться в журнале")
        }
    }

    @Test
    fun `агент записывает файл, снапшот остаётся в репозитории, и вызов попадает в журнал`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-write-repo", "hello.txt", CONTENT)

            val database = Files.createTempFile("aide-write", ".db")
            allow(database, WriteFileTool.TOOL_NAME)
            val model = ToolCallingModel("write_file", """{"path":"hello.txt","content":"$WRITTEN"}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Запиши файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertEquals(WRITTEN, repo.resolve("hello.txt").toFile().readText(), "агент обязан изменить файл")
            val refs = GitCliFixture.snapshotRefs(repo)
            // Две ссылки — старт прогона и изменение: задержка в модели (см. ToolCallingModel)
            // гарантирует, что они приходятся на разные миллисекунды и не совпадают именем.
            // Без записи ссылки движком здесь осталась бы одна — стартовая, — и проверка падала бы.
            assertEquals(
                2,
                run.snapshots.size,
                "прогон обязан помнить и стартовый снапшот, и точку отката изменяющего вызова",
            )
            assertTrue(
                refs.containsAll(run.snapshots.map { it.value }),
                "каждая ссылка прогона обязана существовать в репозитории: в git $refs, в прогоне ${run.snapshots}",
            )

            val recorded = journal(database, run, WRITE_FILE)
            assertEquals("write_file", recorded.tool)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome)
            assertTrue(recorded.arguments.contains("hello.txt"), recorded.arguments)
        }
    }

    @Test
    fun `изменяющий шаг даёт коммит агента в ветке задачи, и фиксация попадает в журнал`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-commit-repo", "hello.txt", CONTENT)

            val database = Files.createTempFile("aide-commit", ".db")
            allow(database, WriteFileTool.TOOL_NAME)
            val model = ToolCallingModel("write_file", """{"path":"hello.txt","content":"$WRITTEN"}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Запиши файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            // Коммит обязан быть в ветке задачи, а не в текущей ветке человека (§ 8.3).
            val branch = "ai/${run.taskId.value}"
            val authors = gitLog(repo, "--format=%an <%ae>", branch)
            val messages = gitLog(repo, "--format=%s", branch)
            assertTrue(
                "aide-agent <agent@aide.local>" in authors,
                "коммит агента обязан отличаться от коммита человека по автору: $authors",
            )
            assertTrue(
                "Шаг 1: прочитать файл" in messages,
                "в сообщении обязан быть номер шага и его описание: $messages",
            )

            val recorded = journal(database, run, COMMIT_STEP)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome, "фиксация шага обязана попасть в журнал (FR-AGENT-8)")
        }
    }

    @Test
    fun `разрешённая команда выполняется, её вывод уходит модели, а изменение фиксируется коммитом`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-command-repo", "hello.txt", CONTENT)

            val database = Files.createTempFile("aide-command", ".db")
            allow(database, RunCommandTool.TOOL_NAME)
            val model = ToolCallingModel(RunCommandTool.TOOL_NAME, COMMAND_ARGUMENTS)
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Выполни команду", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertEquals(
                COMMAND_WRITTEN,
                repo.resolve("hello.txt").toFile().readText(),
                "команда обязана изменить файл",
            )
            val messages = gitLog(repo, "--format=%s", "ai/${run.taskId.value}")
            assertTrue(
                messages.any { it.startsWith("Шаг 1") },
                "изменяющий шаг с выполненной командой обязан быть закоммичен: $messages",
            )

            val result = assertNotNull(
                model.requests[1].messages.lastOrNull { it.role == LlmRole.TOOL },
                "вывод команды обязан вернуться модели",
            )
            assertTrue(result.content.contains(COMMAND_OUTPUT), "модель обязана увидеть stdout: ${result.content}")

            val recorded = journal(database, run, RunCommandTool.TOOL_NAME)
            assertEquals(ToolOutcome.SUCCESS, recorded.outcome, recorded.result)
        }
    }

    @Test
    fun `при ASK команда не запускается, а прогон доходит до finished`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-command-ask", "hello.txt", CONTENT)

            val database = Files.createTempFile("aide-command-ask", ".db")
            val model = ToolCallingModel(RunCommandTool.TOOL_NAME, """{"command":["touch","never-created.txt"]}""")
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTask("Выполни команду", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertFalse(
                repo.resolve("never-created.txt").toFile().exists(),
                "при ASK команда не должна запускаться",
            )
            assertEquals(ToolOutcome.DENIED, journal(database, run, RunCommandTool.TOOL_NAME).outcome)
        }
    }

    /** Ветка задачи на чистом дереве: иначе правки пользователя уехали бы в отложенные (T-1.59). */
    private fun cleanRepoWithFile(prefix: String, name: String, content: String): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory(prefix))
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), repo)
        repo.resolve(name).writeText(content)
        GitCliFixture.run(listOf("git", "add", name), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файл для записи"), repo)
        return repo
    }

    /** Разрешает запись в базе до старта хоста: без этого вызов упрётся в объявленное умолчание ASK. */
    private fun allow(database: Path, tool: String) {
        val seed = DatabaseFactory.open(database)
        try {
            seed.permissions.save(ToolPermission(tool, Permission.ALLOW, Permission.ALLOW))
        } finally {
            seed.close()
        }
    }

    /** Значения поля `git log` для ветки [ref], от нового к старому. */
    private fun gitLog(repo: Path, format: String, ref: String): List<String> =
        GitCliFixture.run(listOf("git", "log", format, ref), repo).output
            .lines()
            .filter { it.isNotBlank() }

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
     * Вызов журнала по имени инструмента: база открывается заново после остановки хоста.
     *
     * Так проверяется именно запись: если бы вызов оставался в памяти, второго открытия
     * базы он бы не пережил. Имя инструмента отбирает нужную запись: изменяющий шаг
     * (T-1.11) дописывает в журнал ещё и фиксацию, и «единственная запись» её бы не
     * отличала — проверка стала бы зависеть от числа внутренних вызовов.
     */
    private fun journal(database: Path, run: AgentRun, tool: String): ToolCall {
        val store = DatabaseFactory.open(database)
        return try {
            store.toolCalls.forRun(run.id).single { it.tool == tool }
        } finally {
            store.close()
        }
    }

    private companion object {

        const val CONTENT: String = "привет из воркспейса"

        /** Содержимое, которым агент перезаписывает файл: отличается от исходного, иначе записи не видно. */
        const val WRITTEN: String = "изменено агентом"

        /** Вывод команды: по нему видно, что результат дошёл до модели, а не только до журнала. */
        const val COMMAND_OUTPUT: String = "вывод-команды"

        /** Команда печатает вывод и меняет файл: одна и та же для проверок stdout и коммита шага. */
        const val COMMAND_ARGUMENTS: String =
            """{"command":["sh","-c","echo $COMMAND_OUTPUT; echo изменено-командой > hello.txt"]}"""
        const val COMMAND_WRITTEN: String = "изменено-командой\n"
        const val READ_FILE: String = "read_file"
        const val WRITE_FILE: String = "write_file"
        const val COMMIT_STEP: String = "commit_step"
        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000
    }
}

/**
 * Пауза модели перед ответом: разводит метки стартового снапшота и снапшота изменения.
 *
 * Оба считаются от часов хоста с точностью до миллисекунды; без паузы они могли бы попасть
 * в одну, имена ссылок совпали бы, и сквозная проверка «ссылок две» стала бы неустойчивой.
 * Пяти миллисекунд хватает с запасом.
 */
private const val MODEL_DELAY_MILLIS: Long = 5

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
        delay(MODEL_DELAY_MILLIS)
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
