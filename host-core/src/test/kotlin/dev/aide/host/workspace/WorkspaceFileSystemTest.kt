package dev.aide.host.workspace

import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceFileSystemTest {

    private val fixture = TempRepoFixture()
    private val workspace = Workspace.open(fixture.root)
    private val fs = WorkspaceFileSystem(workspace)

    @AfterTest
    fun tearDown() = fixture.close()

    @Test
    fun `обычный файл читается`() {
        val content = fs.readFile("src/auth/Login.kt")
        assertEquals("fun login() = Unit\n", content.text)
        assertEquals("kotlin", content.language)
    }

    @Test
    fun `подъём по каталогам за пределы воркспейса запрещён`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("../outside.txt") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `подъём по каталогам к существующему файлу вне корня запрещён`() {
        val sibling = Files.createTempFile(fixture.root.parent, "aide-secret-", ".txt")
        sibling.writeText("секрет\n")
        assertTrue(Files.isReadable(sibling), "Цель атаки должна быть реально читаемой — иначе проверка вырождена")
        try {
            val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("../${sibling.fileName}") }
            assertIs<ProtocolError.AccessDenied>(error.error)
        } finally {
            Files.deleteIfExists(sibling)
        }
    }

    @Test
    fun `вложенный подъём по каталогам запрещён`() {
        assertTrue(Files.isReadable(Path.of("/etc/passwd")), "Цель атаки должна быть читаемой")
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/../../../etc/passwd") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `абсолютный путь вне корня запрещён`() {
        assertTrue(Files.isReadable(Path.of("/etc/passwd")), "Цель атаки должна быть читаемой")
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("/etc/passwd") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `симлинк, ведущий наружу, запрещён`() {
        val outside = Files.createTempFile("aide-outside-", ".txt")
        outside.writeText("секрет\n")
        assertTrue(Files.isReadable(outside), "Цель симлинка должна быть реально читаемой — иначе атака вырождена")
        try {
            fixture.createEscapingSymlink("link-out.txt", outside)
            val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("link-out.txt") }
            val denied = assertIs<ProtocolError.AccessDenied>(error.error)
            assertTrue(denied.reason.contains("симлинк", ignoreCase = true))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `симлинк внутрь воркспейса разрешён`() {
        Files.createSymbolicLink(fixture.root.resolve("link-in.kt"), fixture.root.resolve("src/auth/Login.kt"))
        assertEquals("fun login() = Unit\n", fs.readFile("link-in.kt").text)
    }

    @Test
    fun `отсутствующий файл даёт notFound, а не ошибку доступа`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/NoSuchFile.kt") }
        assertIs<ProtocolError.NotFound>(error.error)
    }

    @Test
    fun `каталог вместо файла даёт понятную ошибку`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth") }
        val notFound = assertIs<ProtocolError.NotFound>(error.error)
        assertTrue(notFound.what.contains("каталог", ignoreCase = true))
    }

    @Test
    fun `файл больше лимита обрезается с пометкой`() {
        val limit = WorkspaceFileSystem.MAX_DISPLAY_BYTES
        fixture.createLargeFile("big.txt", bytes = limit + 1_024)
        val content = fs.readFile("big.txt")
        assertTrue(content.truncated, "Файл больше лимита должен быть помечен как обрезанный")
        assertEquals(limit.toLong(), content.sizeBytes, "sizeBytes — это размер отданного текста")
        assertEquals(limit, content.text.length, "Отданный текст обрезан ровно по лимиту")
        assertTrue(content.sizeBytes < limit + 1_024L, "Отдано меньше, чем лежит в файле")
    }

    @Test
    fun `файл ровно по лимиту не считается обрезанным`() {
        val limit = WorkspaceFileSystem.MAX_DISPLAY_BYTES
        fixture.createLargeFile("exact.txt", bytes = limit)
        val content = fs.readFile("exact.txt")
        assertFalse(content.truncated, "Файл ровно по лимиту не обрезан")
        assertEquals(limit.toLong(), content.sizeBytes)
        assertEquals(limit, content.text.length)
    }

    @Test
    fun `UTF-8 с кириллицей читается как текст`() {
        fixture.root.resolve("src/auth/Text.kt").writeText("// привет, мир\nval еж = \"ёж\"\n")
        val content = fs.readFile("src/auth/Text.kt")
        assertEquals("// привет, мир\nval еж = \"ёж\"\n", content.text)
        assertEquals("kotlin", content.language)
        assertFalse(content.truncated)
    }

    @Test
    fun `файл не в UTF-8 не показывается как текст`() {
        val file = fixture.root.resolve("src/auth/Latin1.kt")
        Files.write(file, byteArrayOf(0xC0.toByte(), 0xC1.toByte(), 0x0A))
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/Latin1.kt") }
        assertIs<ProtocolError.NotImplemented>(error.error)
    }

    @Test
    fun `бинарный файл не показывается как текст`() {
        fixture.createBinaryFile("logo.png")
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("logo.png") }
        assertIs<ProtocolError.NotImplemented>(error.error)
    }

    @Test
    fun `файл без прав на чтение даёт ошибку доступа`() {
        val file = fixture.createUnreadableFile("src/auth/Secret.kt")
        assertFalse(
            Files.isReadable(file),
            "Тест требует непривилегированного пользователя: под root права на файл игнорируются",
        )
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/Secret.kt") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `листинг каталога остаётся внутри воркспейса`() {
        val entries = fs.listChildren("src")
        assertEquals(listOf("src/auth", "src/net"), entries.map { it.path }.sorted())
        assertTrue(entries.all { it.isDirectory })
    }

    @Test
    fun `определение языка по расширению`() {
        assertEquals("kotlin", LanguageDetector.detect("a/b/Main.kt"))
        assertEquals("markdown", LanguageDetector.detect("docs/readme.md"))
        assertEquals("yaml", LanguageDetector.detect("ci.yml"))
        assertEquals(null, LanguageDetector.detect("Dockerfile"))
    }
}
