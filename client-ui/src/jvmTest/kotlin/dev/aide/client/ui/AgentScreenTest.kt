package dev.aide.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.HostSession
import dev.aide.client.ui.screens.AgentScreen
import dev.aide.client.ui.screens.TestStatusLine
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.TestFailure
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.1: ошибка обращения к агенту видна, а при успехе её текста нет.
 *
 * Ошибка объединяет неудачную постановку задачи и неудачный снимок: без показа
 * пользователь на чистой установке не понял бы, почему ничего не происходит.
 */
@OptIn(ExperimentalTestApi::class)
class AgentScreenTest {

    private fun screen(requestFailed: Boolean): @Composable () -> Unit = {
        AgentScreen(
            session = HostSession(runs = emptyList(), tasks = emptyList()),
            requestFailed = requestFailed,
            onPostTask = {},
        )
    }

    @Test
    fun `ошибка обращения к хосту видна`() = runComposeUiTest {
        setContent(screen(requestFailed = true))

        onNodeWithText("Не удалось связаться с хостом: проверьте соединение").assertIsDisplayed()
    }

    @Test
    fun `без ошибки текст ошибки не показывается`() = runComposeUiTest {
        setContent(screen(requestFailed = false))

        onNodeWithText("Не удалось связаться с хостом: проверьте соединение").assertDoesNotExist()
    }

    @Test
    fun `кнопка «Логи» зовёт открытие журнала`() = runComposeUiTest {
        var opened = false
        setContent {
            AgentScreen(
                session = HostSession(),
                requestFailed = false,
                onPostTask = {},
                onOpenLog = { opened = true },
            )
        }

        onNodeWithTag("open-log").performClick()

        assertTrue(opened, "кнопка «Логи» обязана открывать журнал вызовов")
    }

    @Test
    fun `зелёные тесты показаны строкой статуса`() = runComposeUiTest {
        setContent { TestStatusLine(report = TestReport(state = TestState.GREEN)) }

        onNodeWithText("Тесты прошли").assertIsDisplayed()
    }

    @Test
    fun `красные тесты показаны с числом упавших`() = runComposeUiTest {
        setContent {
            TestStatusLine(
                report = TestReport(
                    state = TestState.RED,
                    failures = listOf(TestFailure("a"), TestFailure("b")),
                ),
            )
        }

        onNodeWithText("Упавших тестов: 2").assertIsDisplayed()
    }

    @Test
    fun `инфраструктурная ошибка тестов видна отдельным текстом`() = runComposeUiTest {
        setContent { TestStatusLine(report = TestReport(state = TestState.INFRA_ERROR)) }

        onNodeWithText("Не удалось прогнать тесты").assertIsDisplayed()
    }

    @Test
    fun `без отчёта строка говорит, что тесты не запускались`() = runComposeUiTest {
        setContent { TestStatusLine(report = null) }

        onNodeWithText("Тесты не запускались").assertIsDisplayed()
    }

    @Test
    fun `обрезка списка падений видна в строке статуса`() = runComposeUiTest {
        setContent {
            TestStatusLine(
                report = TestReport(
                    state = TestState.RED,
                    failures = listOf(TestFailure("a"), TestFailure("b")),
                    failuresTruncated = true,
                ),
            )
        }

        onNodeWithText("Упавших тестов: 2, показаны не все").assertIsDisplayed()
    }

    @Test
    fun `пропущенные тесты показаны отдельным текстом`() = runComposeUiTest {
        setContent { TestStatusLine(report = TestReport(state = TestState.SKIPPED)) }

        onNodeWithText("Тестов в проекте нет").assertIsDisplayed()
    }

    @Test
    fun `таймаут тестов показан отдельным текстом`() = runComposeUiTest {
        setContent { TestStatusLine(report = TestReport(state = TestState.TIMEOUT)) }

        onNodeWithText("Тесты не уложились в лимит времени").assertIsDisplayed()
    }

    @Test
    fun `экран агента показывает строку статуса тестов прогона`() = runComposeUiTest {
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(runWithTests(TestReport(state = TestState.GREEN)))),
                requestFailed = false,
                onPostTask = {},
            )
        }

        onNodeWithText("Тесты прошли").assertIsDisplayed()
    }

    /** Прогон с отчётом: так проверяется, что строка статуса тестов стоит и на экране агента. */
    private fun runWithTests(report: TestReport): AgentRun = AgentRun(
        id = RunId("run-1"),
        taskId = TaskId("task-1"),
        state = RunState.RUNNING,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        testReport = report,
        startedAt = Instant.fromEpochMilliseconds(1),
    )
}
