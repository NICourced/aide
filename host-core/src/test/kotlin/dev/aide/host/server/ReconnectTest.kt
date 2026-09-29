package dev.aide.host.server

import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class ReconnectTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workspaceId = WorkspaceId("ws-1")
    private val port = freeLoopbackPort()

    /** Меняемое содержимое ответа: им проверяется, что клиент не остаётся со старым состоянием. */
    @Volatile
    private var branchName = "master"

    /** Сколько раз обработчик реально выполнил запрос файла: по нему видна идемпотентность. */
    private val fileContentCalls = AtomicInteger(0)

    private val handler = ClientMessageHandler { message ->
        when (message) {
            is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, workspaceId)

            is ClientMessage.HostState -> HostMessage.State(
                requestId = message.requestId,
                state = HostStatePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    branch = branchName,
                    headCommit = "abc1234",
                    uptimeMillis = 1,
                    mode = HostMode.LOCAL,
                ),
            )

            is ClientMessage.FileTree -> HostMessage.Tree(
                requestId = message.requestId,
                tree = FileTreePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    entries = listOf(
                        FileTreeEntry(path = "branch=$branchName.kt", isDirectory = false, sizeBytes = 1),
                    ),
                    truncated = false,
                ),
            )

            is ClientMessage.FileContent -> {
                fileContentCalls.incrementAndGet()
                HostMessage.Content(
                    requestId = message.requestId,
                    content = FileContentPayload(
                        workspaceId = workspaceId,
                        path = message.path,
                        text = "// branch=$branchName\n",
                        sizeBytes = 18,
                        truncated = false,
                    ),
                )
            }

            is ClientMessage.Hello -> HostMessage.Failure(
                requestId = RequestId("x"),
                error = ProtocolError.Internal("приветствие обрабатывает сессия"),
            )
        }
    }

    private lateinit var server: ProtocolServer
    private var connection: KtorHostConnection? = null

    @AfterTest
    fun tearDown() {
        runBlocking { connection?.let { runCatching { it.stop() } } }
        if (::server.isInitialized) server.stop()
        scope.cancel()
    }

    @Test
    fun `клиент переживает обрыв и получает актуальное состояние после реконнекта`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val active = startClient()
        val client = HostClient(active, scope)
        client.start()
        // 1. Первое подключение и рабочее состояние.
        awaitState(active) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")
        assertEquals("master", client.hostState().getOrThrow().branch)
        assertEquals("branch=master.kt", client.fileTree().getOrThrow().entries.single().path)

        val firstSession = assertIs<ConnectionState.Connected>(active.state.value).sessionId

        // 2. Хост сообщает новое состояние и уходит — это и есть обрыв с точки зрения клиента.
        branchName = "feature/token"
        server.stop()

        // 3. Клиент переходит в «переподключаюсь», не закрываясь окончательно.
        awaitState(active) { it is ConnectionState.Reconnecting }

        // 4. Хост поднимается на том же порту и обслуживает сессию заново.
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        awaitState(active) { it is ConnectionState.Connected && it.reconnected }

        val secondSession = assertIs<ConnectionState.Connected>(active.state.value).sessionId
        assertTrue(firstSession != secondSession, "Переподключение должно выдавать новую сессию")

        // 5. Главная проверка: клиент не остался со старым состоянием.
        val refreshed = withTimeoutOrNull(5_000) {
            while (client.session.value.hostState?.branch != "feature/token") delay(50)
            client.session.value.hostState
        }
        assertNotNull(refreshed, "После реконнекта состояние хоста должно обновиться автоматически")
        assertEquals("feature/token", refreshed.branch)

        val tree = client.fileTree().getOrThrow()
        assertEquals("branch=feature/token.kt", tree.entries.single().path)
    }

    @Test
    fun `после реконнекта дерево в состоянии клиента обновляется автоматически`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val active = startClient()
        val client = HostClient(active, scope)
        client.start()
        awaitState(active) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")
        client.fileTree()
        assertEquals("branch=master.kt", client.session.value.tree?.entries?.single()?.path)

        branchName = "feature/token"
        server.stop()
        awaitState(active) { it is ConnectionState.Reconnecting }
        server = ProtocolServer(handler = handler, port = port)
        server.start()
        awaitState(active) { it is ConnectionState.Connected && it.reconnected }

        // Ручной запрос дерева здесь не делается: клиент обязан обновить его сам.
        val refreshed = withTimeoutOrNull(5_000) {
            while (client.session.value.tree?.entries?.single()?.path != "branch=feature/token.kt") delay(50)
            client.session.value.tree
        }
        assertNotNull(refreshed, "После реконнекта дерево в состоянии клиента должно обновиться само")
        assertEquals("branch=feature/token.kt", refreshed.entries.single().path)
    }

    @Test
    fun `после реконнекта запросы с прежними идентификаторами обслуживаются`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val active = startClient()
        val client = HostClient(active, scope)
        client.start()
        awaitState(active) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")

        server.stop()
        awaitState(active) { it is ConnectionState.Reconnecting }
        server = ProtocolServer(handler = handler, port = port)
        server.start()
        awaitState(active) { it is ConnectionState.Connected && it.reconnected }

        val state = client.hostState()
        assertTrue(state.isSuccess, "После реконнекта обычные запросы должны работать: ${state.exceptionOrNull()}")
    }

    @Test
    fun `повтор запроса с тем же идентификатором после обрыва выполняется один раз`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val active = startClient()
        val client = HostClient(active, scope)
        client.start()
        awaitState(active) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")

        // Запрос уходит на первом соединении; клиент не знает, дошёл ли ответ.
        val requestId = RequestId("dup-across-reconnect")
        val message = ClientMessage.FileContent(requestId, workspaceId, "src/Login.kt")
        val first = active.request(message)
        assertIs<HostMessage.Content>(first)
        assertEquals(1, fileContentCalls.get(), "Первый запрос обрабатывается ровно один раз")

        // Обрыв и реконнект: сессия новая, а кэш ответов должен уцелеть.
        // Сервер — тот же экземпляр: обрыв связи не перезапускает хост.
        server.stop()
        awaitState(active) { it is ConnectionState.Reconnecting }
        server.start()
        awaitState(active) { it is ConnectionState.Connected && it.reconnected }

        val repeated = active.request(message)
        assertIs<HostMessage.Content>(repeated)
        assertEquals(first, repeated, "Повтор должен вернуть прежний ответ")
        assertEquals(1, fileContentCalls.get(), "После обрыва обработчик не должен выполниться второй раз")
    }

    private fun startClient(): KtorHostConnection = KtorHostConnection(
        endpoint = "ws://127.0.0.1:$port/ws",
        scope = scope,
        httpClient = HttpClient { install(WebSockets) },
        initialRetryMillis = 50,
        maxRetryMillis = 200,
    ).also { connection = it }

    private suspend fun awaitState(
        connection: KtorHostConnection,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionState = withTimeoutOrNull(10_000) {
        while (!predicate(connection.state.value)) delay(20)
        connection.state.value
    } ?: error("Состояние не достигнуто за 10 секунд, текущее: ${connection.state.value}")
}
