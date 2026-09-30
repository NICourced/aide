package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.SessionId
import dev.aide.protocol.WorkspaceId
import kotlin.concurrent.thread
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Идентификаторы запросов уникальны между запусками клиента и внутри одного запуска:
 * кэш ответов живёт на хосте весь его срок, поэтому новый клиентский процесс не должен
 * повторять прежний `req-1` и получать чужой ответ, а два запроса одного клиента не должны
 * делить идентификатор — хост счёл бы второй из них повтором.
 */
class HostClientRequestIdTest {

    private class RecordingConnection : HostConnection {

        override val state: StateFlow<ConnectionState> =
            MutableStateFlow<ConnectionState>(ConnectionState.Connected(SessionId("s"), reconnected = false))

        override val events: SharedFlow<HostMessage> = MutableSharedFlow()

        val sent = mutableListOf<ClientMessage>()

        override fun start() = Unit

        override suspend fun stop() = Unit

        override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage {
            sent += message
            return when (message) {
                is ClientMessage.OpenWorkspace ->
                    HostMessage.WorkspaceOpened(message.requestId, WorkspaceId("ws"))

                else -> error("Неожиданный запрос: $message")
            }
        }
    }

    @Test
    fun `разные запуски клиента не повторяют идентификатор при том же номере`() = runBlocking {
        val first = RecordingConnection()
        val second = RecordingConnection()

        HostClient(first, this, requestIdPrefix = "run-a").openWorkspace("/repo")
        HostClient(second, this, requestIdPrefix = "run-b").openWorkspace("/repo")

        val firstId = (first.sent.single() as ClientMessage.OpenWorkspace).requestId
        val secondId = (second.sent.single() as ClientMessage.OpenWorkspace).requestId

        assertEquals("run-a-1", firstId.value)
        assertEquals("run-b-1", secondId.value)
        assertNotEquals(firstId, secondId, "Один и тот же порядковый номер не должен совпадать между запусками")
    }

    @Test
    fun `внутри одного запуска номера запросов уникальны`() = runBlocking {
        val connection = RecordingConnection()
        val client = HostClient(connection, this, requestIdPrefix = "run")

        client.openWorkspace("/repo")
        client.openWorkspace("/repo")

        val ids = connection.sent.map { (it as ClientMessage.OpenWorkspace).requestId.value }
        assertEquals(listOf("run-1", "run-2"), ids)
    }

    @Test
    fun `префикс по умолчанию не пуст и различает клиентов`() = runBlocking {
        val first = RecordingConnection()
        val second = RecordingConnection()

        HostClient(first, this).openWorkspace("/repo")
        HostClient(second, this).openWorkspace("/repo")

        val firstPrefix = requestIdOf(first).substringBeforeLast('-')
        val secondPrefix = requestIdOf(second).substringBeforeLast('-')

        assertTrue(firstPrefix.isNotBlank(), "Случайный префикс не должен быть пустым: $firstPrefix")
        assertNotEquals(firstPrefix, secondPrefix, "Случайные префиксы двух клиентов не должны совпадать")
    }

    /**
     * Проверка идёт на настоящих потоках: в приложении запросы уходят из нескольких корутин
     * сразу — обработчика событий, экрана, восстановления после обрыва, — и `Dispatchers.Default`
     * разводит их по ядрам. Счётчик под общим замком обязан выдержать и это.
     */
    @Test
    fun `одновременные запросы одного клиента получают разные идентификаторы`() {
        val connection = FakeHostConnection()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            val client = HostClient(connection, scope, requestIdPrefix = "test")
            client.start()
            runBlocking { client.openWorkspace(REPO_PATH) }

            val workers = (1..THREADS).map {
                thread {
                    runBlocking { repeat(REQUESTS_PER_THREAD) { client.hostState() } }
                }
            }
            workers.forEach { it.join() }

            val ids = connection.takeRequests()
                .filterIsInstance<ClientMessage.HostState>()
                .map { it.requestId }

            assertEquals(THREADS * REQUESTS_PER_THREAD, ids.size, "до хоста дошли не все запросы")
            assertEquals(ids.size, ids.toSet().size, "идентификаторы запросов обязаны быть уникальны")
        } finally {
            scope.cancel()
        }
    }

    private fun requestIdOf(connection: RecordingConnection): String =
        (connection.sent.single() as ClientMessage.OpenWorkspace).requestId.value

    private companion object {
        const val REPO_PATH = "/projects/aide"
        const val THREADS = 8
        const val REQUESTS_PER_THREAD = 5_000
    }
}
