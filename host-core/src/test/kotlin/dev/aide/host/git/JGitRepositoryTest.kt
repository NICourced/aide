package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

private const val LOG_LIMIT = 10
private const val MILLIS_PER_SECOND = 1_000L

class JGitRepositoryTest {

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `текущая ветка совпадает с выводом git`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repository ->
            assertEquals("master", GitCliFixture.currentBranch(root))
            assertEquals(GitCliFixture.currentBranch(root), repository.currentBranch())
        }
    }

    @Test
    fun `список изменённых файлов совпадает с git status`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        // Коды git на этой фикстуре зафиксированы явно: без этого совпадение списков
        // не доказывало бы, что разобраны именно «изменён в рабочем каталоге» и «вне индекса».
        assertEquals(
            listOf(" M src/Login.kt", "?? src/New.kt"),
            GitCliFixture.porcelainLines(root),
        )
        assertEquals(
            listOf("src/Login.kt" to FileChangeKind.MODIFIED, "src/New.kt" to FileChangeKind.ADDED),
            expectedFromGitStatus(root),
        )

        JGitRepository.open(root).use { repository ->
            assertEquals(expectedFromGitStatus(root), actualFromJGit(repository))
        }
    }

    @Test
    fun `изменения в индексе совпадают с git status`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "add", "-A"), root)

        assertEquals(
            listOf("M  src/Login.kt", "A  src/New.kt"),
            GitCliFixture.porcelainLines(root),
        )
        assertEquals(
            listOf("src/Login.kt" to FileChangeKind.MODIFIED, "src/New.kt" to FileChangeKind.ADDED),
            expectedFromGitStatus(root),
        )

        JGitRepository.open(root).use { repository ->
            assertEquals(expectedFromGitStatus(root), actualFromJGit(repository))
        }
    }

    @Test
    fun `история коммитов совпадает с git log`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repository ->
            val commits = repository.commitLog(limit = LOG_LIMIT)

            assertEquals(listOf("второй коммит", "первый коммит"), commits.map { it.message })
            assertEquals(GitCliFixture.logField(root, "%s", LOG_LIMIT), commits.map { it.message })
            assertEquals(GitCliFixture.logField(root, "%H", LOG_LIMIT), commits.map { it.hash })
            assertEquals(GitCliFixture.logField(root, "%h", LOG_LIMIT), commits.map { it.shortHash })
            assertEquals(GitCliFixture.logField(root, "%an <%ae>", LOG_LIMIT), commits.map { it.author })
            assertEquals(
                GitCliFixture.logField(root, "%ct", LOG_LIMIT).map { it.toLong() * MILLIS_PER_SECOND },
                commits.map { it.committedAtEpochMillis },
            )
        }
    }

    @Test
    fun `короткий хеш HEAD непустой и совпадает с git rev-parse`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repository ->
            assertTrue(repository.headCommit().isNotEmpty())
            assertEquals(GitCliFixture.headShortHash(root), repository.headCommit())
        }
    }

    @Test
    fun `репозиторий без коммитов не падает и отдаёт пустую историю`() {
        val root = GitCliFixture.createEmptyRepo(tempDir("aide-git-empty-"))
        JGitRepository.open(root).use { repository ->
            assertEquals(GitCliFixture.currentBranch(root), repository.currentBranch())
            assertEquals("master", repository.currentBranch())
            assertTrue(repository.commitLog(limit = LOG_LIMIT).isEmpty())
            assertEquals("", repository.headCommit(), "У репозитория без коммитов нет HEAD")
            assertTrue(repository.changedFiles().isEmpty())
        }
    }

    @Test
    fun `каталог без git даёт типизированную ошибку`() {
        val root = tempDir("aide-nogit-")
        val error = assertFailsWith<GitAccessException> { JGitRepository.open(root) }
        val typed = assertIs<ProtocolError.NotAGitRepository>(error.error)
        assertEquals(root.toString(), typed.path)
    }

    @Test
    fun `переименование и удаление различаются и совпадают с git status`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "mv", "src/Api.kt", "src/Renamed.kt"), root)
        // Удаляем отслеживаемый файл: удаление файла вне индекса git статусом не показывается вовсе.
        Files.delete(root.resolve("src/Login.kt"))

        assertEquals(
            listOf(" D src/Login.kt", "R  src/Api.kt -> src/Renamed.kt", "?? src/New.kt"),
            GitCliFixture.porcelainLines(root),
        )

        JGitRepository.open(root).use { repository ->
            val changed = repository.changedFiles()
            assertEquals(expectedFromGitStatus(root), actualFromJGit(repository))

            val renamed = changed.single { it.path == "src/Renamed.kt" }
            assertEquals(FileChangeKind.RENAMED, renamed.changeKind)
            assertEquals("src/Api.kt", renamed.previousPath)

            val deleted = changed.single { it.path == "src/Login.kt" }
            assertEquals(FileChangeKind.DELETED, deleted.changeKind)
            assertEquals(null, deleted.previousPath)
        }
    }

    @Test
    fun `переименование в git status читается с учётом суффикса со стрелкой`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "mv", "src/Api.kt", "src/Renamed.kt"), root)

        // Строка переименования в `--porcelain=v1` выглядит как `R  старый путь -> новый путь`.
        assertEquals(
            "R  src/Api.kt -> src/Renamed.kt",
            GitCliFixture.porcelainLines(root).single { it.startsWith("R") },
        )
        val rename = GitCliFixture.status(root).single { it.code == "R" }
        assertEquals("src/Renamed.kt", rename.path)
        assertEquals("src/Api.kt", rename.previousPath)

        JGitRepository.open(root).use { repository ->
            val renamed = repository.changedFiles().single { it.path == rename.path }
            assertEquals(FileChangeKind.RENAMED, renamed.changeKind)
            assertEquals(rename.previousPath, renamed.previousPath)
        }
    }

    /** Изменения по данным JGit в том же виде, в каком их даёт разбор `git status`. */
    private fun actualFromJGit(repository: GitRepository): List<Pair<String, FileChangeKind>> =
        repository.changedFiles().map { it.path to it.changeKind }.sortedBy { it.first }

    /**
     * Ожидаемые изменения по выводу `git status`.
     *
     * Сравнение идёт по тем кодам, которые git печатает на фикстуре; неизвестный код роняет
     * тест, а не превращается в строку, которая заведомо не совпадёт с элементом домена.
     */
    private fun expectedFromGitStatus(root: Path): List<Pair<String, FileChangeKind>> =
        GitCliFixture.status(root).map { entry ->
            val kind = when (entry.code) {
                "??", "A" -> FileChangeKind.ADDED
                "M" -> FileChangeKind.MODIFIED
                "D" -> FileChangeKind.DELETED
                "R" -> FileChangeKind.RENAMED
                else -> throw AssertionError("неизвестный код git status «${entry.code}» для ${entry.path}")
            }
            entry.path to kind
        }.sortedBy { it.first }
}
