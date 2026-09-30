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

/** Ветка задачи из соглашения § 8.3: `ai/<task-id>`. */
private const val TASK_BRANCH = "ai/t-1"

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
    fun `переименование в рабочем каталоге совпадает с git status как удаление и новый файл`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        Files.move(root.resolve("src/Api.kt"), root.resolve("src/Renamed.kt"))

        // git не ищет переименования в рабочем каталоге: без индексации он видит удаление
        // старого файла и новый файл. Если бы хост сопоставлял переименования по рабочему
        // дереву, здесь он разошёлся бы с git status.
        assertEquals(
            listOf(" D src/Api.kt", " M src/Login.kt", "?? src/New.kt", "?? src/Renamed.kt"),
            GitCliFixture.porcelainLines(root),
        )

        JGitRepository.open(root).use { repository ->
            val changed = repository.changedFiles()
            assertEquals(expectedFromGitStatus(root), actualFromJGit(repository))
            assertEquals(FileChangeKind.DELETED, changed.single { it.path == "src/Api.kt" }.changeKind)
            assertEquals(FileChangeKind.ADDED, changed.single { it.path == "src/Renamed.kt" }.changeKind)
        }
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

    @Test
    fun `ветка задачи создаётся от HEAD, и рабочее дерево оказывается в ней`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        val headBefore = GitCliFixture.headShortHash(root)

        JGitRepository.open(root).use { repository ->
            val outcome = repository.ensureTaskBranch(TASK_BRANCH)

            assertEquals(TaskBranchOutcome.Created("master"), outcome, "базовой стала ветка, от которой ответвились")
            assertEquals(TASK_BRANCH, repository.currentBranch())
            assertEquals(TASK_BRANCH, GitCliFixture.currentBranch(root), "переключение видно и git")
        }

        // Ветка создана от HEAD, а не от чего-то другого, и незакоммиченные правки
        // пользователя переключение не потеряло: рабочее дерево осталось тем же.
        assertEquals(headBefore, GitCliFixture.headShortHash(root), "HEAD не сдвинулся")
        assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(root))
        assertEquals("fun login() = \"token\"\n", Files.readString(root.resolve("src/Login.kt")))
    }

    @Test
    fun `повторное обеспечение ветки не создаёт вторую и не трогает историю`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repository ->
            assertEquals(TaskBranchOutcome.Created("master"), repository.ensureTaskBranch(TASK_BRANCH))

            // Коммит шага (T-1.11) уже в ветке задачи: повторный запуск обязан его сохранить.
            GitCliFixture.run(listOf("git", "commit", "--allow-empty", "-m", "шаг 1"), root)
            val headAfterStep = GitCliFixture.headShortHash(root)

            assertEquals(TaskBranchOutcome.Existing, repository.ensureTaskBranch(TASK_BRANCH))

            assertEquals(headAfterStep, GitCliFixture.headShortHash(root), "ref ветки не переписан")
            assertEquals(listOf("шаг 1"), repository.commitLog(limit = LOG_LIMIT).take(1).map { it.message })
            assertEquals(
                listOf("refs/heads/$TASK_BRANCH", "refs/heads/master"),
                GitCliFixture.run(listOf("git", "for-each-ref", "--format=%(refname)", "refs/heads/"), root)
                    .output.lines().filter { it.isNotBlank() }.sorted(),
                "ветка задачи одна, второй не появилось",
            )
        }
    }

    @Test
    fun `репозиторий без коммитов отказывает, а не создаёт ветку`() {
        val root = GitCliFixture.createEmptyRepo(tempDir("aide-git-empty-"))
        JGitRepository.open(root).use { repository ->
            val outcome = assertIs<TaskBranchOutcome.Refused>(repository.ensureTaskBranch(TASK_BRANCH))

            assertEquals(TaskBranchRefusal.NO_COMMITS, outcome.reason)
            assertTrue(GitCliFixture.run(listOf("git", "branch", "--list"), root).output.isBlank(), "веток нет")
        }
    }

    @Test
    fun `отсоединённый HEAD отказывает — базовой ветки не существует`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "checkout", "--detach", "HEAD"), root)

        JGitRepository.open(root).use { repository ->
            val outcome = assertIs<TaskBranchOutcome.Refused>(repository.ensureTaskBranch(TASK_BRANCH))

            assertEquals(TaskBranchRefusal.DETACHED_HEAD, outcome.reason)
            assertEquals(GitCliFixture.headShortHash(root), repository.headCommit(), "HEAD остался на месте")
        }
    }

    @Test
    fun `репозиторий только для чтения отказывает до попытки писать`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        val gitDir = root.resolve(".git").toFile()

        JGitRepository.open(root).use { repository ->
            // Права снимаются у каталога .git: создание ветки пишет туда ссылку, и записать
            // её нельзя. Пользователь видит причину, а не строку JGit в журнале.
            check(gitDir.setWritable(false, false)) { "не удалось снять права на ${gitDir.path}" }
            try {
                val outcome = assertIs<TaskBranchOutcome.Refused>(repository.ensureTaskBranch(TASK_BRANCH))

                assertEquals(TaskBranchRefusal.READ_ONLY, outcome.reason)
            } finally {
                gitDir.setWritable(true, true)
            }
        }
    }

    @Test
    fun `сбой git при создании ветки даёт отказ, а не исключение`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        // Ветка `refs/heads/ai` — файл, поэтому `refs/heads/ai/<task-id>` существовать
        // не может: git отказывает в создании, и это тот же сбой, что даёт занятая
        // кем-то ссылка или правило правок пользователя.
        GitCliFixture.run(listOf("git", "branch", "ai"), root)

        JGitRepository.open(root).use { repository ->
            val outcome = assertIs<TaskBranchOutcome.Refused>(repository.ensureTaskBranch(TASK_BRANCH))

            assertEquals(TaskBranchRefusal.GIT_FAILED, outcome.reason)
            assertEquals("master", repository.currentBranch(), "прогон остался в прежней ветке")
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
