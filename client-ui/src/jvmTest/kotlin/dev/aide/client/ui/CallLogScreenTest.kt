package dev.aide.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ToolCallLog
import dev.aide.client.ui.screens.CallLogActions
import dev.aide.client.ui.screens.CallLogScreen
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.ToolCallScope
import dev.aide.protocol.ToolCallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

/**
 * T-1.3, T-1.14: список журнала рисует страницу, состояния § 6.1, раскрытие записи
 * и фильтры по задаче/инструменту.
 *
 * «Не строится целиком» проверяется на 500 записях: LazyColumn компонует только видимые
 * строки, поэтому последняя запись в дереве отсутствует, а первая — есть. Раскрытие
 * проверяется отдельно: страница несёт только превью, и полное содержимое приходит
 * отдельным запросом, у которого своё состояние загрузки и ошибки.
 */
@OptIn(ExperimentalTestApi::class)
class CallLogScreenTest {

    @Test
    fun `список по 500 записям рисует только видимую часть`() = runComposeUiTest {
        val calls = (0 until 500).map { summary(it) }

        show(log(RUN_SCOPE, calls).copy(loaded = true, hasMore = true))

        onNodeWithTag("call-log-row-tc-0").assertExists()
        onNodeWithTag("call-log-row-tc-499").assertDoesNotExist()
    }

    @Test
    fun `загруженный пустой журнал объясняет, что вызовов нет`() = runComposeUiTest {
        show(log(RUN_SCOPE).copy(loaded = true))

        onNodeWithText("Вызовов пока нет").assertIsDisplayed()
    }

    @Test
    fun `незагруженный журнал показывается загрузкой, а не пустотой`() = runComposeUiTest {
        // `loaded = false`: страницу ещё не запрашивали — это загрузка, а не «пусто».
        show(log(RUN_SCOPE))

        onNodeWithText("Загрузка").assertIsDisplayed()
        onNodeWithText("Вызовов пока нет").assertDoesNotExist()
    }

    @Test
    fun `без области журнал говорит об этом`() = runComposeUiTest {
        show(log())

        onNodeWithText("Прогона пока нет — журнал пуст").assertIsDisplayed()
    }

    @Test
    fun `ошибка загрузки показывается состоянием ошибки`() = runComposeUiTest {
        show(log(RUN_SCOPE).copy(failed = true))

        onNodeWithText("Не удалось загрузить журнал: хост не ответил").assertIsDisplayed()
    }

    @Test
    fun `сводка показывает признак «требовал подтверждения»`() = runComposeUiTest {
        show(log(RUN_SCOPE, listOf(summary(0, requiredApproval = true))).copy(loaded = true))

        onNodeWithText("Требовал подтверждения").assertIsDisplayed()
    }

    @Test
    fun `сводка показывает решение разрешить один раз`() = runComposeUiTest {
        show(
            log(
                RUN_SCOPE,
                listOf(summary(0, requiredApproval = true, approval = ApprovalDecision.ALLOW_ONCE)),
            ).copy(loaded = true),
        )

        onNodeWithText("Разрешено один раз").assertIsDisplayed()
    }

    @Test
    fun `сводка показывает решение запретить`() = runComposeUiTest {
        show(
            log(
                RUN_SCOPE,
                listOf(summary(0, requiredApproval = true, approval = ApprovalDecision.DENY)),
            ).copy(loaded = true),
        )

        onNodeWithText("Запрещено").assertIsDisplayed()
    }

    @Test
    fun `свёрнутая запись показывает превью и пометку обрезки`() = runComposeUiTest {
        show(log(RUN_SCOPE, listOf(summary(0, resultTruncated = true))).copy(loaded = true))

        onNodeWithText("read_file · успех · 5 мс · 0 мк").assertIsDisplayed()
        onNodeWithText("показано не всё").assertIsDisplayed()
    }

    @Test
    fun `кнопка догрузки есть, когда есть что догружать`() = runComposeUiTest {
        show(log(RUN_SCOPE, listOf(summary(0))).copy(loaded = true, hasMore = true))

        onNodeWithTag("call-log-load-older").assertExists()
    }

    @Test
    fun `кнопки догрузки нет, когда догружать нечего`() = runComposeUiTest {
        show(log(RUN_SCOPE, listOf(summary(0))).copy(loaded = true, hasMore = false))

        onNodeWithTag("call-log-load-older").assertDoesNotExist()
    }

    @Test
    fun `нет связи показывается баннером над журналом`() = runComposeUiTest {
        show(log(RUN_SCOPE).copy(loaded = true, offline = true))

        onNodeWithTag("offline-banner").assertExists()
    }

    @Test
    fun `тап по строке просит раскрыть именно эту запись`() = runComposeUiTest {
        var toggled: ToolCallId? = null
        show(log(RUN_SCOPE, listOf(summary(0), summary(1))).copy(loaded = true), onToggleRow = { toggled = it })

        onNodeWithTag("call-log-row-tc-1").performClick()

        assertEquals(ToolCallId("tc-1"), toggled)
    }

    @Test
    fun `раскрытая запись во время загрузки показывает загрузку`() = runComposeUiTest {
        show(
            log(RUN_SCOPE, listOf(summary(0)))
                .copy(loaded = true, expanded = ToolCallId("tc-0"), detailLoading = true),
        )

        // Строка кликабельна и объединяет семантику детей — тег ищем в несведённом дереве.
        onNodeWithTag("call-log-detail-loading", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `ошибка загрузки полного содержимого видна у записи`() = runComposeUiTest {
        show(log(RUN_SCOPE, listOf(summary(0))).copy(loaded = true, expanded = ToolCallId("tc-0"), detailFailed = true))

        onNodeWithText("Не удалось загрузить вызов").assertIsDisplayed()
    }

    @Test
    fun `раскрытая запись показывает полные аргументы и результат`() = runComposeUiTest {
        val full = detailCall(0)
        show(log(RUN_SCOPE, listOf(summary(0))).copy(loaded = true, expanded = ToolCallId("tc-0"), detail = full))

        onNodeWithText(full.arguments).assertIsDisplayed()
        onNodeWithText(full.result.orEmpty()).assertIsDisplayed()
    }

    @Test
    fun `выбор задачи зовёт колбэк`() = runComposeUiTest {
        var selected: TaskId? = null
        val task = Task(
            id = TaskId("t-1"),
            title = "Авторизация",
            prompt = "Сделай",
            branch = "ai/t-1",
            status = dev.aide.domain.TaskStatus.RUNNING,
            createdAt = Instant.fromEpochMilliseconds(BASE_MILLIS),
        )
        show(
            log = log(TASK_SCOPE).copy(loaded = true),
            tasks = listOf(task),
            onSelectTask = { selected = it },
        )

        onNodeWithTag("call-log-task-dropdown").performClick()
        onNodeWithTag("call-log-task-t-1").performClick()

        assertEquals(TaskId("t-1"), selected)
    }

    @Test
    fun `выбор инструмента зовёт колбэк с именем`() = runComposeUiTest {
        var selected: String? = null
        show(
            log = log(RUN_SCOPE).copy(loaded = true, availableTools = listOf("read_file", "write_file")),
            onSelectTool = { selected = it },
        )

        onNodeWithTag("call-log-tool-dropdown").performClick()
        onNodeWithTag("call-log-tool-write_file").performClick()

        assertEquals("write_file", selected)
    }

    @Test
    fun `выбор всех инструментов зовёт колбэк с null`() = runComposeUiTest {
        var selected: String? = "unchanged"
        show(
            log = log(RUN_SCOPE).copy(loaded = true, availableTools = listOf("read_file")),
            onSelectTool = { selected = it },
        )

        onNodeWithTag("call-log-tool-dropdown").performClick()
        onNodeWithTag("call-log-tool-__all__").performClick()

        assertEquals(null, selected)
    }

    @Test
    fun `пустой отфильтрованный журнал отличается от ещё не загруженного`() = runComposeUiTest {
        show(log(RUN_SCOPE, tool = "write_file").copy(loaded = true))

        onNodeWithText("Вызовов пока нет").assertIsDisplayed()
        onNodeWithText("Загрузка").assertDoesNotExist()
    }

    private fun ComposeUiTest.show(
        log: ToolCallLog,
        tasks: List<Task> = emptyList(),
        onToggleRow: (ToolCallId) -> Unit = {},
        onSelectTask: (TaskId) -> Unit = {},
        onSelectTool: (String?) -> Unit = {},
    ) {
        setContent {
            TestCallLog(
                log = log,
                tasks = tasks,
                onToggleRow = onToggleRow,
                onSelectTask = onSelectTask,
                onSelectTool = onSelectTool,
            )
        }
    }

    /** Вызов экрана с колбэками-заглушками: тест проверяет показ, а не навигацию. */
    @Composable
    private fun TestCallLog(
        log: ToolCallLog,
        tasks: List<Task>,
        onToggleRow: (ToolCallId) -> Unit,
        onSelectTask: (TaskId) -> Unit = {},
        onSelectTool: (String?) -> Unit = {},
    ) {
        CallLogScreen(
            log = log,
            tasks = tasks,
            actions = CallLogActions(
                onLoadPage = {},
                onBack = {},
                onToggleRow = onToggleRow,
                onSelectTask = onSelectTask,
                onSelectTool = onSelectTool,
            ),
        )
    }

    @Test
    fun `неприменённое предложение показано отдельным исходом`() = runComposeUiTest {
        val proposed = summary(0).copy(outcome = ToolOutcome.PROPOSED)
        show(log(RUN_SCOPE, listOf(proposed)).copy(loaded = true))

        onNodeWithText("предложение", substring = true).assertIsDisplayed()
    }

    private fun log(
        scope: ToolCallScope? = null,
        calls: List<ToolCallSummary> = emptyList(),
        tool: String? = null,
    ): ToolCallLog = ToolCallLog(scope = scope, calls = calls, tool = tool)

    /** Полная запись для раскрытия: текст длиннее превью, по нему видно, что показано всё. */
    private fun detailCall(index: Int): ToolCall = ToolCall(
        id = ToolCallId("tc-$index"),
        runId = RUN,
        tool = "read_file",
        arguments = """{"path":"file-$index","полный":"аргумент целиком"}""",
        result = "полный результат вызова $index",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 5,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = false,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
    )

    private fun summary(
        index: Int,
        requiredApproval: Boolean = false,
        resultTruncated: Boolean = false,
        approval: ApprovalDecision? = null,
    ): ToolCallSummary = ToolCallSummary(
        id = ToolCallId("tc-$index"),
        runId = RUN,
        tool = "read_file",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 5,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = requiredApproval,
        approval = approval,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
        argumentsPreview = """{"path":"file-$index"}""",
        argumentsTruncated = false,
        resultPreview = "результат-$index",
        resultTruncated = resultTruncated,
    )

    private companion object {
        val RUN: RunId = RunId("r-1")
        val RUN_SCOPE: ToolCallScope.Run = ToolCallScope.Run(RUN)
        val TASK_SCOPE: ToolCallScope.Task = ToolCallScope.Task(TaskId("t-1"))
        const val BASE_MILLIS: Long = 1_758_535_200_000
    }
}
