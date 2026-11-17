package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.AppStateStore
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostConnection
import dev.aide.client.state.ScreenState
import dev.aide.client.state.settings.KeyValueStore
import dev.aide.client.state.settings.SettingsStore
import dev.aide.domain.AutonomyMode
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.domain.TaskId
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SessionId
import dev.aide.protocol.WorkspaceId
import dev.aide.protocol.requestIdOrNull
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.Clock

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

    @Test
    fun `следующая задача использует порог объёма на момент постановки`() = runComposeUiTest {
        val connection = StubConnection()
        val settings = SettingsStore(InMemoryKeyValueStore())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            setContent { App(connection = connection, settings = settings, scope = scope) }

            onNodeWithText("Настройки").performClick()
            onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
            onNodeWithTag("settings-safe-volume-threshold").performTextInput("75")
            onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()

            onNodeWithText("Агент").performClick()
            onNodeWithTag("task-input").performTextInput("первая")
            onNodeWithTag("post-task").performClick()
            waitUntil { connection.postedThresholds.size == 1 }

            onNodeWithText("Настройки").performClick()
            onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
            onNodeWithTag("settings-safe-volume-threshold").performTextInput("30")
            onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()

            onNodeWithText("Агент").performClick()
            onNodeWithTag("task-input").performTextInput("вторая")
            onNodeWithTag("post-task").performClick()
            waitUntil { connection.postedThresholds.size == 2 }

            assertEquals(listOf(75, 30), connection.postedThresholds)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `завершённый прогон даёт кнопку отката, открывающую список снапшотов с предпросмотром`() =
        runComposeUiTest {
            val connection = StubConnection().apply { openWorkspaceOnRequest = true }
            val settings = SettingsStore(InMemoryKeyValueStore()).apply {
                repositoryPath = "/tmp/test-repo"
            }
            val appState = AppStateStore()
            val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
            try {
                setContent { App(connection = connection, settings = settings, scope = scope, state = appState) }

                waitUntil(timeoutMillis = 3000) { connection.workspaceOpened.get() }
                waitForIdle()

                onNodeWithText("Агент").performClick()
                waitForIdle()
                onNodeWithTag("open-snapshots").performClick()
                waitForIdle()

                waitUntil(timeoutMillis = 3000) { connection.snapshotListRequested.get() }

                onNodeWithTag("snapshot-list").assertIsDisplayed()
                onNodeWithTag("snapshot-row-${StubConnection.SECOND_SNAPSHOT_REF}").performClick()
                waitForIdle()

                waitUntil(timeoutMillis = 3000) { connection.snapshotPreviewRequested.get() }
                waitUntil(timeoutMillis = 3000) {
                    appState.snapshots.state.value.preview is ScreenState.Loaded
                }
                waitForIdle()

                onNodeWithText("Откат создаёт обратный коммит и сохраняет историю.").assertExists()
                onNodeWithText("Откатить").assertExists()
                assertTrue(connection.workspaceOpened.get())
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
        val postedThresholds = CopyOnWriteArrayList<Int>()
        val workspaceOpened = AtomicBoolean(false)
        val snapshotListRequested = AtomicBoolean(false)
        val snapshotPreviewRequested = AtomicBoolean(false)
        var openWorkspaceOnRequest = false

        override fun start() {
            _state.value = ConnectionState.Connected(sessionId = SessionId("test"), reconnected = false)
        }

        override suspend fun stop() = Unit

        override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage = when (message) {
            is ClientMessage.AgentStatus -> HostMessage.AgentSnapshot(message.requestId, emptyList(), emptyList())
            is ClientMessage.PostTask -> {
                postedModes += message.mode
                postedThresholds += message.safeVolumeThresholdLines
                HostMessage.TaskPosted(message.requestId, TaskId("task-${postedModes.size}"))
            }
            is ClientMessage.OpenWorkspace -> if (openWorkspaceOnRequest) {
                workspaceOpened.set(true)
                HostMessage.WorkspaceOpened(message.requestId, WORKSPACE_ID)
            } else {
                failure(message)
            }
            is ClientMessage.FileTree -> HostMessage.Tree(
                message.requestId,
                FileTreePayload(
                    workspaceId = WORKSPACE_ID,
                    rootPath = "/tmp/test-repo",
                    entries = emptyList(),
                    truncated = false,
                ),
            )
            is ClientMessage.HostState -> HostMessage.State(
                message.requestId,
                HostStatePayload(
                    workspaceId = WORKSPACE_ID,
                    rootPath = "/tmp/test-repo",
                    branch = "main",
                    headCommit = "abc123",
                    uptimeMillis = 0,
                    mode = HostMode.LOCAL,
                ),
            )
            is ClientMessage.SnapshotList -> {
                snapshotListRequested.set(true)
                HostMessage.SnapshotListResponse(message.requestId, snapshots)
            }
            is ClientMessage.SnapshotPreviewRequest -> {
                snapshotPreviewRequested.set(true)
                HostMessage.SnapshotPreviewResponse(
                    message.requestId,
                    SnapshotPreview(
                        snapshot = snapshots.first { it.ref == message.ref },
                        headCommit = "abc123",
                        files = listOf(
                            SnapshotFileChange(
                                path = "file.txt",
                                changeKind = FileChangeKind.MODIFIED,
                                addedLines = 3,
                                removedLines = 1,
                            ),
                        ),
                    ),
                )
            }
            else -> failure(message)
        }

        private fun failure(message: ClientMessage): HostMessage = HostMessage.Failure(
            message.requestIdOrNull ?: RequestId("test"),
            ProtocolError.Internal("нет ответа в тесте"),
        )

        companion object {
            val WORKSPACE_ID = WorkspaceId("test-ws")
            const val FIRST_SNAPSHOT_REF = "refs/ai/snap/1000-before-agent-step"
            const val SECOND_SNAPSHOT_REF = "refs/ai/snap/2000-before-agent-step"
            val snapshots = listOf(
                Snapshot(
                    ref = SnapshotRef(FIRST_SNAPSHOT_REF),
                    label = "before-agent-step",
                    commit = "commit1",
                    trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
                    taskId = TaskId("task-1"),
                    createdAt = Clock.System.now(),
                ),
                Snapshot(
                    ref = SnapshotRef(SECOND_SNAPSHOT_REF),
                    label = "before-agent-step",
                    commit = "commit2",
                    trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
                    taskId = TaskId("task-1"),
                    createdAt = Clock.System.now(),
                ),
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
