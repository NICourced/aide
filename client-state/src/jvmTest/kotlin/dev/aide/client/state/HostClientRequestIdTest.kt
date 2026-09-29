package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.SessionId
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.runBlocking

/**
 * Идентификаторы запросов уникальны между запусками клиента: кэш ответов живёт на хосте
 * весь его срок, поэтому новый клиентский процесс не должен повторять прежний `req-1`
 * и получать чужой ответ.
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

    private fun requestIdOf(connection: RecordingConnection): String =
        (connection.sent.single() as ClientMessage.OpenWorkspace).requestId.value
}
