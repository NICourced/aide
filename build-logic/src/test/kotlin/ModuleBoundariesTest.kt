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
    fun `импорт хоста в host-tools считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "host-tools",
            files = mapOf("ReadFileTool.kt" to "import dev.aide.host.workspace.WorkspaceFileSystem\n"),
        )
        assertEquals(
            listOf("ReadFileTool.kt: dev.aide.host → dev.aide.host.workspace.WorkspaceFileSystem"),
            violations,
        )
    }

    /**
     * Проверяется каждым префиксом отдельно: правило, скопированное у `host-core`
     * (там этих запретов нет), прошло бы такой набор молча.
     */
    @Test
    fun `импорты агента, протокола и git в host-tools считаются нарушением`() {
        val cases = listOf(
            "Tool.kt: dev.aide.agent → dev.aide.agent.AgentRun" to "import dev.aide.agent.AgentRun",
            "Tool.kt: dev.aide.protocol → dev.aide.protocol.HostMode" to "import dev.aide.protocol.HostMode",
            "Tool.kt: org.eclipse.jgit → org.eclipse.jgit.api.Git" to "import org.eclipse.jgit.api.Git",
        )
        cases.forEach { (expected, importLine) ->
            val violations = ModuleBoundaries.findViolations(
                module = "host-tools",
                files = mapOf("Tool.kt" to "$importLine\n"),
            )
            assertEquals(listOf(expected), violations, "ожидалось нарушение для «$importLine»")
        }
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
