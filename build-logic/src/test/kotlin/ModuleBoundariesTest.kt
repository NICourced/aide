package aide.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleBoundariesTest {

    @Test
    fun `импорт хоста в client-ui считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "client-ui",
            files = mapOf("App.kt" to "package dev.aide.client.ui\nimport dev.aide.host.HostApp\n"),
        )
        assertEquals(listOf("App.kt: dev.aide.host → dev.aide.host.HostApp"), violations)
    }

    @Test
    fun `импорт compose в domain считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "domain",
            files = mapOf("Risk.kt" to "import org.jetbrains.compose.ui.Modifier\n"),
        )
        assertEquals(listOf("Risk.kt: org.jetbrains.compose → org.jetbrains.compose.ui.Modifier"), violations)
    }

    @Test
    fun `импорт клиента в host-core считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "host-core",
            files = mapOf("EmbeddedHost.kt" to "import dev.aide.client.state.HostClient\n"),
        )
        assertEquals(listOf("EmbeddedHost.kt: dev.aide.client → dev.aide.client.state.HostClient"), violations)
    }

    @Test
    fun `чистый файл нарушений не даёт`() {
        val violations = ModuleBoundaries.findViolations(
            module = "domain",
            files = mapOf("Risk.kt" to "import kotlinx.serialization.Serializable\n"),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `текст сообщения объясняет причину и действие`() {
        val message = ModuleBoundaries.message("client-ui", listOf("App.kt: dev.aide.host → dev.aide.host.HostApp"))
        assertTrue(message.contains("Нарушены границы модуля 'client-ui'"))
        assertTrue(message.contains("Почему это запрещено"))
        assertTrue(message.contains("Что делать"))
    }
}
