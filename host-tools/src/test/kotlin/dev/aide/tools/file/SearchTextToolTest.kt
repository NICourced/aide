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
 * T-1.7: поиск строки по проекту — путь, номер строки и лимиты.
 *
 * Проверки идут на настоящем дереве: нумерация строк, пропуск двоичных и слишком
 * больших файлов видно только на файлах, а не на подделке файловой системы.
 */
class SearchTextToolTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    private fun search(query: String): String =
        runBlocking { SearchTextTool.execute(argumentsOf("query" to query), workspace.context).text }

    @Test
    fun `совпадения находятся в нескольких файлах с номерами строк`() {
        workspace.write("src/A.kt", "fun a() = 1\nfun token() = 2\n")
        workspace.write("src/B.kt", "// token тут\nclass B\n")
        workspace.write("src/C.kt", "class C\n")

        val found = search("token").lines().drop(1)

        assertEquals(
            listOf("src/A.kt:2: fun token() = 2", "src/B.kt:1: // token тут"),
            found,
            "в выдаче обязаны быть путь, номер строки и сама строка",
        )
    }

    @Test
    fun `совпадений нет — это не ошибка`() {
        workspace.write("src/A.kt", "fun a() = 1\n")

        val result = runBlocking { SearchTextTool.execute(argumentsOf("query" to "token"), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertTrue(result.text.contains("не найдено"), result.text)
    }

    @Test
    fun `регистр учитывается`() {
        workspace.write("src/A.kt", "val Token = 1\nval token = 2\n")

        val found = search("token").lines().drop(1)

        assertEquals(listOf("src/A.kt:2: val token = 2"), found)
    }

    @Test
    fun `результат ограничен лимитом и это видно по пометке`() {
        val lines = (0..SearchTextTool.MAX_MATCHES + EXTRA_MATCHES).joinToString("\n") { "token $it" }
        workspace.write("src/A.kt", lines)

        val result = runBlocking { SearchTextTool.execute(argumentsOf("query" to "token"), workspace.context) }

        assertEquals(SearchTextTool.MAX_MATCHES, result.text.lines().count { it.startsWith("src/A.kt:") })
        assertTrue(
            result.text.contains("лимит в"),
            "об исчерпанном лимите обязана быть пометка: ${result.text.takeLast(80)}",
        )
    }

    @Test
    fun `каталог git не просматривается`() {
        workspace.write("src/A.kt", "// token тут\n")
        workspace.write(".git/COMMIT_EDITMSG", "token в служебном файле\n")

        val found = search("token").lines().drop(1)

        assertEquals(listOf("src/A.kt:1: // token тут"), found)
    }

    @Test
    fun `двоичный файл пропускается`() {
        workspace.writeBytes("data/blob.bin", byteArrayOf(0x00, 0x01) + "token".toByteArray())

        val result = runBlocking { SearchTextTool.execute(argumentsOf("query" to "token"), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertTrue(result.text.contains("не найдено"), "двоичный файл не текстовый: ${result.text}")
    }

    @Test
    fun `крупный файл пропускается, и об этом сказано в ответе`() {
        workspace.write("src/A.kt", "// token тут\n")
        workspace.writeBytes("data/huge.txt", ByteArray(SearchTextTool.MAX_SEARCH_FILE_BYTES + 1) { 'a'.code.toByte() })

        val result = runBlocking { SearchTextTool.execute(argumentsOf("query" to "token"), workspace.context) }

        assertEquals(listOf("src/A.kt:1: // token тут"), result.text.lines().drop(1).filter { it.startsWith("src/") })
        assertTrue(
            result.text.contains("пропущено файлов крупнее"),
            "молчаливый пропуск выглядел бы как «там ничего нет»: ${result.text}",
        )
    }

    @Test
    fun `пустой запрос — отказ, а не все строки проекта`() {
        workspace.write("src/A.kt", "fun a() = 1\n")

        val result = runBlocking { SearchTextTool.execute(argumentsOf("query" to ""), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
    }

    @Test
    fun `длинная строка обрезается в выдаче`() {
        workspace.write("data/min.js", "token " + "a".repeat(LONG_LINE) + "\n")

        val found = search("token").lines().first { it.startsWith("data/min.js") }

        assertFalse(found.length > LINE_LIMIT_MARGIN, "строка в ответе обязана быть короткой: ${found.length}")
    }

    private companion object {

        /** Насколько совпадений больше лимита: проверяется, что лимит действительно режет. */
        const val EXTRA_MATCHES = 20

        /** Заведомо длиннее лимита строки в выдаче. */
        const val LONG_LINE = 1_000

        /** Предел длины строки в выдаче плюс запас на путь и номер. */
        const val LINE_LIMIT_MARGIN = 240
    }
}
