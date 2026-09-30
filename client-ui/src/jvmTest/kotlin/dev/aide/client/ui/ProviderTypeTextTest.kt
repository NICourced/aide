package dev.aide.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Text
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.providerTypeResource
import dev.aide.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * T-1.56: протокол показывается названием из ресурсов, а не именем перечисления.
 *
 * `OPENAI_COMPATIBLE` и `ANTHROPIC` — идентификаторы для кода; пользователю они ничего
 * не говорят, и показывать их в разделе настроек значит нарушать NFR-13.
 */
@OptIn(ExperimentalTestApi::class)
class ProviderTypeTextTest {

    @Test
    fun `каждый протокол показывается названием из ресурсов`() = runComposeUiTest {
        setContent {
            Column {
                ProviderType.entries.forEach { type -> Text(Strings.text(providerTypeResource(type))) }
            }
        }

        onNodeWithText("OpenAI-совместимый (chat completions)").assertIsDisplayed()
        onNodeWithText("Anthropic Messages API").assertIsDisplayed()
        ProviderType.entries.forEach { type ->
            onNodeWithText(type.name).assertDoesNotExist()
        }
    }

    @Test
    fun `разные протоколы дают разные строки`() {
        // Общий текст на два протокола означал бы, что пользователь не различит, какой
        // из них выбран, — а от этого зависит формат запроса.
        val resources = ProviderType.entries.map { providerTypeResource(it) }

        assertEquals(ProviderType.entries.size, resources.toSet().size)
    }
}
