package dev.aide.tools.file

import dev.aide.domain.FileChangeKind
import dev.aide.domain.HunkKind
import dev.aide.domain.LineKind
import dev.aide.domain.RiskLevel
import dev.aide.domain.ToolOutcome
import dev.aide.tools.ToolResult
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.argumentsOf
import dev.aide.tools.file.ProposeResult
import dev.aide.tools.limits.HardLimitViolation
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.8: запись файла внутри воркспейса — создание, перезапись, каталоги, лимиты, отказы.
 *
 * Проверки идут на настоящем каталоге: инструмент работает с диском, и подделка файловой
 * системы проверяла бы подделку. Отказ за пределами воркспейса — это отказ жёсткого предела
 * ([HardLimitViolation]): в результат его переводит точка вызова, и это её проверка
 * (ToolInvokerChangeSnapshotTest), а здесь видно, что инструмент ходит через порт.
 */
class WriteFileToolTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `новый файл создаётся с заданным содержимым`() {
        val result = runBlocking { write("src/New.kt", "class New\n") }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertEquals("class New\n", workspace.root.resolve("src/New.kt").readText())
        assertTrue(result.text.contains("создан"), "модель обязана понять, что файла не было: ${result.text}")
    }

    @Test
    fun `существующий файл перезаписывается целиком`() {
        workspace.write("src/App.kt", "fun main() = Unit\n")

        val result = runBlocking { write("src/App.kt", "fun main() = println(\"привет\")\n") }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertEquals("fun main() = println(\"привет\")\n", workspace.root.resolve("src/App.kt").readText())
        assertTrue(result.text.contains("перезаписан"), "модель обязана понять, что файл заменён: ${result.text}")
    }

    @Test
    fun `пустой путь — отказ, а не запись в корень`() {
        val result = runBlocking { write("", "содержимое") }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("пустой путь"), result.text)
    }

    @Test
    fun `путь за пределами воркспейса отклоняется`() {
        runBlocking {
            assertFailsWith<HardLimitViolation> { write("../снаружи.txt", "взлом") }
        }
        assertFalse(workspace.root.parent.resolve("снаружи.txt").exists())
    }

    @Test
    fun `запись в новый подкаталог создаёт родительские каталоги`() {
        val result = runBlocking { write("src/main/kotlin/App.kt", "package main\n") }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertEquals("package main\n", workspace.root.resolve("src/main/kotlin/App.kt").readText())
    }

    @Test
    fun `превышение лимита объёма — отказ, и файл не изменён`() {
        workspace.write("src/App.kt", "прежнее содержимое\n")
        val oversize = "a".repeat(WriteFileTool.MAX_WRITE_BYTES + 1)

        val result = runBlocking { write("src/App.kt", oversize) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(
            result.text.contains("${WriteFileTool.MAX_WRITE_BYTES}"),
            "в отказе обязан быть лимит: ${result.text}",
        )
        assertEquals(
            "прежнее содержимое\n",
            workspace.root.resolve("src/App.kt").readText(),
            "отклонённый вызов не имеет права тронуть файл",
        )
    }

    @Test
    fun `запись через симлинк за корень отклоняется, цель не тронута`() {
        val outside = Files.createTempDirectory("aide-write-outside-")
        try {
            val target = outside.resolve("секрет.txt").also { it.writeText("секрет\n") }
            // Симлинк на каталог снаружи, и запись идёт «через» него: путь внутри корня
            // выглядит обычным, а канонизация приводит его к цели за корнем.
            val link = workspace.root.resolve("linked")
            Files.createSymbolicLink(link, outside)

            runBlocking {
                assertFailsWith<HardLimitViolation> { write("linked/секрет.txt", "взлом") }
            }

            assertEquals("секрет\n", target.readText(), "файл за корнем не имеет права быть изменён")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `запись через висячий симлинк отклоняется, файл за корнем не создаётся`() {
        val outside = Files.createTempDirectory("aide-write-dangling-")
        val target = outside.resolve("создать.txt")
        try {
            // Цель симлинка отсутствует: лексически путь «внутри корня», но ядро по ссылке
            // создало бы файл за корнем — ровно то, что обязана отсечь граница.
            Files.createSymbolicLink(workspace.root.resolve("dangling"), target)
            assertFalse(Files.exists(target), "цель обязана отсутствовать, иначе случай не висячий")

            runBlocking {
                assertFailsWith<HardLimitViolation> { write("dangling", "взлом") }
            }

            assertFalse(Files.exists(target), "за висячим симлинком файл создавать нельзя")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `запись поверх каталога — отказ с объяснением`() {
        workspace.directory("src/main")

        val result = runBlocking { write("src/main", "не файл") }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("каталог"), result.text)
    }

    @Test
    fun `describe нового файла даёт ADDED с полным содержимым`() {
        runBlocking {
            val described = describe("src/New.kt", "class New\n")

            val files = assertIs<ProposeResult.Files>(described).files
            assertEquals(1, files.size)
            val change = files.single()
            assertEquals("src/New.kt", change.path)
            assertEquals(FileChangeKind.ADDED, change.changeKind)
            assertEquals(HunkKind.ADD, change.hunks.single().kind)
            assertEquals(RiskLevel.RISKY, change.hunks.single().risk)
            assertEquals(1, change.addedLines)
            assertEquals(0, change.removedLines)
            assertTrue(change.hunks.single().lines.all { it.kind == LineKind.ADDED })
        }
    }

    @Test
    fun `describe существующего файла даёт REPLACE со старыми и новыми строками`() {
        runBlocking {
            workspace.write("src/App.kt", "fun main() = Unit\n")

            val described = describe("src/App.kt", "fun main() = println()\n")

            val change = assertIs<ProposeResult.Files>(described).files.single()
            assertEquals(FileChangeKind.MODIFIED, change.changeKind)
            assertEquals(HunkKind.REPLACE, change.hunks.single().kind)
            assertTrue(change.hunks.single().lines.any { it.kind == LineKind.REMOVED })
            assertTrue(change.hunks.single().lines.any { it.kind == LineKind.ADDED })
            assertEquals(1, change.removedLines)
            assertEquals(1, change.addedLines)
        }
    }

    @Test
    fun `describe считает пустое содержимое и CRLF без лишних строк`() {
        runBlocking {
            val empty = assertIs<ProposeResult.Files>(describe("empty.txt", "")).files.single()
            val crlf = assertIs<ProposeResult.Files>(describe("crlf.txt", "a\r\nb\r\n")).files.single()

            assertEquals(0, empty.addedLines)
            assertEquals(listOf("a", "b"), crlf.hunks.single().lines.map { it.text })
            assertEquals(2, crlf.addedLines)
        }
    }

    @Test
    fun `describe пустого пути возвращает Failure`() {
        runBlocking {
            val described = describe("", "x")
            assertIs<ProposeResult.Failure>(described)
        }
    }

    @Test
    fun `describe не создаёт файла на диске`() {
        runBlocking {
            describe("src/Ghost.kt", "class Ghost")

            assertFalse(workspace.root.resolve("src/Ghost.kt").exists(), "describe не должен создавать файл")
        }
    }

    private suspend fun describe(path: String, content: String): ProposeResult = WriteFileTool.describe(
        argumentsOf("path" to path, "content" to content),
        workspace.context,
        RiskLevel.RISKY,
    )

    private suspend fun write(path: String, content: String): ToolResult =
        WriteFileTool.execute(argumentsOf("path" to path, "content" to content), workspace.context)
}
