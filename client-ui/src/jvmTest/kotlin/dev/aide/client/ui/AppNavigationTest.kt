package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostConnection
import dev.aide.client.state.settings.KeyValueStore
import dev.aide.client.state.settings.SettingsStore
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SessionId
import dev.aide.protocol.requestIdOrNull
import kotlin.test.Test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * T-1.3: кнопка «Логи» на экране агента действительно уводит на журнал вызовов.
 *
 * Проверяется переход, а не только сама кнопка: экран агента — не то место, где виден
 * журнал, и «кнопка есть» без перехода оставило бы пользователя без обещанного экрана.
 * Соединение — заглушка (О-11): сети и хоста в тесте нет, проверяется маршрутизация.
 */
@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {

    @Test
    fun `кнопка «Логи» открывает журнал вызовов`() = runComposeUiTest {
        val connection = StubConnection()
        val settings = SettingsStore(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            setContent { App(connection = connection, settings = settings, scope = scope) }

            // Экран агента — точка входа в журнал (кнопка живёт на нём).
            onNodeWithText("Агент").performClick()
            onNodeWithTag("open-log").performClick()

            onNodeWithText("Журнал вызовов").assertIsDisplayed()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `без нажатия журнал не показывается`() = runComposeUiTest {
        val connection = StubConnection()
        val settings = SettingsStore(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            setContent { App(connection = connection, settings = settings, scope = scope) }

            onNodeWithText("Агент").performClick()

            onNodeWithTag("open-log").assertExists()
            onNodeWithText("Журнал вызовов").assertDoesNotExist()
        } finally {
            scope.cancel()
        }
    }

    /** Соединение-заглушка: отвечает на минимум, нужный экрану агента. */
    private class StubConnection : HostConnection {

        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> = _state

        private val _events = MutableSharedFlow<HostMessage>()
        override val events: SharedFlow<HostMessage> = _events

        override fun start() {
            _state.value = ConnectionState.Connected(sessionId = SessionId("test"), reconnected = false)
        }

        override suspend fun stop() = Unit

        override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage = when (message) {
            is ClientMessage.AgentStatus -> HostMessage.AgentSnapshot(message.requestId, emptyList(), emptyList())
            else -> HostMessage.Failure(
                message.requestIdOrNull ?: RequestId("test"),
                ProtocolError.Internal("нет ответа в тесте"),
            )
        }
    }

    /** Хранилище настроек в памяти: тест не пишет в файл. */
    private class InMemoryKeyValueStore : KeyValueStore {
        private val values = mutableMapOf<String, String>()

        override fun getString(key: String): String? = values[key]
        override fun putString(key: String, value: String) {
            values[key] = value
        }

        override fun remove(key: String) {
            values.remove(key)
        }
    }
}
