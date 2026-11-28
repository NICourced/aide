package dev.aide.host.git

import dev.aide.host.diff.TextDiffInput
import dev.aide.host.workspace.TempRepoFixture
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Чтение diff'а между базовой и задачной ветками для сборки пакета изменений (T-1.24).
 *
 * Проверка идёт на реальном git-репозитории: merge-base, деревья коммитов и чтение blob'ов
 * — это не то, что можно проверить на моках, не повторив реализацию.
 */
class PacketDiffOperationTest {

    private val repo = TempRepoFixture()

    @AfterTest
    fun tearDown() {
        repo.close()
    }

    @Test
    fun `changeSet содержит merge-base и HEAD задачной ветки`() {
        val root = repo.root
        GitCliFixture.createRepo(root)
        val masterHead = GitCliFixture.headHash(root)

        GitCliFixture.run(listOf("git", "checkout", "-b", "ai/task"), root)
        root.resolve("src/Login.kt").writeText("fun login(token: String) = Unit\n")
        GitCliFixture.run(listOf("git", "add", "src/Login.kt"), root)
        GitCliFixture.run(listOf("git", "commit", "-m", "правка в ветке задачи"), root)
        val taskHead = GitCliFixture.headHash(root)

        val jgit = JGitRepository.open(root)
        jgit.use {
            val changeSet = it.changeSets.read("master", "ai/task")

            assertEquals(masterHead, changeSet.baseCommit, "baseCommit — merge-base с master")
            assertEquals(taskHead, changeSet.headCommit, "headCommit — HEAD задачной ветки")
            assertEquals(1, changeSet.files.size, "один файл изменился")
        }
    }

    @Test
    fun `changeSet включает переименованный файл`() {
        val root = repo.root
        GitCliFixture.createRepo(root)
        GitCliFixture.run(listOf("git", "checkout", "-b", "ai/task"), root)
        GitCliFixture.run(listOf("git", "mv", "src/Login.kt", "src/Auth.kt"), root)
        GitCliFixture.run(listOf("git", "commit", "-m", "переименование"), root)

        JGitRepository.open(root).use {
            val changeSet = it.changeSets.read("master", "ai/task")

            assertEquals(1, changeSet.files.size)
            val input = changeSet.files.single()
            assertEquals("src/Auth.kt", input.newPath)
            assertEquals("src/Login.kt", input.oldPath)
        }
    }

    @Test
    fun `readContext читает строки из blob'а закреплённого коммита`() {
        val root = repo.root
        GitCliFixture.createRepo(root)
        val masterHead = GitCliFixture.headHash(root)

        JGitRepository.open(root).use {
            val lines = it.changeSets.readContext(masterHead, "src/Login.kt", startLine = 1, lineCount = 2)

            assertEquals(1, lines.size)
            assertEquals(1, lines.single().number)
            assertEquals("fun login() = Unit", lines.single().text)
        }
    }

    @Test
    fun `readContext возвращает пустой список за пределами файла`() {
        val root = repo.root
        GitCliFixture.createRepo(root)
        val masterHead = GitCliFixture.headHash(root)

        JGitRepository.open(root).use {
            val lines = it.changeSets.readContext(masterHead, "src/Login.kt", startLine = 10, lineCount = 5)

            assertTrue(lines.isEmpty())
        }
    }
}
