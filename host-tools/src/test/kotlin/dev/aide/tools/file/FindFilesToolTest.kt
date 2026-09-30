package dev.aide.tools.file

import dev.aide.domain.ToolOutcome
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.argumentsOf
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.7: поиск файлов по маске, лимит результатов и каталог `.git` вне выдачи.
 *
 * Маска проверяется на настоящем дереве: правило «маска без каталога ищет по имени,
 * маска с каталогом — по пути от корня» видно только на файлах разной глубины.
 */
class FindFilesToolTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    private fun find(mask: String): String =
        runBlocking { FindFilesTool.execute(argumentsOf("mask" to mask), workspace.context).text }

    @Test
    fun `маска по имени находит файлы на любой глубине`() {
        workspace.write("Main.kt", "fun main() = Unit\n")
        workspace.write("src/App.kt", "class App\n")
        workspace.write("src/deep/Util.kt", "object Util\n")
        workspace.write("docs/readme.md", "# Проект\n")

        val found = find("*.kt").lines().drop(1).toSet()

        assertEquals(setOf("Main.kt", "src/App.kt", "src/deep/Util.kt"), found)
    }

    @Test
    fun `маска с ведущей частью «на любой глубине» находит и файл в корне`() {
        workspace.write("Main.kt", "fun main() = Unit\n")
        workspace.write("src/App.kt", "class App\n")

        val found = find("**/*.kt").lines().drop(1).toSet()

        assertEquals(setOf("Main.kt", "src/App.kt"), found, "привычная маска не должна терять файлы в корне")
    }

    @Test
    fun `маска с каталогом ограничивает поиск этим каталогом`() {
        workspace.write("Main.kt", "fun main() = Unit\n")
        workspace.write("src/App.kt", "class App\n")

        val found = find("src/**/*.kt").lines().drop(1).toSet()

        assertEquals(setOf("src/App.kt"), found)
    }

    @Test
    fun `каталог git в выдачу не попадает`() {
        workspace.write("Main.kt", "fun main() = Unit\n")
        workspace.write(".git/config", "[core]\n")

        val found = find("*.kt")

        assertFalse(found.contains(".git/config"), "служебные файлы git агенту не нужны: $found")
        assertTrue(found.contains("Main.kt"), "обычный файл обязан остаться: $found")
    }

    @Test
    fun `пустой результат — не ошибка`() {
        workspace.write("Main.kt", "fun main() = Unit\n")

        val result = runBlocking { FindFilesTool.execute(argumentsOf("mask" to "*.rs"), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, "«ничего не найдено» — это ответ, а не сбой")
        assertTrue(result.text.contains("не найдено"), result.text)
    }

    @Test
    fun `результат ограничен лимитом и это видно по пометке`() {
        repeat(FindFilesTool.MAX_RESULTS + EXTRA_FILES) { index -> workspace.write("src/File$index.kt", "класс\n") }

        val result = runBlocking { FindFilesTool.execute(argumentsOf("mask" to "*.kt"), workspace.context) }

        val shown = result.text.lines().drop(1).count { it.endsWith(".kt") }
        assertEquals(FindFilesTool.MAX_RESULTS, shown, "модели отдаётся не больше лимита файлов")
        assertTrue(
            result.text.contains("${FindFilesTool.MAX_RESULTS + EXTRA_FILES}"),
            "в ответе обязано быть, сколько всего найдено",
        )
        assertTrue(
            result.text.contains("показаны первые"),
            "об обрезке обязана быть пометка: ${result.text.takeLast(80)}",
        )
    }

    @Test
    fun `неразбираемая маска — отказ с примером`() {
        val result = runBlocking { FindFilesTool.execute(argumentsOf("mask" to "["), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("*.kt"), "модели нужен пример рабочей маски: ${result.text}")
    }

    @Test
    fun `пустая маска — отказ, а не весь репозиторий`() {
        workspace.write("Main.kt", "fun main() = Unit\n")

        val result = runBlocking { FindFilesTool.execute(argumentsOf("mask" to "  "), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
    }

    private companion object {

        /** Насколько файлов больше лимита: проверяется, что лимит действительно режет. */
        const val EXTRA_FILES = 20
    }
}
