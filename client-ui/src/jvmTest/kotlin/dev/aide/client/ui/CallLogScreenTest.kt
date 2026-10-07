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
import dev.aide.client.ui.screens.CallLogScreen
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.protocol.ToolCallSummary
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.datetime.Instant

/**
 * T-1.3: список журнала рисует страницу, а не весь лог; состояния § 6.1 и раскрытие записи.
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

        show(log(RUN, calls).copy(loaded = true, hasMore = true))

        onNodeWithTag("call-log-row-tc-0").assertExists()
        onNodeWithTag("call-log-row-tc-499").assertDoesNotExist()
    }

    @Test
    fun `загруженный пустой журнал объясняет, что вызовов нет`() = runComposeUiTest {
        show(log(RUN).copy(loaded = true))

        onNodeWithText("Вызовов пока нет").assertIsDisplayed()
    }

    @Test
    fun `незагруженный журнал показывается загрузкой, а не пустотой`() = runComposeUiTest {
        // `loaded = false`: страницу ещё не запрашивали — это загрузка, а не «пусто».
        show(log(RUN))

        onNodeWithText("Загрузка").assertIsDisplayed()
        onNodeWithText("Вызовов пока нет").assertDoesNotExist()
    }

    @Test
    fun `без прогона журнал говорит об этом`() = runComposeUiTest {
        show(log())

        onNodeWithText("Прогона пока нет — журнал пуст").assertIsDisplayed()
    }

    @Test
    fun `ошибка загрузки показывается состоянием ошибки`() = runComposeUiTest {
        show(log(RUN).copy(failed = true))

        onNodeWithText("Не удалось загрузить журнал: хост не ответил").assertIsDisplayed()
    }

    @Test
    fun `сводка показывает признак «требовал подтверждения»`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0, requiredApproval = true))).copy(loaded = true))

        onNodeWithText("Требовал подтверждения").assertIsDisplayed()
    }

    @Test
    fun `свёрнутая запись показывает превью и пометку обрезки`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0, resultTruncated = true))).copy(loaded = true))

        onNodeWithText("read_file · успех · 5 мс · 0 мк").assertIsDisplayed()
        onNodeWithText("показано не всё").assertIsDisplayed()
    }

    @Test
    fun `кнопка догрузки есть, когда есть что догружать`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0))).copy(loaded = true, hasMore = true))

        onNodeWithTag("call-log-load-older").assertExists()
    }

    @Test
    fun `кнопки догрузки нет, когда догружать нечего`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0))).copy(loaded = true, hasMore = false))

        onNodeWithTag("call-log-load-older").assertDoesNotExist()
    }

    @Test
    fun `нет связи показывается баннером над журналом`() = runComposeUiTest {
        show(log(RUN).copy(loaded = true, offline = true))

        onNodeWithTag("offline-banner").assertExists()
    }

    @Test
    fun `тап по строке просит раскрыть именно эту запись`() = runComposeUiTest {
        var toggled: ToolCallId? = null
        show(log(RUN, listOf(summary(0), summary(1))).copy(loaded = true)) { toggled = it }

        onNodeWithTag("call-log-row-tc-1").performClick()

        assertEquals(ToolCallId("tc-1"), toggled)
    }

    @Test
    fun `раскрытая запись во время загрузки показывает загрузку`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0))).copy(loaded = true, expanded = ToolCallId("tc-0"), detailLoading = true))

        // Строка кликабельна и объединяет семантику детей — тег ищем в несведённом дереве.
        onNodeWithTag("call-log-detail-loading", useUnmergedTree = true).assertExists()
    }

    @Test
    fun `ошибка загрузки полного содержимого видна у записи`() = runComposeUiTest {
        show(log(RUN, listOf(summary(0))).copy(loaded = true, expanded = ToolCallId("tc-0"), detailFailed = true))

        onNodeWithText("Не удалось загрузить вызов").assertIsDisplayed()
    }

    @Test
    fun `раскрытая запись показывает полные аргументы и результат`() = runComposeUiTest {
        val full = detailCall(0)
        show(log(RUN, listOf(summary(0))).copy(loaded = true, expanded = ToolCallId("tc-0"), detail = full))

        onNodeWithText(full.arguments).assertIsDisplayed()
        onNodeWithText(full.result.orEmpty()).assertIsDisplayed()
    }

    private fun ComposeUiTest.show(log: ToolCallLog, onToggleRow: (ToolCallId) -> Unit = {}) {
        setContent { TestCallLog(log, onToggleRow) }
    }

    /** Вызов экрана с колбэками-заглушками: тест проверяет показ, а не навигацию. */
    @Composable
    private fun TestCallLog(log: ToolCallLog, onToggleRow: (ToolCallId) -> Unit) {
        CallLogScreen(log = log, onLoadPage = {}, onBack = {}, onToggleRow = onToggleRow)
    }

    private fun log(runId: RunId? = null, calls: List<ToolCallSummary> = emptyList()): ToolCallLog =
        ToolCallLog(runId = runId, calls = calls)

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
    ): ToolCallSummary = ToolCallSummary(
        id = ToolCallId("tc-$index"),
        runId = RUN,
        tool = "read_file",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 5,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = requiredApproval,
        at = Instant.fromEpochMilliseconds(BASE_MILLIS + index),
        argumentsPreview = """{"path":"file-$index"}""",
        argumentsTruncated = false,
        resultPreview = "результат-$index",
        resultTruncated = resultTruncated,
    )

    private companion object {
        val RUN: RunId = RunId("r-1")
        const val BASE_MILLIS: Long = 1_758_535_200_000
    }
}
