package dev.aide.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.HostSession
import dev.aide.client.ui.screens.AgentActions
import dev.aide.client.ui.screens.AgentScreen
import dev.aide.client.ui.screens.TestStatusLine
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.PlanDecision
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskId
import dev.aide.domain.TestFailure
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import kotlin.test.Test
import kotlin.test.assertEquals
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
            actions = AgentActions(postTask = {}),
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
                actions = AgentActions(postTask = {}, openLog = { opened = true }),
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
                actions = AgentActions(postTask = {}),
            )
        }

        onNodeWithText("Тесты прошли").assertIsDisplayed()
    }

    @Test
    fun `план рисуется списком шагов со статусами`() = runComposeUiTest {
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(planRun(RunState.PLANNED))),
                requestFailed = false,
                actions = AgentActions(postTask = {}),
            )
        }

        onNodeWithText("План до начала работы").assertIsDisplayed()
        onNodeWithText("1. Прочитать файл (выполнен)").assertIsDisplayed()
        onNodeWithText("2. Изменить функцию (ожидает)").assertIsDisplayed()
    }

    @Test
    fun `подтверждение плана зовёт колбэк с идентификатором прогона`() = runComposeUiTest {
        var approved: Pair<RunId, PlanDecision>? = null
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(planRun(RunState.PLANNED))),
                requestFailed = false,
                actions = AgentActions(
                    postTask = {},
                    decidePlan = { runId, decision -> approved = runId to decision },
                ),
            )
        }

        onNodeWithTag("approve-plan").performClick()

        assertEquals(RunId("run-1") to PlanDecision.Approve, approved, "кнопка подтверждения отдаёт прогон и решение")
    }

    @Test
    fun `переделать с комментарием зовёт колбэк с прогоном и решением`() = runComposeUiTest {
        var replanned: Pair<RunId, PlanDecision>? = null
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(planRun(RunState.PLANNED))),
                requestFailed = false,
                actions = AgentActions(
                    postTask = {},
                    decidePlan = { runId, decision -> replanned = runId to decision },
                ),
            )
        }

        onNodeWithTag("plan-comment").performTextInput("разбей на два шага")
        onNodeWithTag("replan-plan").performClick()

        assertEquals(
            RunId("run-1") to PlanDecision.Replan("разбей на два шага"),
            replanned,
            "комментарий обязан уехать решением о перепланировании",
        )
    }

    @Test
    fun `без ожидания кнопок решения нет`() = runComposeUiTest {
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(planRun(RunState.RUNNING))),
                requestFailed = false,
                actions = AgentActions(postTask = {}),
            )
        }

        onNodeWithText("План до начала работы").assertIsDisplayed()
        onNodeWithTag("approve-plan").assertDoesNotExist()
        onNodeWithTag("replan-plan").assertDoesNotExist()
    }

    @Test
    fun `в режиме только предлагать кнопок решения нет`() = runComposeUiTest {
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(planRun(RunState.PLANNED, AutonomyMode.SUGGEST_ONLY))),
                requestFailed = false,
                actions = AgentActions(postTask = {}),
            )
        }

        onNodeWithTag("approve-plan").assertDoesNotExist()
    }

    @Test
    fun `без плана раздел плана не рисуется`() = runComposeUiTest {
        setContent {
            AgentScreen(
                session = HostSession(runs = listOf(runWithTests(TestReport(state = TestState.GREEN)))),
                requestFailed = false,
                actions = AgentActions(postTask = {}),
            )
        }

        onNodeWithText("План до начала работы").assertDoesNotExist()
    }

    /** Прогон с планом: список шагов и состояния; режим задаёт, ждут ли решения по плану. */
    private fun planRun(state: RunState, mode: AutonomyMode = AutonomyMode.ASK_BEFORE_CHANGES): AgentRun = AgentRun(
        id = RunId("run-1"),
        taskId = TaskId("task-1"),
        state = state,
        mode = mode,
        plan = listOf(
            PlanStep(index = 0, summary = "Прочитать файл", status = StepStatus.DONE),
            PlanStep(index = 1, summary = "Изменить функцию", status = StepStatus.PENDING),
        ),
        startedAt = Instant.fromEpochMilliseconds(1),
    )

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
