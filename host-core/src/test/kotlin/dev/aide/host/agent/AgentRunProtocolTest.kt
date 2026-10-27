package dev.aide.host.agent

import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.AgentModels
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.decidePlan
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.Permission
import dev.aide.domain.PlanDecision
import dev.aide.domain.PlanStep
import dev.aide.domain.RunCommand
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolPermission
import dev.aide.host.EmbeddedHost
import dev.aide.host.git.GitCliFixture
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.RequestId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicInteger
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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

            val taskId = client.postTaskApprovingPlan("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
                .getOrThrow()

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
    fun `постановка, стоянка плана, решение и завершение проходят путь клиент — хост — событие`() {
        // T-1.2: до решения прогон стоит в PLANNED и ничего не делает; после подтверждения
        // идёт работа. Модель просит вызов инструмента — тогда «до решения вызовов нет»
        // проверяет именно шлюз, а не отсутствие вызовов у модели как таковое.
        runBlocking {
            val model = ToolFirstModel()
            val host = EmbeddedHost.open(
                databasePath = tempDatabase(),
                models = scripted(model),
                planner = fixedPlanner(1),
            )
            try {
                val (connection, client) = newClient(host, "plan")
                client.start()
                awaitConnected(connection)
                openRepository(client)
                client.postTask("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val planned = awaitRun(client) { it.state == RunState.PLANNED }
                assertNotNull(planned, "прогон обязан показать план и встать на стоянку: ${client.session.value.runs}")
                // Стоянка устойчива: за паузу прогон не сдвинулся и модель не была спрошена.
                delay(STANDSTILL_SETTLE_MILLIS)
                assertEquals(
                    RunState.PLANNED,
                    client.session.value.runs.firstOrNull { it.id == planned.id }?.state,
                    "до решения прогон обязан оставаться в PLANNED",
                )
                assertEquals(0, model.calls.get(), "до решения модель не спрашивают")
                val beforeDecision = client.toolLogClient.toolCalls(planned.id).getOrThrow()
                assertTrue(beforeDecision.calls.isEmpty(), "до решения вызовов инструментов быть не должно")

                client.decidePlan(planned.id, PlanDecision.Approve).getOrThrow()
                assertNotNull(
                    awaitRun(client) { it.id == planned.id && it.state == RunState.FINISHED },
                    "после решения прогон обязан завершиться: ${client.session.value.runs}",
                )
                // После решения вызов действительно состоялся — иначе «до решения пусто»
                // ничего не доказывало бы: у модели могло не быть вызовов вовсе.
                assertTrue(model.calls.get() > 0, "после решения модель обязана быть спрошена")
                val afterDecision = client.toolLogClient.toolCalls(planned.id).getOrThrow()
                assertTrue(
                    afterDecision.calls.any { it.tool == FIND_FILES },
                    "после решения вызов инструмента обязан появиться в журнале: ${afterDecision.calls}",
                )
            } finally {
                host.close()
            }
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
                client.postTaskApprovingPlan("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

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
                client.postTaskApprovingPlan("Ещё задача", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
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

    /**
     * T-1.4: стоп возвращает рабочее дерево к последнему снапшоту прогона **и** правки человека.
     *
     * Проверка сквозная, потому что критерий задачи наблюдаем только здесь: снапшот, `reset`,
     * очистка неотслеживаемого, отложенные правки — это согласие движка, портов адаптеров и git.
     * Дерево начинается грязным: правка отслеживаемого файла и новый файл человека откладываются
     * на время прогона (T-1.59), и стоп обязан вернуть дерево к снапшоту **до** их возврата.
     * Если порядок обратный, `reset --hard` снесёт уже возвращённые правки — тест это и ловит.
     */
    @Test
    fun `стоп возвращает дерево к снапшоту и оставляет правки человека`() {
        runBlocking {
            // Фикстура оставляет изменённый `src/Login.kt` и неотслеживаемый `src/New.kt`.
            val repo = GitCliFixture.createRepo(Files.createTempDirectory("aide-stop-dirty-repo"))
            // `build/` игнорируется: файл в таком каталоге создала не работа агента и терять его нельзя.
            repo.resolve(".gitignore").writeText("build/\n")
            GitCliFixture.run(listOf("git", "add", ".gitignore"), repo)
            GitCliFixture.run(listOf("git", "commit", "-m", "игнорируемое"), repo)
            // Неотслеживаемый каталог человека с игнорируемым файлом внутри — ровно тот
            // случай, где `git clean` уносил файл вместе с каталогом (T-1.4, решение 1).
            repo.resolve("local").createDirectories()
            repo.resolve("local/main.txt").writeText(HUMAN_FILE)
            repo.resolve("local/build").createDirectories()
            repo.resolve("local/build/art.bin").writeText(IGNORED_ARTEFACT)
            val database = tempDatabase()
            allowWrite(database)
            val host = EmbeddedHost.open(
                databasePath = database,
                models = scripted(StopAfterWritesModel(listOf(FILE to WRITTEN))),
                planner = fixedPlanner(1),
            )
            try {
                val (connection, client) = newClient(host, "stop-dirty")
                client.start()
                awaitConnected(connection)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan("Поправь файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                val changed = withTimeoutOrNull(STOP_WAIT_MILLIS) {
                    while (!Files.exists(repo.resolve(FILE))) delay(20)
                    true
                }
                assertEquals(true, changed, "агент обязан успеть создать файл до стопа")

                val running = awaitRun(client) { it.state == RunState.RUNNING }
                assertNotNull(running, "прогон обязан идти: ${client.session.value.runs}")
                client.controlRun(running.id, RunCommand.STOP).getOrThrow()
                assertNotNull(
                    awaitRun(client) { it.id == running.id && it.state == RunState.STOPPED },
                    "стоп — событием",
                )
            } finally {
                host.close()
            }

            assertFalse(Files.exists(repo.resolve(FILE)), "файл, созданный прогоном, убран")
            assertEquals("master", GitCliFixture.currentBranch(repo), "дерево вернулось на ветку человека")
            assertEquals(
                // `local/` git показывает целиком: в каталоге есть и неотслеживаемый файл,
                // и игнорируемый — но неотслеживаемым считается только первый.
                listOf(" M src/Login.kt", "?? local/", "?? src/New.kt"),
                GitCliFixture.porcelainLines(repo),
                "дерево совпадает со снапшотом плюс возвращённые правки человека",
            )
            assertEquals(
                "fun login() = \"token\"\n",
                Files.readString(repo.resolve("src/Login.kt")),
                "правка человека вернулась на свою ветку, а не потеряна вместе с деревом агента",
            )
            assertEquals(
                IGNORED_ARTEFACT,
                Files.readString(repo.resolve("local/build/art.bin")),
                "игнорируемое внутри неотслеживаемого каталога человека уцелело",
            )
            assertEquals(HUMAN_FILE, Files.readString(repo.resolve("local/main.txt")), "файл человека вернулся")
        }
    }

    /**
     * T-1.4: стоп возвращает рабочее дерево к последнему снапшоту прогона.
     *
     * Проверка сквозная, потому что критерий задачи наблюдаем только здесь: снапшот, `reset`,
     * очистка неотслеживаемого — это согласие движка, портов адаптеров и git. Правки человека
     * сюда не подмешиваются: репозиторий начинается чистым, и тогда видно именно возврат дерева.
     */
    @Test
    fun `стоп возвращает рабочее дерево к снапшоту прогона`() {
        runBlocking {
            val repo = cleanRepoWithFile()
            val database = tempDatabase()
            allowWrite(database)
            val host = EmbeddedHost.open(
                databasePath = database,
                models = scripted(
                    StopAfterWritesModel(listOf(FILE to WRITTEN, GENERATED to GENERATED_CONTENT)),
                ),
                planner = fixedPlanner(1),
            )
            try {
                val (connection, client) = newClient(host, "stop-restore")
                client.start()
                awaitConnected(connection)
                assertNotNull(client.openWorkspace(repo.toString()), "воркспейс обязан открыться")
                client.postTaskApprovingPlan("Поправь файл", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

                // Ждём самой правки, а не состояния: возврат дерева проверяется на том, что
                // агент успел изменить, — иначе проверка прошла бы и без восстановления.
                val changed = withTimeoutOrNull(STOP_WAIT_MILLIS) {
                    while (Files.readString(repo.resolve(FILE)) != WRITTEN || !Files.exists(repo.resolve(GENERATED))) {
                        delay(20)
                    }
                    true
                }
                assertEquals(true, changed, "агент обязан успеть изменить файл и создать новый до стопа")

                val running = awaitRun(client) { it.state == RunState.RUNNING }
                assertNotNull(running, "прогон обязан идти: ${client.session.value.runs}")
                client.controlRun(running.id, RunCommand.STOP).getOrThrow()
                assertNotNull(
                    awaitRun(client) { it.id == running.id && it.state == RunState.STOPPED },
                    "стоп — событием",
                )
            } finally {
                host.close()
            }

            assertEquals(
                CONTENT,
                Files.readString(repo.resolve(FILE)),
                "правка агента откачена к снапшоту: репозиторий оставлен в его состоянии",
            )
            assertFalse(Files.exists(repo.resolve(GENERATED)), "файл, созданный прогоном, убран")
            assertEquals(
                emptyList(),
                GitCliFixture.porcelainLines(repo),
                "после стопа рабочее дерево совпадает со снапшотом",
            )
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
            first.postTaskApprovingPlan("Почини", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()
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
            client.postTaskApprovingPlan("долгая задача", AutonomyMode.ASK_BEFORE_CHANGES).getOrThrow()

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
    fun `прогон идёт в ветке задачи, а после него дерево возвращается на базовую`() = runBlocking {
        val model = GateModel()
        val host = EmbeddedHost.open(
            databasePath = tempDatabase(),
            models = scripted(model),
            planner = fixedPlanner(1),
        )
        try {
            val (connection, client) = newClient(host, "branch")
            client.start()
            awaitConnected(connection)
            openRepository(client)

            assertEquals("master", awaitBranch(client, "master"), "состояние открытого репозитория доходит до клиента")
            val headBefore = headOfRepo()

            val taskId = client.postTaskApprovingPlan("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
                .getOrThrow()
            val taskBranch = "ai/${taskId.value}"

            // Модель ждёт разрешения, то есть прогон ещё идёт: ветка задачи видна в шапке
            // потому, что хост разослал событие о смене воркспейса после переключения (T-1.18).
            assertEquals(taskBranch, awaitBranch(client, taskBranch), "шапка обязана показать ветку задачи")
            assertEquals(taskBranch, GitCliFixture.currentBranch(repo.root), "прогон идёт в ветке задачи")
            model.release()
            assertNotNull(awaitRun(client) { it.state == RunState.FINISHED }, "прогон обязан завершиться")

            // T-1.59: дерево возвращается на ветку пользователя вместе с его правками, и шапка
            // узнаёт об этом из события — иначе она осталась бы на ветке агента.
            assertEquals("master", awaitBranch(client, "master"), "шапка видит возврат на базовую ветку")
            assertEquals("master", GitCliFixture.currentBranch(repo.root), "репозиторий вернулся на базовую ветку")
            assertEquals(headBefore, headOfRepo(), "ветка создана от HEAD: сам HEAD не сдвинулся")
            assertTrue(refOf(taskBranch).isNotEmpty(), "ветка задачи осталась в репозитории")
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
            val taskId = client.postTaskApprovingPlan("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
                .getOrThrow()
            assertNotNull(awaitRun(client) { it.state == RunState.FINISHED })
            // T-1.59: после прогона дерево вернулось на базовую ветку, а ветка задачи осталась.
            assertEquals("master", awaitBranch(client, "master"))
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
            assertEquals("master", awaitBranch(client, "master"), "перезапуск не оставляет дерево в ветке агента")
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

    private fun fixedPlanner(stepCount: Int): RunPlanner = RunPlanner { _, _, _ ->
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

    /**
     * Репозиторий с закоммиченным файлом и чистым деревом.
     *
     * Чистое дерево здесь существенно: незакоммиченные правки фикстуры уехали бы в отложенные
     * (T-1.59), и тогда проверялся бы их возврат, а не возврат дерева к снапшоту.
     */
    private fun cleanRepoWithFile(): Path {
        val repo = GitCliFixture.createRepo(Files.createTempDirectory("aide-stop-repo"))
        GitCliFixture.run(listOf("git", "add", "-A"), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), repo)
        repo.resolve(FILE).writeText(CONTENT)
        GitCliFixture.run(listOf("git", "add", FILE), repo)
        GitCliFixture.run(listOf("git", "commit", "-m", "файл для правки"), repo)
        return repo
    }

    /** Разрешает запись в базе до старта хоста: без этого вызов упрётся в объявленное умолчание ASK. */
    private fun allowWrite(database: Path) {
        val seed = DatabaseFactory.open(database)
        try {
            seed.permissions.save(ToolPermission(WRITE_FILE, Permission.ALLOW, Permission.ALLOW))
        } finally {
            seed.close()
        }
    }

    /** Модель отвечает сразу: прогон доходит до завершения. */
    private class TextModel : LlmClient {
        override suspend fun complete(request: LlmRequest): LlmResponse =
            LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }

    /**
     * Модель, записывающая заданные файлы и затем замолкающая: так проверяется возврат дерева.
     *
     * Каждый вызов просит одну запись; когда записи кончились, модель не отвечает никогда.
     * Именно на этом вызове прогон и застаёт стоп: работа агента уже сделана, а шаг ещё
     * не завершён — ровно тот случай, ради которого нужна точка отката.
     */
    private class StopAfterWritesModel(private val writes: List<Pair<String, String>>) : LlmClient {

        private val calls = AtomicInteger(0)

        override suspend fun complete(request: LlmRequest): LlmResponse {
            val index = calls.incrementAndGet()
            if (index > writes.size) return awaitCancellation()
            val (path, content) = writes[index - 1]
            return LlmResponse.Text(
                text = "",
                cost = Cost(),
                elapsedMillis = 1,
                toolCalls = listOf(
                    LlmToolCall(
                        id = "stop-$index",
                        name = WRITE_FILE,
                        arguments = """{"path":"$path","content":"$content"}""",
                    ),
                ),
            )
        }
    }

    /**
     * Модель, просящая вызов инструмента, а затем завершающая шаг.
     *
     * Счётчик вызовов открыт тесту: по нему проверяется, что до решения модель не спрашивали.
     */
    private class ToolFirstModel : LlmClient {

        /** Сколько раз модель спросили; до решения обязано оставаться нулём. */
        val calls = AtomicInteger(0)

        override suspend fun complete(request: LlmRequest): LlmResponse {
            val index = calls.incrementAndGet()
            return if (index == 1) {
                val call = LlmToolCall(id = "plan-call", name = FIND_FILES, arguments = """{"mask":"*"}""")
                LlmResponse.Text(text = "", cost = Cost(), elapsedMillis = 1, toolCalls = listOf(call))
            } else {
                LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
            }
        }
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

/** Имя инструмента, которым модель просит работу в проверке стоянки плана (T-1.2). */
private const val FIND_FILES: String = "find_files"

/** Инструмент правки файла: им модель меняет файл и создаёт новый в проверке стопа (T-1.4). */
private const val WRITE_FILE: String = "write_file"

/** Файл, который агент правит: снапшот прогона хранит его исходное содержимое (T-1.4). */
private const val FILE: String = "hello.txt"

/** Содержимое файла в снапшоте: к нему дерево обязано вернуться после стопа. */
private const val CONTENT: String = "исходное содержимое\n"

/** Содержимое, которым агент правит файл: отличается от исходного, иначе правки не видно. */
private const val WRITTEN: String = "изменено агентом\n"

/** Новый файл, созданный прогоном: после стопа его быть не должно. */
private const val GENERATED: String = "src/Generated.kt"

/** Содержимое нового файла прогона: по нему видно, что запись действительно состоялась. */
private const val GENERATED_CONTENT: String = "class Generated"

/** Неотслеживаемый файл человека: на время прогона он откладывается и обязан вернуться. */
private const val HUMAN_FILE: String = "правка человека\n"

/** Игнорируемый файл человека внутри неотслеживаемого каталога: его возврат дерева не трогает. */
private const val IGNORED_ARTEFACT: String = "артефакт сборки\n"

/** Сколько ждать правки агента до стопа: работа идёт через ws, базу и git. */
private const val STOP_WAIT_MILLIS: Long = 10_000

/** Пауза, за которую стоянка обязана не сдвинуться: прогон не должен начать работу сам. */
private const val STANDSTILL_SETTLE_MILLIS: Long = 200
