package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import org.eclipse.jgit.lib.RefUpdate

private const val LOG_LIMIT = 10
private const val MILLIS_PER_SECOND = 1_000L

/** Ветка задачи из соглашения § 8.3: `ai/<task-id>`. */
private const val TASK_BRANCH = "ai/t-1"

/** Метка времени снапшота из T-1.19: миллисекунды от эпохи. */
private const val SNAPSHOT_MILLIS = 1_758_535_200_000L

/** Повод снапшота в имени ссылки. */
private const val LABEL = "before-agent-step"

/** Ссылка на отложенные правки пользователя из соглашения T-1.59. */
private const val STASH_REF = "refs/ai/stash/t-1"

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

    @Test
    fun `снапшот указывает на HEAD и не появляется в списке веток`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        val headBefore = GitCliFixture.headHash(root)

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            // Снапшот — ссылка на уже существующий коммит: ни нового коммита, ни сдвига
            // HEAD, ни правки рабочего дерева.
            assertEquals(headBefore, GitCliFixture.hashOf(root, ref), "ссылка обязана указывать на HEAD")
            assertEquals(headBefore, GitCliFixture.headHash(root), "HEAD не сдвинулся")
            assertEquals(
                listOf("второй коммит", "первый коммит"),
                repository.commitLog(limit = LOG_LIMIT).map { it.message },
                "снапшот не создаёт коммит",
            )
            assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(root))

            // Главное требование § 9: снапшот есть в скрытом пространстве имён, но его нет
            // в обычном списке веток — ни у git, ни, значит, в перечислении веток интерфейса.
            assertEquals(listOf(ref), GitCliFixture.snapshotRefs(root))
            assertEquals(listOf("refs/heads/master"), GitCliFixture.refNames(root, "refs/heads/"))
            assertEquals("* master", GitCliFixture.branchList(root), "снапшот не ветка")
        }
    }

    @Test
    fun `ссылки снапшотов перечисляются в порядке создания`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        JGitRepository.open(root).use { repository ->
            val older = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            val newer = snapshotRefName(SNAPSHOT_MILLIS + MILLIS_PER_SECOND, "after-agent-step")
            // Ставим в обратном порядке: порядок задаёт метка времени в имени, а не порядок вызовов.
            repository.createSnapshot(newer)
            repository.createSnapshot(older)

            assertEquals(listOf(older, newer), repository.snapshotRefs())
        }
    }

    @Test
    fun `удаление снапшота убирает ссылку, но не историю`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            repository.createSnapshot(ref)
            val headBefore = GitCliFixture.headHash(root)

            repository.deleteSnapshots(listOf(ref))

            assertTrue(GitCliFixture.snapshotRefs(root).isEmpty(), "ссылки нет ни в JGit, ни в git")
            assertTrue(repository.snapshotRefs().isEmpty())
            assertEquals(headBefore, GitCliFixture.headHash(root), "коммит остался в истории")
            assertEquals(
                listOf("второй коммит", "первый коммит"),
                repository.commitLog(limit = LOG_LIMIT).map { it.message },
            )
        }
    }

    @Test
    fun `репозиторий без коммитов снапшота не даёт, но не падает`() {
        val root = GitCliFixture.createEmptyRepo(tempDir("aide-git-empty-"))

        JGitRepository.open(root).use { repository ->
            // «Ссылаться не на что» — состояние репозитория, а не сбой: прогон без коммитов
            // должен читать и планировать, а не падать из-за отсутствия снапшота.
            assertEquals(SnapshotOutcome.NoHead, repository.createSnapshot(snapshotRefName(SNAPSHOT_MILLIS, LABEL)))
            assertTrue(repository.snapshotRefs().isEmpty())
        }
    }

    @Test
    fun `сбой записи ссылки даёт отказ, а не исключение`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        // Права снимаются у каталога `refs`: ссылка снапшота пишется в `refs/ai/snap/`,
        // а создание новых каталогов под `refs` без записи в него невозможно. Снятия прав
        // с самого `.git` мало — запись глубже по дереву его не требует.
        val refsDir = root.resolve(".git/refs").toFile()
        val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)

        JGitRepository.open(root).use { repository ->
            // Ссылку записать некуда: это состояние репозитория, а не сбой чтения — движку
            // нужен отказ-значение, а не исключение, иначе прогон останется `PLANNED`,
            // а задача — `RUNNING` без причины.
            check(refsDir.setWritable(false, false)) { "не удалось снять права на ${refsDir.path}" }
            try {
                assertEquals(SnapshotOutcome.Refused, repository.createSnapshot(ref))
            } finally {
                refsDir.setWritable(true, true)
            }
        }

        assertEquals(emptyList(), GitCliFixture.snapshotRefs(root), "ссылка не появилась")
    }

    @Test
    fun `неудачный исход удаления ссылки не считается успехом`() {
        // JGit сообщает о неудаче удаления значением, а не исключением: без этой проверки
        // ссылки копились бы, а лимит 50 соблюдался бы только на бумаге (T-1.19).
        val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)

        // Ссылки не было — она уже отсутствует; ссылка была — удалена.
        requireDeleted(ref, RefUpdate.Result.NEW)
        requireDeleted(ref, RefUpdate.Result.FORCED)

        listOf(
            RefUpdate.Result.LOCK_FAILURE,
            RefUpdate.Result.REJECTED,
            RefUpdate.Result.IO_FAILURE,
            RefUpdate.Result.NOT_ATTEMPTED,
            RefUpdate.Result.REJECTED_OTHER_REASON,
        ).forEach { result ->
            assertFailsWith<GitAccessException>("исход $result означает, что ссылка могла остаться") {
                requireDeleted(ref, result)
            }
        }
    }

    @Test
    fun `правки откладываются целиком, включая новые файлы`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        val head = GitCliFixture.headHash(root)

        JGitRepository.open(root).use { repository ->
            val outcome = assertIs<StashOutcome.Stashed>(repository.workStash.stashEdits(STASH_REF, "ai/t-1"))

            assertEquals(STASH_REF, outcome.ref)
            assertEquals("master", outcome.branch, "правки были на текущей ветке — её и надо запомнить")
            assertTrue(GitCliFixture.porcelainLines(root).isEmpty(), "дерево обязано стать чистым")
            assertEquals(
                "fun login() = Unit\n",
                Files.readString(root.resolve("src/Login.kt")),
                "правка человека уехала в сторону, а не осталась в дереве",
            )
            assertFalse(Files.exists(root.resolve("src/New.kt")), "новый файл — такая же правка человека")
            assertEquals(head, GitCliFixture.headHash(root), "HEAD не сдвинулся")
            assertEquals(listOf(STASH_REF), GitCliFixture.refNames(root, STASH_REF_PREFIX), "отложенное — ссылка")
        }
    }

    @Test
    fun `чистое дерево — откладывать нечего`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        // Правки фикстуры принимаются коммитом: дерево становится чистым, откладывать нечего.
        GitCliFixture.run(listOf("git", "add", "-A"), root)
        GitCliFixture.run(listOf("git", "commit", "-m", "правки приняты"), root)

        JGitRepository.open(root).use { repository ->
            assertEquals(StashOutcome.Nothing, repository.workStash.stashEdits(STASH_REF, "ai/t-1"))
            assertTrue(GitCliFixture.refNames(root, STASH_REF_PREFIX).isEmpty(), "лишней ссылки не появилось")
        }
    }

    @Test
    fun `репозиторий без коммитов правки не откладывает`() {
        val root = GitCliFixture.createEmptyRepo(tempDir("aide-git-empty-"))
        Files.writeString(root.resolve("New.kt"), "class New\n")

        JGitRepository.open(root).use { repository ->
            // Ссылаться не на что: откладывать некуда, и файл остаётся на месте — прогон
            // дальше сам откажет по ветке (`NO_COMMITS`), но правки не пропадут.
            assertEquals(StashOutcome.Nothing, repository.workStash.stashEdits(STASH_REF, "ai/t-1"))
            assertTrue(Files.exists(root.resolve("New.kt")))
        }
    }

    @Test
    fun `возврат ставит правки на прежнюю ветку и убирает ссылку`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        JGitRepository.open(root).use { repository ->
            repository.workStash.stashEdits(STASH_REF, "ai/t-1")
            // Ветка задачи: агент работает в ней, дерево при этом чистое.
            repository.ensureTaskBranch(TASK_BRANCH)
            assertTrue(GitCliFixture.porcelainLines(root).isEmpty())

            assertEquals(StashReturnOutcome.Returned, repository.workStash.returnStashEdits(STASH_REF, "master"))

            assertEquals("master", repository.currentBranch(), "правки вернулись туда, где были")
            assertEquals(listOf(" M src/Login.kt", "?? src/New.kt"), GitCliFixture.porcelainLines(root))
            assertEquals("fun login() = \"token\"\n", Files.readString(root.resolve("src/Login.kt")))
            assertTrue(GitCliFixture.refNames(root, STASH_REF_PREFIX).isEmpty(), "ссылка убрана после успеха")
        }
    }

    @Test
    fun `конфликт возврата сохраняет отложенное и оставляет метки конфликта`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        JGitRepository.open(root).use { repository ->
            repository.workStash.stashEdits(STASH_REF, "ai/t-1")
            repository.ensureTaskBranch(TASK_BRANCH)
            // Агент правит тот же файл, что и человек: возврат на ветку задачи конфликтует.
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")
            GitCliFixture.run(listOf("git", "add", "src/Login.kt"), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "шаг агента"), root)

            assertEquals(StashReturnOutcome.Conflict, repository.workStash.returnStashEdits(STASH_REF, TASK_BRANCH))

            assertEquals(
                listOf(STASH_REF),
                GitCliFixture.refNames(root, STASH_REF_PREFIX),
                "при конфликте отложенное не теряется: ссылка остаётся",
            )
            val content = Files.readString(root.resolve("src/Login.kt"))
            assertTrue(content.contains("<<<<<<<"), "конфликт показан метками, а не проглочен")
            assertTrue(content.contains("\"token\""), "правка человека в конфликте присутствует")
        }
    }

    @Test
    fun `возврат без ссылки — отказ, а не тихий успех`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))

        JGitRepository.open(root).use { repository ->
            // Ссылки нет: отложенное потеряно, и молчать об этом нельзя.
            assertEquals(StashReturnOutcome.Refused, repository.workStash.returnStashEdits(STASH_REF, "master"))
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
