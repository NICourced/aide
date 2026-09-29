package dev.aide.host.server

import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostCallException
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Тест поднимает настоящий сервер на loopback и ходит в него настоящим клиентом. */
class ProtocolServerTest {

    private val treeCalls = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connections = mutableListOf<KtorHostConnection>()

    private val workspaceId = WorkspaceId("ws-test")

    private val handler = ClientMessageHandler { message ->
        when (message) {
            is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, workspaceId)

            is ClientMessage.FileTree -> {
                treeCalls.incrementAndGet()
                HostMessage.Tree(
                    requestId = message.requestId,
                    tree = FileTreePayload(
                        workspaceId = workspaceId,
                        rootPath = "/projects/aide",
                        entries = listOf(
                            FileTreeEntry(path = "src", isDirectory = true, sizeBytes = null),
                            FileTreeEntry(path = "src/auth/Login.kt", isDirectory = false, sizeBytes = 128),
                        ),
                        truncated = false,
                    ),
                )
            }

            is ClientMessage.FileContent -> HostMessage.Content(
                requestId = message.requestId,
                content = FileContentPayload(
                    workspaceId = workspaceId,
                    path = message.path,
                    text = "fun login() = Unit",
                    sizeBytes = 17,
                    truncated = false,
                ),
            )

            is ClientMessage.HostState -> HostMessage.State(
                requestId = message.requestId,
                state = HostStatePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    branch = "master",
                    headCommit = "abc1234",
                    uptimeMillis = 1,
                    mode = HostMode.LOCAL,
                ),
            )

            is ClientMessage.Hello -> HostMessage.Failure(
                requestId = RequestId("unexpected"),
                error = ProtocolError.Internal("приветствие обрабатывает сессия"),
            )
        }
    }

    private lateinit var server: ProtocolServer

    @BeforeTest
    fun setUp() {
        server = ProtocolServer(handler = handler, port = freeLoopbackPort())
        server.start()
    }

    @AfterTest
    fun tearDown() {
        runBlocking { connections.forEach { runCatching { it.stop() } } }
        connections.clear()
        server.stop()
        scope.cancel()
    }

    private fun newClient(): Pair<KtorHostConnection, HostClient> {
        val connection = KtorHostConnection(
            endpoint = server.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        connections += connection
        return connection to HostClient(connection, scope)
    }

    private suspend fun awaitConnected(connection: KtorHostConnection) {
        val state = withTimeoutOrNull(5_000) {
            while (connection.state.value !is ConnectionState.Connected) delay(20)
            connection.state.value
        }
        assertNotNull(state, "Клиент не подключился за 5 секунд, состояние: ${connection.state.value}")
    }

    @Test
    fun `клиент подключается, открывает воркспейс и получает дерево`() {
        runBlocking {
            val (connection, client) = newClient()
            client.start()
            awaitConnected(connection)

            val opened = client.openWorkspace("/projects/aide")
            assertEquals(workspaceId, opened)

            val tree = client.fileTree().getOrThrow()
            assertEquals(2, tree.entries.size)
            assertEquals("src/auth/Login.kt", tree.entries.last().path)
        }
    }

    @Test
    fun `повтор запроса с тем же идентификатором не выполняет операцию дважды`() {
        runBlocking {
            val (connection, client) = newClient()
            client.start()
            awaitConnected(connection)
            client.openWorkspace("/projects/aide")

            val requestId = RequestId("dup-1")
            val first = connection.request(ClientMessage.FileTree(requestId, workspaceId))
            val second = connection.request(ClientMessage.FileTree(requestId, workspaceId))

            assertIs<HostMessage.Tree>(first)
            assertIs<HostMessage.Tree>(second)
            assertEquals(first, second, "Повтор должен вернуть тот же ответ")
            assertEquals(1, treeCalls.get(), "Обработчик должен выполниться ровно один раз")
        }
    }

    @Test
    fun `ошибка доступа доходит до клиента типизированной`() {
        runBlocking {
            val failing = ProtocolServer(
                handler = ClientMessageHandler { message ->
                    when (message) {
                        is ClientMessage.OpenWorkspace ->
                            HostMessage.WorkspaceOpened(message.requestId, workspaceId)

                        is ClientMessage.FileContent -> HostMessage.Failure(
                            requestId = message.requestId,
                            error = ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"),
                        )

                        else -> HostMessage.Failure(
                            requestId = RequestId("unexpected"),
                            error = ProtocolError.NotImplemented("не нужен этому тесту"),
                        )
                    }
                },
                port = freeLoopbackPort(),
            )
            failing.start()
            try {
                val connection = KtorHostConnection(
                    endpoint = failing.endpoint,
                    scope = scope,
                    httpClient = HttpClient { install(WebSockets) },
                )
                connections += connection
                val client = HostClient(connection, scope)
                client.start()
                awaitConnected(connection)
                client.openWorkspace("/projects/aide")

                val error = client.fileContent("/etc/passwd").exceptionOrNull()
                assertIs<HostCallException>(error)
                assertEquals("/etc/passwd", assertIs<ProtocolError.AccessDenied>(error.error).path)
            } finally {
                failing.stop()
            }
        }
    }

    @Test
    fun `ответ неожиданного типа не роняет клиента`() {
        runBlocking {
            val noisy = ProtocolServer(handler = ClientHandlerReturningEvent(), port = freeLoopbackPort())
            noisy.start()
            try {
                val connection = KtorHostConnection(
                    endpoint = noisy.endpoint,
                    scope = scope,
                    httpClient = HttpClient { install(WebSockets) },
                )
                connections += connection
                val client = HostClient(connection, scope)
                client.start()
                awaitConnected(connection)
                client.openWorkspace("/projects/aide")

                // Хост отвечает событием вместо ответа: запрос завершается таймаутом, но соединение живо.
                repeat(3) { index ->
                    val response = connection.request(
                        ClientMessage.FileTree(RequestId("unexpected-$index"), workspaceId),
                        timeoutMillis = 300,
                    )
                    assertNull(response, "Событие без requestId не должно закрывать ожидающий запрос")
                }

                // Следующий обычный запрос по-прежнему обслуживается: клиент не сломался.
                val state = connection.request(
                    ClientMessage.HostState(RequestId("after-events"), workspaceId),
                    timeoutMillis = 2_000,
                )
                assertEquals("master", assertIs<HostMessage.State>(state).state.branch)
                assertIs<ConnectionState.Connected>(connection.state.value)
            } finally {
                noisy.stop()
            }
        }
    }

    @Test
    fun `клиент с несовместимой версией получает Incompatible, а не уходит в переподключения`() {
        runBlocking {
            val incompatible = KtorHostConnection(
                endpoint = server.endpoint,
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
                clientVersion = ProtocolVersion(major = 2, minor = 0),
                initialRetryMillis = 50,
                maxRetryMillis = 100,
            )
            connections += incompatible

            val states = mutableListOf<ConnectionState>()
            val watcher = scope.launch { incompatible.state.collect { states += it } }
            try {
                incompatible.start()
                val reached = withTimeoutOrNull(5_000) {
                    while (incompatible.state.value !is ConnectionState.Incompatible) delay(20)
                    incompatible.state.value
                }
                assertIs<ConnectionState.Incompatible>(reached)

                delay(500)
                watcher.cancelAndJoin()

                val resumedAfterIncompatible = states
                    .dropWhile { it !is ConnectionState.Incompatible }
                    .drop(1)
                    .any { it is ConnectionState.Connecting || it is ConnectionState.Reconnecting }
                assertTrue(
                    !resumedAfterIncompatible,
                    "После несовместимости клиент не должен переподключаться, наблюдались состояния: $states",
                )

                // Сервер закрыл сессию по протоколу, поэтому запрос после этого не обслуживается.
                assertNull(
                    incompatible.request(
                        ClientMessage.HostState(RequestId("after-incompatible"), workspaceId),
                        timeoutMillis = 500,
                    ),
                    "Закрытая сессия не должна отвечать на запросы",
                )
            } finally {
                watcher.cancel()
            }
        }
    }
}

/**
 * Обработчик, отвечающий событием вместо ответа на запрос: так проверяется, что
 * неожиданный тип ответа не роняет клиент и не закрывает соединение.
 */
private class ClientHandlerReturningEvent : ClientMessageHandler {
    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.OpenWorkspace ->
            HostMessage.WorkspaceOpened(message.requestId, WorkspaceId("ws-test"))

        is ClientMessage.FileTree -> HostMessage.Event(HostEvent.HostShuttingDown)

        is ClientMessage.HostState -> HostMessage.State(
            requestId = message.requestId,
            state = HostStatePayload(
                workspaceId = WorkspaceId("ws-test"),
                rootPath = "/projects/aide",
                branch = "master",
                headCommit = "abc1234",
                uptimeMillis = 1,
                mode = HostMode.LOCAL,
            ),
        )

        is ClientMessage.FileContent -> HostMessage.Failure(
            requestId = message.requestId,
            error = ProtocolError.NotImplemented("файл не нужен этому тесту"),
        )

        is ClientMessage.Hello -> HostMessage.Event(HostEvent.HostShuttingDown)
    }
}
