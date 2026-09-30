package dev.aide.client.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.ui.screens.AgentScreen
import kotlin.test.Test

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
            runs = emptyList(),
            tasks = emptyList(),
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
}
