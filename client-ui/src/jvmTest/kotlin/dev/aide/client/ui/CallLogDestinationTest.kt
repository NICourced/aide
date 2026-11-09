package dev.aide.client.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.AppStateStore
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.HostConnection
import dev.aide.client.state.HostSession
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ToolCallScope
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
import kotlinx.datetime.Instant

/** T-1.14: обновления сессии не должны отменять выбор пользователя в журнале. */
@OptIn(ExperimentalTestApi::class)
class CallLogDestinationTest {

    @Test
    fun `новый прогон не сбрасывает выбранную задачу журнала`() {
        runComposeUiTest {
            val state = AppStateStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
            val client = HostClient(CallLogConnection(), scope)
            var session by mutableStateOf(sessionWithRuns("r-1"))
            try {
                setContent {
                    CallLogDestination(state, client, session, scope, onBack = {})
                }
                waitUntil { state.toolCallLog.log.value.loaded }
                state.toolCallLog.selectTask(OTHER_TASK)

                session = sessionWithRuns("r-1", "r-2")
                waitForIdle()

                assertEquals(ToolCallScope.Task(OTHER_TASK), state.toolCallLog.log.value.scope)
            } finally {
                scope.cancel()
            }
        }
    }

    private fun sessionWithRuns(vararg ids: String): HostSession = HostSession(
        tasks = listOf(task(TASK), task(OTHER_TASK), task(THIRD_TASK)),
        runs = ids.map { id ->
            AgentRun(
                id = RunId(id),
                taskId = if (id == "r-2") THIRD_TASK else TASK,
                state = RunState.FINISHED,
                mode = AutonomyMode.ASK_BEFORE_CHANGES,
                startedAt = Instant.fromEpochMilliseconds(1),
            )
        },
    )

    private fun task(id: TaskId): Task = Task(
        id = id,
        title = id.value,
        prompt = "Задача ${id.value}",
        branch = "ai/${id.value}",
        status = TaskStatus.ACCEPTED,
        createdAt = Instant.fromEpochMilliseconds(1),
    )

    private class CallLogConnection : HostConnection {
        private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
        override val state: StateFlow<ConnectionState> = _state
        private val _events = MutableSharedFlow<HostMessage>()
        override val events: SharedFlow<HostMessage> = _events

        override fun start() = Unit

        override suspend fun stop() = Unit

        override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage {
            val request = message as ClientMessage.ToolCalls
            return HostMessage.ToolCallPage(
                requestId = request.requestId,
                scope = request.scope,
                tool = request.tool,
                calls = emptyList(),
                nextCursor = null,
                hasMore = false,
                tools = emptyList(),
            )
        }
    }

    private companion object {
        val TASK = TaskId("t-1")
        val OTHER_TASK = TaskId("t-2")
        val THIRD_TASK = TaskId("t-3")
    }
}
