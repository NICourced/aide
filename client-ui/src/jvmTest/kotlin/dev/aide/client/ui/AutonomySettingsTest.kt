package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.settings.KeyValueStore
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.ui.screens.ModelSettingsState
import dev.aide.client.ui.screens.SettingsScreen
import dev.aide.domain.AutonomyMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** Выбор режима автономности в настройках (T-1.15). */
@OptIn(ExperimentalTestApi::class)
class AutonomySettingsTest {

    @Test
    fun `выбор сохраняется и сразу отмечается выбранным`() = runComposeUiTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        setContent {
            SettingsScreen(
                settings = settings,
                modelConfig = ModelSettingsState(
                    config = null,
                    catalog = emptyList(),
                    check = null,
                    error = null,
                    onSave = {},
                    onCheck = {},
                ),
                onOpenRepository = {},
            )
        }

        onNodeWithTag("choice-${AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS}").performScrollTo().performClick()

        assertEquals(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, settings.autonomyMode)
        onNodeWithTag("choice-${AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS}").assertIsNotEnabled()
    }

    @Test
    fun `порог сохраняется и показывается как действующий`() = runComposeUiTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        setContent {
            SettingsScreen(
                settings = settings,
                modelConfig = emptyModelConfig(),
                onOpenRepository = {},
            )
        }

        onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
        onNodeWithTag("settings-safe-volume-threshold").performTextInput("100")
        onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()

        assertEquals(100, settings.safeVolumeThresholdLines)
        onNodeWithTag("settings-safe-volume-threshold").assertTextContains("100")
    }

    @Test
    fun `неположительный порог не сохраняется и показывает ошибку`() = runComposeUiTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        setContent {
            SettingsScreen(
                settings = settings,
                modelConfig = emptyModelConfig(),
                onOpenRepository = {},
            )
        }

        onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
        onNodeWithTag("settings-safe-volume-threshold").performTextInput("0")
        onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()

        assertEquals(50, settings.safeVolumeThresholdLines)
        onNodeWithText("Порог должен быть положительным числом").assertIsDisplayed()
    }

    @Test
    fun `два последовательных значения порога сохраняются`() = runComposeUiTest {
        val settings = SettingsStore(InMemoryKeyValueStore())
        setContent {
            SettingsScreen(
                settings = settings,
                modelConfig = emptyModelConfig(),
                onOpenRepository = {},
            )
        }

        onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
        onNodeWithTag("settings-safe-volume-threshold").performTextInput("75")
        onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()
        assertEquals(75, settings.safeVolumeThresholdLines)

        onNodeWithTag("settings-safe-volume-threshold").performScrollTo().performTextClearance()
        onNodeWithTag("settings-safe-volume-threshold").performTextInput("30")
        onNodeWithTag("settings-safe-volume-threshold-apply").performScrollTo().performClick()
        assertEquals(30, settings.safeVolumeThresholdLines)
    }

    private fun emptyModelConfig(): ModelSettingsState = ModelSettingsState(
        config = null,
        catalog = emptyList(),
        check = null,
        error = null,
        onSave = {},
        onCheck = {},
    )

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
