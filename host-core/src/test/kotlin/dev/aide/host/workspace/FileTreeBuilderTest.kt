package dev.aide.host.workspace

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTreeBuilderTest {

    private val fixture = TempRepoFixture()

    @AfterTest
    fun tearDown() = fixture.close()

    private fun builder(workspace: Workspace = Workspace.open(fixture.root)) =
        FileTreeBuilder(WorkspaceFileSystem(workspace), workspace)

    @Test
    fun `дерево содержит файлы и каталоги, отсортированные по пути`() {
        val tree = builder().build()
        val paths = tree.entries.map { it.path }
        assertEquals(paths.sorted(), paths, "Записи должны быть отсортированы")
        assertTrue(paths.contains("src/auth/Login.kt"))
        assertTrue(paths.contains("src/net/Api.kt"))
        assertTrue(paths.contains("docs/readme.md"))
        assertTrue(tree.entries.first { it.path == "src" }.isDirectory)
        assertFalse(tree.entries.first { it.path == "docs/readme.md" }.isDirectory)
        assertFalse(tree.truncated, "Маленькое дерево не должно считаться обрезанным")
        assertEquals(0, tree.skippedEntries, "Без обрезки пропущенных записей нет")
    }

    @Test
    fun `каталог git не попадает в дерево`() {
        fixture.root.resolve(".git").createDirectories()
        fixture.root.resolve(".git/HEAD").writeText("ref: refs/heads/master\n")
        val paths = builder().build().entries.map { it.path }
        assertFalse(paths.any { it == ".git" || it.startsWith(".git/") }, "Служебный каталог git не показывается")
    }

    @Test
    fun `каталоги из gitignore не попадают в дерево`() {
        // .gitignore в фикстуре содержит build/
        fixture.root.resolve("build").createDirectories()
        fixture.root.resolve("build/output.jar").writeText("binary")
        val paths = builder().build().entries.map { it.path }
        assertFalse(paths.any { it.startsWith("build/") }, "Игнорируемые каталоги не показываются")
    }

    @Test
    fun `симлинк наружу не попадает в дерево`() {
        val outside = Files.createTempDirectory("aide-outside-")
        try {
            outside.resolve("secret.txt").writeText("секрет")
            fixture.createEscapingSymlink("link-out", outside)
            val tree = builder().build()
            val paths = tree.entries.map { it.path }
            assertFalse(paths.any { it.startsWith("link-out") }, "Симлинк наружу не должен раскрывать содержимое")
            assertFalse(tree.truncated)
            assertEquals(0, tree.skippedEntries, "Отсечение симлинка — не обрезка по лимиту")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `лимит записей соблюдается и помечается`() {
        val many = fixture.root.resolve("many")
        many.createDirectories()
        repeat(30) { index -> many.resolve("file-$index.txt").writeText("x") }

        val tree = builder().let { FileTreeBuilder(it.fileSystem, it.workspace, maxEntries = 10) }.build()
        assertTrue(tree.truncated, "Дерево должно быть помечено как неполное")
        assertEquals(10, tree.entries.size)
        assertTrue(tree.skippedEntries > 0)
    }

    @Test
    fun `пустой репозиторий даёт пустое дерево без ошибки`() {
        val emptyRoot = Files.createTempDirectory("aide-empty-")
        try {
            val tree = builder(Workspace.open(emptyRoot)).build()
            assertTrue(tree.entries.isEmpty())
            assertFalse(tree.truncated)
            assertEquals(emptyRoot.toRealPath().toString(), tree.rootPath)
        } finally {
            emptyRoot.toFile().deleteRecursively()
        }
    }
}
