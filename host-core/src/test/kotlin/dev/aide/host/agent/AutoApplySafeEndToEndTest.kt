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
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.Permission
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TestState
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.host.EmbeddedHost
import dev.aide.host.git.GitCliFixture
import dev.aide.host.store.DatabaseFactory
import dev.aide.tools.file.WriteFileTool
import dev.aide.tools.sandbox.RunLintTool
import dev.aide.tools.sandbox.RunTestsTool
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.io.path.createDirectories
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
 * T-1.16 сквозным путём: production-хост в `AUTO_APPLY_SAFE` применяет безопасное
 * изменение документации после зелёных проверок, а обычный исходник сохраняет
 * предложением.
 */
class AutoApplySafeEndToEndTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        scope.cancel()
    }

    @Test
    fun `AUTO_APPLY_SAFE применяет маленькую документацию после зелёных проверок`() {
        runBlocking {
            val repo = repoWithGreenChecks("aide-safe-doc-repo")
            val database = Files.createTempFile("aide-safe-doc", ".db")
            allowAll(database, WriteFileTool.TOOL_NAME, RunTestsTool.TOOL_NAME, RunLintTool.TOOL_NAME)
            val model = MultiToolCallingModel(
                listOf(
                    RunTestsTool.TOOL_NAME to "{}",
                    RunLintTool.TOOL_NAME to "{}",
                    WriteFileTool.TOOL_NAME to """{"path":"docs/readme.md","content":"# doc\n"}""",
                ),
            )
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan(
                    "Документация",
                    AutonomyMode.AUTO_APPLY_SAFE,
                    safeVolumeThresholdLines = 50,
                ).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertEquals("# doc\n", repo.resolve("docs/readme.md").toFile().readText())
            assertEquals(ToolOutcome.SUCCESS, journal(database, run, WriteFileTool.TOOL_NAME).outcome)
            val messages = gitLog(repo, "--format=%s", "ai/${run.taskId.value}")
            assertTrue(messages.any { it.startsWith("Шаг 1") }, "изменение должно быть закоммичено: $messages")
        }
    }

    @Test
    fun `AUTO_APPLY_SAFE сохраняет обычный исходник предложением`() {
        runBlocking {
            val repo = repoWithGreenChecks("aide-safe-source-repo")
            val database = Files.createTempFile("aide-safe-source", ".db")
            allowAll(database, WriteFileTool.TOOL_NAME, RunTestsTool.TOOL_NAME, RunLintTool.TOOL_NAME)
            val model = MultiToolCallingModel(
                listOf(
                    RunTestsTool.TOOL_NAME to "{}",
                    RunLintTool.TOOL_NAME to "{}",
                    WriteFileTool.TOOL_NAME to """{"path":"src/main/App.kt","content":"fun main() = Unit\n"}""",
                ),
            )
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan(
                    "Исходник",
                    AutonomyMode.AUTO_APPLY_SAFE,
                    safeVolumeThresholdLines = 50,
                ).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertFalse(repo.resolve("src/main/App.kt").toFile().exists(), "обычный исходник не должен быть записан")
            assertEquals(ToolOutcome.PROPOSED, journal(database, run, WriteFileTool.TOOL_NAME).outcome)
            DatabaseFactory.open(database).use { store ->
                val proposal = store.proposals.byRun(run.id).single()
                assertEquals("src/main/App.kt", proposal.files.single().path)
            }
        }
    }

    @Test
    fun `production write_file отклоняет неизвестный аргумент risk`() {
        runBlocking {
            val repo = repoWithGreenChecks("aide-safe-source-risk-arg-repo")
            val database = Files.createTempFile("aide-safe-source-risk-arg", ".db")
            allowAll(database, WriteFileTool.TOOL_NAME, RunTestsTool.TOOL_NAME, RunLintTool.TOOL_NAME)
            val model = MultiToolCallingModel(
                listOf(
                    RunTestsTool.TOOL_NAME to "{}",
                    RunLintTool.TOOL_NAME to "{}",
                    WriteFileTool.TOOL_NAME to
                        """{"path":"src/main/App.kt","content":"fun main() = Unit\n","risk":"SAFE"}""",
                ),
            )
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan(
                    "Исходник с аргументом risk",
                    AutonomyMode.AUTO_APPLY_SAFE,
                    safeVolumeThresholdLines = 50,
                ).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertFalse(
                repo.resolve("src/main/App.kt").toFile().exists(),
                "исходник не должен быть записан по заявке модели",
            )
            assertEquals(
                ToolOutcome.FAILURE,
                journal(database, run, WriteFileTool.TOOL_NAME).outcome,
            )
            DatabaseFactory.open(database).use { store ->
                assertTrue(
                    store.proposals.byRun(run.id).isEmpty(),
                    "неизвестный аргумент не должен порождать предложение",
                )
            }
        }
    }

    @Test
    fun `AUTO_APPLY_SAFE без зелёных проверок не применяет даже документацию`() {
        runBlocking {
            val repo = cleanRepoWithFile("aide-safe-no-checks-repo", "docs/readme.md", "# old\n")
            val database = Files.createTempFile("aide-safe-no-checks", ".db")
            allowAll(database, WriteFileTool.TOOL_NAME)
            val model = MultiToolCallingModel(
                listOf(
                    WriteFileTool.TOOL_NAME to """{"path":"docs/readme.md","content":"# doc\n"}""",
                ),
            )
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan(
                    "Документация без проверок",
                    AutonomyMode.AUTO_APPLY_SAFE,
                    safeVolumeThresholdLines = 50,
                ).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertEquals("# old\n", repo.resolve("docs/readme.md").toFile().readText())
            assertEquals(ToolOutcome.PROPOSED, journal(database, run, WriteFileTool.TOOL_NAME).outcome)
        }
    }

    @Test
    fun `AUTO_APPLY_SAFE не применяет документацию без линтера даже после зелёных тестов`() {
        runBlocking {
            val repo = repoWithGreenChecks("aide-safe-doc-no-lint-repo")
            val database = Files.createTempFile("aide-safe-doc-no-lint", ".db")
            allowAll(database, WriteFileTool.TOOL_NAME, RunTestsTool.TOOL_NAME)
            val model = MultiToolCallingModel(
                listOf(
                    RunTestsTool.TOOL_NAME to "{}",
                    WriteFileTool.TOOL_NAME to """{"path":"docs/readme.md","content":"# doc\n"}""",
                ),
            )
            val host = host(database, model)
            val run = try {
                val client = openClient(host)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan(
                    "Документация без линтера",
                    AutonomyMode.AUTO_APPLY_SAFE,
                    safeVolumeThresholdLines = 50,
                ).getOrThrow()
                finish(client)
            } finally {
                host.close()
            }

            assertFalse(repo.resolve("docs/readme.md").toFile().exists(), "файл не должен быть записан без линтера")
            assertEquals(ToolOutcome.PROPOSED, journal(database, run, WriteFileTool.TOOL_NAME).outcome)
        }
    }

    /** Репозиторий с gradlew, который пишет зелёные отчёты тестов и линтера. */
    private fun repoWithGreenChecks(prefix: String): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory(prefix))
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), repo)

        val gradlew = repo.resolve("gradlew")
        gradlew.writeText(GREEN_GRADLEW)
        assertTrue(gradlew.toFile().setExecutable(true), "бит запуска gradlew")

        GitCliFixture.run(listOf("git", "add", "gradlew"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "обёртка сборки"), repo)
        return repo
    }

    /** Чистый репозиторий с одним файлом. */
    private fun cleanRepoWithFile(prefix: String, name: String, content: String): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory(prefix))
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), repo)
        val file = repo.resolve(name)
        file.parent?.createDirectories()
        file.writeText(content)
        GitCliFixture.run(listOf("git", "add", name), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файл"), repo)
        return repo
    }

    /** Разрешает инструменты в базе до старта хоста. */
    private fun allowAll(database: Path, vararg tools: String) {
        val seed = DatabaseFactory.open(database)
        try {
            tools.forEach { seed.permissions.save(ToolPermission(it, Permission.ALLOW, Permission.ALLOW)) }
        } finally {
            seed.close()
        }
    }

    private fun host(database: Path, model: LlmClient): EmbeddedHost = EmbeddedHost.open(
        databasePath = database,
        models = object : AgentModels {
            override fun current(): Result<ConfiguredModel> = Result.success(ConfiguredModel("test/scripted", model))
            override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
        },
        planner = RunPlanner { _, _, _ ->
            listOf(PlanStep(index = 0, summary = "шаг", status = StepStatus.PENDING))
        },
    )

    private suspend fun openClient(host: EmbeddedHost): HostClient {
        val connection = KtorHostConnection(
            endpoint = host.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        val client = HostClient(connection, scope, requestIdPrefix = "safe")
        client.start()
        withTimeoutOrNull(CONNECT_TIMEOUT_MILLIS) {
            while (connection.state.value !is ConnectionState.Connected) delay(POLL_MILLIS)
        } ?: error("клиент не подключился: ${connection.state.value}")
        return client
    }

    private suspend fun finish(client: HostClient): dev.aide.domain.AgentRun {
        val finished = withTimeoutOrNull(RUN_TIMEOUT_MILLIS) {
            while (!client.session.value.runs.any { it.state == RunState.FINISHED }) delay(POLL_MILLIS)
            client.session.value.runs.first { it.state == RunState.FINISHED }
        }
        return assertNotNull(finished, "прогон обязан дойти до finished: ${client.session.value.runs}")
    }

    private fun journal(database: Path, run: dev.aide.domain.AgentRun, tool: String): dev.aide.domain.ToolCall {
        val store = DatabaseFactory.open(database)
        return try {
            store.toolCalls.forRun(run.id).single { it.tool == tool }
        } finally {
            store.close()
        }
    }

    private fun gitLog(repo: Path, format: String, ref: String): List<String> =
        GitCliFixture.run(listOf("git", "log", format, ref), repo).output.lines().filter { it.isNotBlank() }

    private companion object {

        const val POLL_MILLIS: Long = 20
        const val CONNECT_TIMEOUT_MILLIS: Long = 10_000
        const val RUN_TIMEOUT_MILLIS: Long = 15_000

        /**
         * Обёртка, пишущая зелёный JUnit XML и пустой Checkstyle XML.
         *
         * Настоящий Gradle не запускается (О-11): проверяется путь от инструментов
         * до отчёта и решения `AUTO_APPLY_SAFE`.
         */
        const val GREEN_GRADLEW: String = """#!/bin/sh
if [ "${'$'}1" = "test" ]; then
  mkdir -p build/test-results/test
  cat > build/test-results/test/TEST-dev.aide.SampleTest.xml <<'XML'
<testsuite name="dev.aide.SampleTest" tests="1" failures="0">
  <testcase name="проходит" classname="dev.aide.SampleTest" time="0.01"/>
</testsuite>
XML
  exit 0
elif [ "${'$'}1" = "detekt" ]; then
  mkdir -p build/reports/detekt
  cat > build/reports/detekt/detekt.xml <<'XML'
<?xml version="1.0" encoding="UTF-8"?>
<checkstyle version="8.0">
</checkstyle>
XML
  exit 0
fi
exit 1
"""
    }
}

/**
 * Модель, последовательно вызывающая заданные инструменты, а затем завершая шаг.
 *
 * Результат каждого вызова возвращается модели, и она переходит к следующему;
 * после последнего вызова отвечает "готово".
 */
private class MultiToolCallingModel(private val calls: List<Pair<String, String>>) : LlmClient {

    private val requests: MutableList<LlmRequest> = CopyOnWriteArrayList()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        delay(MODEL_DELAY_MILLIS)
        requests += request
        val index = requests.size - 1
        return if (index < calls.size) {
            val (tool, arguments) = calls[index]
            LlmResponse.Text(
                text = "",
                cost = Cost(amountMicros = 0, known = true),
                elapsedMillis = 1,
                toolCalls = listOf(LlmToolCall(id = "call-$index", name = tool, arguments = arguments)),
            )
        } else {
            LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 0, known = true), elapsedMillis = 1)
        }
    }

    companion object {
        private const val MODEL_DELAY_MILLIS: Long = 5
    }
}
