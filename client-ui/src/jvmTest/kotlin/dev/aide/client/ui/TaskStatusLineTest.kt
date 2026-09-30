package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.ui.screens.TaskStatusLine
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlinx.datetime.Instant

/**
 * T-1.1: строка задачи показывает `QUEUED` (модель ещё думает) и `FAILED` с причиной.
 *
 * Без этого отказ прогона на чистой установке остался бы невидимым: прогон до плана
 * не создаётся, и рассказать об отказе может только задача.
 */
@OptIn(ExperimentalTestApi::class)
class TaskStatusLineTest {

    private fun task(status: TaskStatus, reason: String? = null): Task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Почини сборку",
        branch = "ai/t-1",
        status = status,
        failureReason = reason,
        createdAt = Instant.fromEpochMilliseconds(0),
    )

    @Test
    fun `задача в очереди видна`() = runComposeUiTest {
        setContent { TaskStatusLine(task = task(TaskStatus.QUEUED)) }

        onNodeWithText("Задача в очереди").assertIsDisplayed()
    }

    @Test
    fun `задача failed показывает настроенную причину`() = runComposeUiTest {
        setContent { TaskStatusLine(task = task(TaskStatus.FAILED, "NOT_CONFIGURED")) }

        onNodeWithText("Задача завершилась ошибкой").assertIsDisplayed()
        onNodeWithText("Провайдер модели не настроен: добавьте его в настройках").assertIsDisplayed()
    }

    @Test
    fun `остановка пользователем объясняется, а не выглядит неизвестной причиной`() = runComposeUiTest {
        setContent { TaskStatusLine(task = task(TaskStatus.FAILED, "user_stop")) }

        onNodeWithText("Прогон остановлен пользователем").assertIsDisplayed()
    }

    @Test
    fun `прерывание перезапуском хоста объясняется`() = runComposeUiTest {
        setContent { TaskStatusLine(task = task(TaskStatus.FAILED, "host_restart")) }

        onNodeWithText("Прогон прерван перезапуском хоста").assertIsDisplayed()
    }

    @Test
    fun `неизвестный код причины даёт общий текст, а не пустоту`() = runComposeUiTest {
        setContent { TaskStatusLine(task = task(TaskStatus.FAILED, "неведомая_причина")) }

        onNodeWithText("Причина неизвестна, подробности — в журнале хоста").assertIsDisplayed()
    }
}
