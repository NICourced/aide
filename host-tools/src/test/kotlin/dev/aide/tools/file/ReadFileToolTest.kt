package dev.aide.tools.file

import dev.aide.domain.ToolOutcome
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.argumentsOf
import dev.aide.tools.limits.HardLimitViolation
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking

/**
 * T-1.7: чтение файла внутри воркспейса, обрезка, отказы.
 *
 * Проверки идут на настоящем каталоге: инструмент работает с диском, и подделка
 * файловой системы проверяла бы подделку. Отказ за пределами воркспейса — это отказ
 * жёсткого предела ([HardLimitViolation]): в результат его переводит точка вызова,
 * и это её проверка (ToolInvokerTest), а здесь видно, что инструмент ходит через порт.
 */
class ReadFileToolTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `файл внутри воркспейса читается целиком`() {
        workspace.write("src/App.kt", "fun main() = Unit\n")

        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "src/App.kt"), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertEquals("fun main() = Unit\n", result.text)
    }

    @Test
    fun `путь за пределами воркспейса отклоняется`() {
        runBlocking {
            assertFailsWith<HardLimitViolation> {
                ReadFileTool.execute(argumentsOf("path" to "../снаружи.txt"), workspace.context)
            }
        }
    }

    @Test
    fun `абсолютный путь за пределами воркспейса отклоняется`() {
        val outside = workspace.root.parent.resolve("aide-outside.txt")
        outside.toFile().writeText("секрет\n")
        try {
            runBlocking {
                assertFailsWith<HardLimitViolation> {
                    ReadFileTool.execute(argumentsOf("path" to outside.toString()), workspace.context)
                }
            }
        } finally {
            outside.toFile().delete()
        }
    }

    @Test
    fun `пустой путь — отказ с объяснением, а не чтение каталога`() {
        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to ""), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("пустой путь"), result.text)
    }

    @Test
    fun `отсутствующий файл даёт понятный отказ для модели`() {
        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "нет-такого.kt"), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("нет-такого.kt"), "в отказе обязан быть путь: ${result.text}")
    }

    @Test
    fun `каталог вместо файла — отказ`() {
        workspace.write("src/App.kt", "fun main() = Unit\n")

        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "src"), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome)
        assertTrue(result.text.contains("каталог"), "модель обязана понять, что путь — каталог: ${result.text}")
    }

    @Test
    fun `большой файл обрезается и это видно по пометке`() {
        val size = ReadFileTool.MAX_READ_BYTES + OVERSIZE
        workspace.writeBytes("data/big.txt", ByteArray(size) { 'a'.code.toByte() })

        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "data/big.txt"), workspace.context) }

        assertEquals(ToolOutcome.SUCCESS, result.outcome)
        assertTrue(
            result.text.contains("обрезан"),
            "пометка об обрезке обязана быть: ${result.text.takeLast(80)}",
        )
        assertTrue(result.text.contains("$size"), "в пометке обязан быть настоящий размер файла")
        assertTrue(
            result.text.length <= ReadFileTool.MAX_READ_BYTES + NOTE_MARGIN,
            "в ответ модели не должно попадать больше лимита: ${result.text.length}",
        )
    }

    @Test
    fun `файл без обрезки пометки не получает`() {
        workspace.writeBytes("data/small.txt", ByteArray(ReadFileTool.MAX_READ_BYTES))

        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "data/small.txt"), workspace.context) }

        assertTrue("обрезан" !in result.text, "ровно на лимите файл ещё целый")
    }

    @Test
    fun `двоичный файл не отдаётся модели`() {
        workspace.writeBytes("data/elf.bin", byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01))

        val result = runBlocking { ReadFileTool.execute(argumentsOf("path" to "data/elf.bin"), workspace.context) }

        assertEquals(ToolOutcome.FAILURE, result.outcome, "двоичные байты в контексте модели — это мусор, а не чтение")
    }

    private companion object {

        /** Насколько файл больше лимита: нужен файл, который обрезается заведомо. */
        const val OVERSIZE = 4_096

        /** Запас на текст пометки об обрезке. */
        const val NOTE_MARGIN = 256
    }
}
