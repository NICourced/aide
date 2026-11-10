package dev.aide.client.ui

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
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
