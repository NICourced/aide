package dev.aide.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.ui.screens.RunStateLine
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import kotlin.test.Test
import kotlinx.datetime.Instant

/**
 * T-1.1: строка состояния прогона показывает каждое значение `RunState`.
 *
 * Кнопки паузы и стопа — отдельная задача (T-1.4), но если строка не знает `PAUSED`
 * и `STOPPED`, требование критерия «каждое состояние видно в UI» не выполнено:
 * состояния приходят событием, и экран обязан их показать.
 */
@OptIn(ExperimentalTestApi::class)
class RunStateLineTest {

    private fun run(state: RunState): AgentRun = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = state,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        startedAt = Instant.fromEpochMilliseconds(0),
    )

    private fun allStates(): @Composable () -> Unit = {
        Column {
            RunStateLine(run = null)
            RunState.entries.forEach { state -> RunStateLine(run = run(state)) }
        }
    }

    @Test
    fun `строка состояния показывает все значения RunState`() = runComposeUiTest {
        setContent(allStates())

        // Строка на каждое состояние: часть из них ниже видимой области, поэтому
        // проверяется наличие в композиции, а видимость — одиночным случаем ниже.
        onNodeWithText("Прогонов пока нет").assertExists()
        onNodeWithText("План готов").assertExists()
        onNodeWithText("Выполняется").assertExists()
        onNodeWithText("Пауза").assertExists()
        onNodeWithText("Завершён").assertExists()
        onNodeWithText("Ошибка прогона").assertExists()
        onNodeWithText("Остановлен").assertExists()
        onNodeWithText("Прерван перезапуском хоста").assertExists()
    }

    @Test
    fun `заданное состояние показывается, когда прогон есть`() = runComposeUiTest {
        setContent { RunStateLine(run = run(RunState.PAUSED)) }

        onNodeWithText("Пауза").assertIsDisplayed()
    }
}
