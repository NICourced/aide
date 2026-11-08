package dev.aide.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.ui.screens.RunMetricsLine
import dev.aide.client.ui.strings.RunDuration
import dev.aide.client.ui.strings.runDuration
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

/**
 * T-1.5: строка метрик показывает время, стоимость прогона и сумму по задаче.
 *
 * Проверяются оба предупреждения FR-COST-5: неизвестная цена прогона и неполный итог
 * задачи видны словами, а не нулём. Формат длительности проверяется отдельно от UI —
 * раскладка на часы, минуты и секунды не должна зависеть от Compose.
 */
@OptIn(ExperimentalTestApi::class)
class RunMetricsLineTest {

    private fun run(elapsedMillis: Long, cost: Cost): AgentRun = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.RUNNING,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        startedAt = Instant.fromEpochMilliseconds(0),
        elapsedMillis = elapsedMillis,
        cost = cost,
    )

    private fun line(run: AgentRun?, taskCost: Cost): @Composable () -> Unit = {
        RunMetricsLine(run = run, taskCost = taskCost)
    }

    @Test
    fun `время и стоимость прогона и сумма по задаче видны строкой`() = runComposeUiTest {
        setContent(
            line(
                run = run(
                    elapsedMillis = 3_723_000,
                    cost = Cost(amountMicros = 25, known = true),
                ),
                taskCost = Cost(amountMicros = 30, known = true),
            ),
        )

        onNodeWithText("Время: 1 ч 2 мин 3 с · Стоимость: 25 мк").assertIsDisplayed()
        onNodeWithText("Задача: 30 мк").assertIsDisplayed()
        onNodeWithTag("run-metrics").assertIsDisplayed()
        onNodeWithTag("task-metrics").assertIsDisplayed()
    }

    @Test
    fun `неизвестная цена прогона показывается словами, а не нулём`() = runComposeUiTest {
        setContent(
            line(
                run = run(elapsedMillis = 1_000, cost = Cost(amountMicros = 0, known = false)),
                taskCost = Cost(amountMicros = 0, known = false),
            ),
        )

        onNodeWithText("Время: 0 ч 0 мин 1 с · Стоимость: цена неизвестна").assertIsDisplayed()
        onNodeWithText("Задача: итог неполный").assertIsDisplayed()
    }

    @Test
    fun `известная цена прогона не скрывает неполный итог задачи`() = runComposeUiTest {
        // Суть FR-COST-5: одно неизвестное звено в сумме делает неполным итог задачи,
        // но не превращает в «неизвестно» цену самого прогона.
        setContent(
            line(
                run = run(elapsedMillis = 1_000, cost = Cost(amountMicros = 25, known = true)),
                taskCost = Cost(amountMicros = 25, known = false),
            ),
        )

        onNodeWithText("Время: 0 ч 0 мин 1 с · Стоимость: 25 мк").assertIsDisplayed()
        onNodeWithText("Задача: итог неполный").assertIsDisplayed()
    }

    @Test
    fun `без прогона метрик нет`() = runComposeUiTest {
        setContent(line(run = null, taskCost = Cost()))

        onNodeWithTag("run-metrics").assertDoesNotExist()
        onNodeWithTag("task-metrics").assertDoesNotExist()
    }

    @Test
    fun `длительность раскладывается на часы, минуты и секунды`() {
        assertEquals(RunDuration(hours = 0, minutes = 0, seconds = 0), runDuration(0))
        assertEquals(
            RunDuration(hours = 0, minutes = 0, seconds = 0),
            runDuration(999),
            "неполная секунда не считается",
        )
        assertEquals(RunDuration(hours = 0, minutes = 0, seconds = 1), runDuration(1_000))
        assertEquals(RunDuration(hours = 0, minutes = 1, seconds = 0), runDuration(60_000))
        assertEquals(RunDuration(hours = 1, minutes = 2, seconds = 3), runDuration(3_723_000))
        assertEquals(RunDuration(hours = 0, minutes = 0, seconds = 0), runDuration(-5_000), "минус не показывается")
    }
}
