package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostConnection
import dev.aide.client.state.settings.KeyValueStore
import dev.aide.client.state.settings.SettingsStore
import dev.aide.domain.AutonomyMode
import dev.aide.domain.TaskId
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SessionId
import dev.aide.protocol.requestIdOrNull
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/** Навигация и режим постановки задачи на уровне всего App. */
@OptIn(ExperimentalTestApi::class)
class AppNavigationTest {

    @Test
    fun `кнопка «Логи» открывает журнал вызовов`() = runComposeUiTest {
        val connection = StubConnection()
        val settings = SettingsStore(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            setContent { App(connection = connection, settings = settings, scope = scope) }

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

    @Test
    fun `следующая задача использует режим настройки на момент постановки`() = runComposeUiTest {
        val connection = StubConnection()
        val settings = SettingsStore(InMemoryKeyValueStore()).apply {
            autonomyMode = AutonomyMode.SUGGEST_ONLY
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            setContent { App(connection = connection, settings = settings, scope = scope) }
            onNodeWithText("Агент").performClick()

            onNodeWithTag("task-input").performTextInput("первая")
            onNodeWithTag("post-task").performClick()
            waitUntil { connection.postedModes.size == 1 }
            settings.autonomyMode = AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS
            onNodeWithTag("task-input").performTextInput("вторая")
            onNodeWithTag("post-task").performClick()
            waitUntil { connection.postedModes.size == 2 }

            assertEquals(
                listOf(AutonomyMode.SUGGEST_ONLY, AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS),
                connection.postedModes,
            )
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

        val postedModes = CopyOnWriteArrayList<AutonomyMode>()

        override fun start() {
            _state.value = ConnectionState.Connected(sessionId = SessionId("test"), reconnected = false)
        }

        override suspend fun stop() = Unit

        override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage = when (message) {
            is ClientMessage.AgentStatus -> HostMessage.AgentSnapshot(message.requestId, emptyList(), emptyList())
            is ClientMessage.PostTask -> {
                postedModes += message.mode
                HostMessage.TaskPosted(message.requestId, TaskId("task-${postedModes.size}"))
            }
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
