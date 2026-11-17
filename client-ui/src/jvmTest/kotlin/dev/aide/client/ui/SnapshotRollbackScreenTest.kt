package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ScreenState
import dev.aide.client.state.SnapshotRollbackState
import dev.aide.client.ui.screens.SnapshotRollbackActions
import dev.aide.client.ui.screens.SnapshotRollbackBottomSheet
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.6: bottom sheet отката к снапшоту показывает список, предпросмотр и откат.
 *
 * Состояние подаётся извне: тест проверяет, что UI правильно отображает данные
 * и звёт колбэки с нужными аргументами.
 */
@OptIn(ExperimentalTestApi::class)
class SnapshotRollbackScreenTest {

    private val ref = SnapshotRef("refs/ai/snap/1758535200000-before-agent-step")
    private val snapshot = Snapshot(
        ref = ref,
        label = "before-agent-step",
        commit = "abc1234",
        trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
        createdAt = Instant.fromEpochMilliseconds(1_758_535_200_000L),
    )

    @Test
    fun `показывает заголовок и список снапшотов`() = runComposeUiTest {
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                ),
                actions = emptyActions(),
            )
        }

        onNodeWithText("Точки отката").assertIsDisplayed()
        onNodeWithText("before-agent-step").assertIsDisplayed()
    }

    @Test
    fun `выбор снапшота раскрывает предпросмотр изменений`() = runComposeUiTest {
        val preview = SnapshotPreview(
            snapshot = snapshot,
            headCommit = "head-1234",
            files = listOf(
                SnapshotFileChange(
                    path = "src/Login.kt",
                    changeKind = FileChangeKind.MODIFIED,
                    addedLines = 2,
                    removedLines = 1,
                ),
            ),
        )
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(preview),
                ),
                actions = emptyActions(),
            )
        }

        onNodeWithText("Изменения").assertIsDisplayed()
        onNodeWithText("src/Login.kt").assertIsDisplayed()
        onNodeWithTag("snapshot-revert-${ref.value}").assertIsDisplayed()
    }

    @Test
    fun `кнопка отката зовёт колбэк с ref и headCommit`() = runComposeUiTest {
        val preview = SnapshotPreview(
            snapshot = snapshot,
            headCommit = "head-5678",
            files = emptyList(),
        )
        var reverted: Pair<SnapshotRef, String>? = null
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(preview),
                ),
                actions = SnapshotRollbackActions(
                    onDismiss = {},
                    onLoadList = {},
                    onSelect = {},
                    onRevert = { r, head -> reverted = r to head },
                ),
            )
        }

        onNodeWithTag("snapshot-revert-${ref.value}").performClick()

        assertEquals(ref to "head-5678", reverted, "откат обязан передать ref и HEAD предпросмотра")
    }

    @Test
    fun `показывает пояснение про обратный коммит перед откатом`() = runComposeUiTest {
        val preview = SnapshotPreview(
            snapshot = snapshot,
            headCommit = "head-1234",
            files = emptyList(),
        )
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(preview),
                ),
                actions = emptyActions(),
            )
        }

        onNodeWithText("Откат создаёт обратный коммит и сохраняет историю.").assertIsDisplayed()
    }

    @Test
    fun `после успеха показывает хеш обратного коммита и кнопку закрытия`() = runComposeUiTest {
        val preview = SnapshotPreview(
            snapshot = snapshot,
            headCommit = "head-1234",
            files = emptyList(),
        )
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(preview),
                    completedCommit = "revert-abc-123",
                ),
                actions = emptyActions(),
            )
        }

        onNodeWithText("Создан обратный коммит: revert-abc-123").assertIsDisplayed()
        onNodeWithTag("snapshot-close").assertIsDisplayed()
    }

    @Test
    fun `кнопка закрытия зовёт onDismiss`() = runComposeUiTest {
        var dismissed = false
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(
                        SnapshotPreview(
                            snapshot = snapshot,
                            headCommit = "head-1234",
                            files = emptyList(),
                        ),
                    ),
                    completedCommit = "revert-abc-123",
                ),
                actions = SnapshotRollbackActions(
                    onDismiss = { dismissed = true },
                    onLoadList = {},
                    onSelect = {},
                    onRevert = { _, _ -> },
                ),
            )
        }

        onNodeWithTag("snapshot-close").performClick()

        assertTrue(dismissed, "кнопка закрытия обязана звать onDismiss")
    }

    @Test
    fun `пустой список показывает плейсхолдер`() = runComposeUiTest {
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(list = ScreenState.Loaded(emptyList())),
                actions = emptyActions(),
            )
        }

        onNodeWithText("Точек отката пока нет").assertIsDisplayed()
    }

    @Test
    fun `ошибка загрузки списка показывает кнопку повтора`() = runComposeUiTest {
        var retried = false
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Failed(
                        kind = ScreenState.ErrorKind.OTHER,
                        arguments = listOf("сбой"),
                    ),
                ),
                actions = SnapshotRollbackActions(
                    onDismiss = {},
                    onLoadList = { retried = true },
                    onSelect = {},
                    onRevert = { _, _ -> },
                ),
            )
        }

        onNodeWithText("Не получилось").assertIsDisplayed()
        onNodeWithText("Повторить").performClick()
        assertTrue(retried, "кнопка повтора обязана звать onLoadList")
    }

    @Test
    fun `отсутствие репозитория показывает честное состояние`() = runComposeUiTest {
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(list = ScreenState.NoRepository),
                actions = emptyActions(),
            )
        }

        onNodeWithTag("snapshot-access-denied-title").assertIsDisplayed()
        onNodeWithText("Репозиторий не выбран. Укажите путь к нему в настройках.").assertIsDisplayed()
    }

    @Test
    fun `отсутствие прав показывает честное состояние`() = runComposeUiTest {
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.NoPermission(path = "/secret", reason = "запрещено"),
                ),
                actions = emptyActions(),
            )
        }

        onNodeWithTag("snapshot-access-denied-title").assertIsDisplayed()
        onNodeWithText("Нет доступа").assertIsDisplayed()
        onNodeWithText("Доступ к «/secret» закрыт: запрещено").assertIsDisplayed()
    }

    @Test
    fun `выбор другого снапшота заблокирован во время отката`() = runComposeUiTest {
        val otherRef = SnapshotRef("refs/ai/snap/1758535300000-after-agent-step")
        val otherSnapshot = snapshot.copy(
            ref = otherRef,
            label = "after-agent-step",
            trigger = SnapshotTrigger.AFTER_AGENT_STEP,
        )
        var selected: SnapshotRef? = null
        setContent {
            SnapshotRollbackBottomSheet(
                state = SnapshotRollbackState(
                    list = ScreenState.Loaded(listOf(snapshot, otherSnapshot)),
                    selected = ref,
                    preview = ScreenState.Loaded(
                        SnapshotPreview(
                            snapshot = snapshot,
                            headCommit = "head-1234",
                            files = emptyList(),
                        ),
                    ),
                    reverting = ref,
                ),
                actions = SnapshotRollbackActions(
                    onDismiss = {},
                    onLoadList = {},
                    onSelect = { selected = it },
                    onRevert = { _, _ -> },
                ),
            )
        }

        onNodeWithTag("snapshot-row-${otherRef.value}").performClick()

        assertEquals(null, selected, "нажатие на другую строку во время отката не должно звать onSelect")
    }

    private fun emptyActions(): SnapshotRollbackActions = SnapshotRollbackActions(
        onDismiss = {},
        onLoadList = {},
        onSelect = {},
        onRevert = { _, _ -> },
    )
}
