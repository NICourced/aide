package dev.aide.host

import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.host.git.GitCliFixture
import dev.aide.host.workspace.TempRepoFixture
import dev.aide.protocol.HostMode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.net.ServerSocket
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

/**
 * T-0.13: хост, поднятый в том же процессе, — это тот же [dev.aide.host.server.ProtocolServer],
 * что и удалённый, а клиент отличается от него только адресом.
 *
 * `TempRepoFixture` даёт файлы, `GitCliFixture` — историю: `StageZeroHandler` открывает
 * git-репозиторий, и без `.git` открытие воркспейса вернуло бы `NotAGitRepository`.
 */
class EmbeddedHostTest {

    private val fixture = TempRepoFixture().also { GitCliFixture.createRepo(it.root) }
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun `хост поднимается, объявляет локальный режим и отдаёт состояние репозитория`() {
        runBlocking {
            val host = EmbeddedHost.open()
            try {
                assertTrue(
                    host.endpoint.startsWith("ws://127.0.0.1:"),
                    "Локальный хост слушает loopback: ${host.endpoint}",
                )

                val connection = newConnection(host.endpoint)
                val client = HostClient(connection, scope)
                client.start()
                awaitConnected(connection)

                assertNotNull(client.openWorkspace(fixture.root.toString()))

                val state = client.hostState().getOrThrow()
                assertEquals(HostMode.LOCAL, state.mode)
                assertEquals("master", state.branch)
                assertEquals(fixture.root.toRealPath().toString(), state.rootPath)

                val tree = client.fileTree().getOrThrow()
                assertTrue(tree.entries.any { it.path == "src/auth/Login.kt" })
            } finally {
                host.close()
            }
        }
    }

    @Test
    fun `остановка хоста освобождает порт и не роняет клиент`() {
        runBlocking {
            val host = EmbeddedHost.open()
            val port = host.port
            val connection = KtorHostConnection(
                endpoint = host.endpoint,
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
                initialRetryMillis = 50,
                maxRetryMillis = 100,
            )
            val client = HostClient(connection, scope)
            client.start()
            awaitConnected(connection)

            host.close()

            // Порт освобождён: его можно занять снова.
            ServerSocket(port).use { socket -> assertTrue(socket.isBound) }

            // Клиент замечает обрыв, но не закрывается окончательно — он будет переподключаться.
            val state = withTimeoutOrNull(5_000) {
                while (connection.state.value !is ConnectionState.Reconnecting) delay(20)
                connection.state.value
            }
            assertIs<ConnectionState.Reconnecting>(
                state,
                "Состояние после остановки хоста: ${connection.state.value}",
            )
            connection.stopSafely()
        }
    }

    @Test
    fun `клиент работает с хостом по указанному адресу и не знает, локальный он или нет`() {
        runBlocking {
            val host = EmbeddedHost.open()
            try {
                // Тот же клиентский код, но адрес — единственное, что отличает случай.
                val connection = newConnection("ws://127.0.0.1:${host.port}/ws")
                val client = HostClient(connection, scope)
                client.start()
                awaitConnected(connection)

                assertNotNull(client.openWorkspace(fixture.root.toString()))
                assertEquals("master", client.hostState().getOrThrow().branch)
                assertNotNull(client.session.value.workspaceId, "Клиент должен знать открытый воркспейс")
            } finally {
                host.close()
            }
        }
    }

    private fun newConnection(endpoint: String): KtorHostConnection = KtorHostConnection(
        endpoint = endpoint,
        scope = scope,
        httpClient = HttpClient { install(WebSockets) },
    )

    private suspend fun awaitConnected(connection: KtorHostConnection) {
        val state = withTimeoutOrNull(10_000) {
            while (connection.state.value !is ConnectionState.Connected) delay(20)
            connection.state.value
        }
        assertIs<ConnectionState.Connected>(state, "Клиент не подключился: ${connection.state.value}")
    }

    private suspend fun KtorHostConnection.stopSafely() {
        runCatching { stop() }
    }
}
