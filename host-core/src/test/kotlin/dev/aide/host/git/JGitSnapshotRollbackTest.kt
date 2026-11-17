package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/** Метка времени первого снапшота. */
private const val FIRST_MILLIS = 1_758_535_200_000L

/** Метка времени второго снапшота. */
private const val SECOND_MILLIS = 1_758_535_300_000L

/** Метка времени третьего снапшота. */
private const val THIRD_MILLIS = 1_758_535_400_000L

/** Повод снапшота. */
private const val LABEL = "before-agent-step"

/**
 * T-1.6: откат к снапшоту с предпросмотром.
 *
 * Репозиторий настоящий: `diff`, `commit` и `reset` — операции git, и проверять их
 * на заглушке значило бы проверять заглушку.
 */
class JGitSnapshotRollbackTest {

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(prefix: String): Path {
        val dir = Files.createTempDirectory(prefix)
        tempDirs.add(dir)
        return dir
    }

    /** Фикстура оставляет незакоммиченные правки; для снапшота дерево должно быть чистым. */
    private fun commitFixture(root: Path) {
        commitAll(root, "фикстура")
    }

    private fun commitAll(root: Path, message: String) {
        val add = GitCliFixture.run(listOf("git", "add", "-A"), root)
        check(add.exitCode == 0) { "git add failed: ${add.output}" }
        val commit = GitCliFixture.run(listOf("git", "commit", "-m", message), root)
        check(commit.exitCode == 0) { "git commit failed: ${commit.output}" }
    }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `список снапшотов отсортирован от новых к старым и содержит метаданные`() {
        val root = tempDir("aide-rollback-list-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val firstRef = snapshotRefName(FIRST_MILLIS, LABEL)
            val secondRef = snapshotRefName(SECOND_MILLIS, "after-agent-step")
            val thirdRef = snapshotRefName(THIRD_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(firstRef))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(secondRef))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(thirdRef))

            val snapshots = repository.rollback.list()

            assertEquals(listOf(thirdRef, secondRef, firstRef), snapshots.map { it.ref.value })
            assertEquals(
                listOf(LABEL, "after-agent-step", LABEL),
                snapshots.map { it.label },
            )
            assertEquals(
                listOf(
                    SnapshotTrigger.BEFORE_AGENT_STEP,
                    SnapshotTrigger.AFTER_AGENT_STEP,
                    SnapshotTrigger.BEFORE_AGENT_STEP,
                ),
                snapshots.map { it.trigger },
            )
            snapshots.forEach { assertEquals(GitCliFixture.hashOf(root, it.ref.value), it.commit) }
        }
    }

    @Test
    fun `список не содержит чужие ссылки`() {
        val root = tempDir("aide-rollback-foreign-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            GitCliFixture.run(listOf("git", "tag", "v1"), root)
            GitCliFixture.run(listOf("git", "branch", "feature"), root)

            val snapshots = repository.rollback.list()

            assertEquals(listOf(ref), snapshots.map { it.ref.value })
        }
    }

    @Test
    fun `список не содержит снапшоты из другой ветки`() {
        val root = tempDir("aide-rollback-other-branch-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            GitCliFixture.run(listOf("git", "checkout", "-b", "feature"), root)
            root.resolve("src/Feature.kt").writeText("class Feature\n")
            commitAll(root, "ветка feature")
            val featureRef = snapshotRefName(SECOND_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(featureRef))
            GitCliFixture.run(listOf("git", "checkout", "master"), root)

            val snapshots = repository.rollback.list()

            assertEquals(listOf(ref), snapshots.map { it.ref.value })
        }
    }

    @Test
    fun `список пропускает снапшоты с неизвестной меткой`() {
        val root = tempDir("aide-rollback-unknown-label-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val knownRef = snapshotRefName(FIRST_MILLIS, LABEL)
            val unknownRef = snapshotRefName(SECOND_MILLIS, "unknown-label")
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(knownRef))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(unknownRef))

            val snapshots = repository.rollback.list()

            assertEquals(listOf(knownRef), snapshots.map { it.ref.value })
        }
    }

    @Test
    fun `предпросмотр изменения показывает файл и объемы строк`() {
        val root = tempDir("aide-rollback-modify-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Login.kt").writeText("fun login(): String = \"token\"\n")
            commitAll(root, "правка")

            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value

            assertEquals(GitCliFixture.headHash(root), preview.headCommit)
            assertEquals(1, preview.files.size)
            val change = preview.files.single()
            assertEquals("src/Login.kt", change.path)
            assertEquals(FileChangeKind.MODIFIED, change.changeKind)
            assertEquals(1, change.addedLines)
            assertEquals(1, change.removedLines)
        }
    }

    @Test
    fun `предпросмотр добавления показывает файл и нулевое удаление`() {
        val root = tempDir("aide-rollback-add-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Another.kt").writeText("class Another\n")
            commitAll(root, "добавление")

            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value

            val change = preview.files.single()
            assertEquals("src/Another.kt", change.path)
            assertEquals(FileChangeKind.ADDED, change.changeKind)
            assertEquals(1, change.addedLines)
            assertEquals(0, change.removedLines)
        }
    }

    @Test
    fun `предпросмотр удаления показывает файл и нулевое добавление`() {
        val root = tempDir("aide-rollback-delete-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.delete(root.resolve("src/Login.kt"))
            commitAll(root, "удаление")

            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value

            val change = preview.files.single()
            assertEquals("src/Login.kt", change.path)
            assertEquals(FileChangeKind.DELETED, change.changeKind)
            assertEquals(0, change.addedLines)
            assertEquals(1, change.removedLines)
        }
    }

    @Test
    fun `предпросмотр переименования показывает оба пути`() {
        val root = tempDir("aide-rollback-rename-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.move(root.resolve("src/Login.kt"), root.resolve("src/Auth.kt"))
            commitAll(root, "переименование")

            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value

            val change = preview.files.single()
            assertEquals("src/Auth.kt", change.path)
            assertEquals("src/Login.kt", change.previousPath)
            assertEquals(FileChangeKind.RENAMED, change.changeKind)
        }
    }

    @Test
    fun `успешный откат создает обратный коммит и возвращает дерево к снапшоту`() {
        val root = tempDir("aide-rollback-revert-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Login.kt").writeText("fun login() = \"agent\"\n")
            root.resolve("src/Generated.kt").writeText("class Generated\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "Шаг 1: правка"), root)
            root.resolve("src/Api.kt").writeText("class ApiV2\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "Шаг 2: ещё правка"), root)
            val headBefore = GitCliFixture.headHash(root)
            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value

            val outcome = repository.rollback.revert(SnapshotRef(ref), preview.headCommit)

            val committed = assertIs<SnapshotRollbackOutcome.Success<SnapshotRevertCommitted>>(outcome).value
            assertEquals(committed.commit, GitCliFixture.headHash(root))
            assertEquals(
                GitCliFixture.hashOf(root, "$ref^{tree}"),
                GitCliFixture.hashOf(root, committed.commit + "^{tree}"),
                "дерево нового HEAD совпадает с деревом снапшота",
            )
            assertNotEquals(headBefore, committed.commit)
            val log = GitCliFixture.logField(root, "%H %P %s", limit = 3)
            val revertLine = log.first()
            assertTrue(revertLine.startsWith(committed.commit), "новый коммит наверху")
            assertTrue(revertLine.contains(headBefore), "прежний HEAD — родитель обратного коммита")
            assertTrue(revertLine.contains("Откат к снапшоту"), "сообщение содержит признак отката")
            val author = GitCliFixture.logField(root, "%an <%ae>", limit = 1).single()
            assertEquals("Aide Test <test@aide.dev>", author)
            assertEquals(listOf(ref), GitCliFixture.snapshotRefs(root), "ссылка снапшота сохранена")
        }
    }

    @Test
    fun `откат не выполняется при грязном рабочем дереве`() {
        val root = tempDir("aide-rollback-dirty-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Login.kt").writeText("fun login() = \"dirty\"\n")

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.DIRTY_WORKTREE,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
            assertEquals(
                "fun login() = \"dirty\"\n",
                Files.readString(root.resolve("src/Login.kt")),
            )
        }
    }

    @Test
    fun `откат не выполняется по несуществующей ссылке`() {
        val root = tempDir("aide-rollback-unknown-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            val outcome = repository.rollback.revert(
                SnapshotRef(snapshotRefName(FIRST_MILLIS, LABEL)),
                GitCliFixture.headHash(root),
            )
            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат не выполняется по ссылке с чужим префиксом`() {
        val root = tempDir("aide-rollback-wrong-prefix-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            val outcome = repository.rollback.revert(
                SnapshotRef("refs/heads/master"),
                GitCliFixture.headHash(root),
            )
            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат не выполняется если снапшот не предок HEAD`() {
        val root = tempDir("aide-rollback-non-ancestor-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            GitCliFixture.run(listOf("git", "checkout", "--orphan", "other"), root)
            root.resolve("src/Other.kt").writeText("class Other\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "другая ветка"), root)

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.NOT_IN_CURRENT_BRANCH,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат не выполняется если HEAD изменился с момента предпросмотра`() {
        val root = tempDir("aide-rollback-head-changed-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Login.kt").writeText("fun login() = \"agent\"\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "шаг"), root)
            val oldHead = GitCliFixture.headHash(root)
            root.resolve("src/Login.kt").writeText("fun login() = \"changed\"\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "ещё шаг"), root)

            val outcome = repository.rollback.revert(SnapshotRef(ref), oldHead)

            assertEquals(
                SnapshotRollbackRefusal.HEAD_CHANGED,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
            assertFalse(Files.readString(root.resolve("src/Login.kt")).contains("token"))
        }
    }

    @Test
    fun `откат не выполняется если дерево снапшота совпадает с деревом HEAD`() {
        val root = tempDir("aide-rollback-nothing-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.NOTHING_TO_REVERT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `если hook бросает перед reset, HEAD не сдвигается`() {
        val root = tempDir("aide-rollback-hook-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Login.kt").writeText("fun login() = \"agent\"\n")
            GitCliFixture.run(listOf("git", "add", "."), root)
            GitCliFixture.run(listOf("git", "commit", "-m", "шаг"), root)
            val headBefore = GitCliFixture.headHash(root)
            val preview = assertIs<SnapshotRollbackOutcome.Success<SnapshotPreview>>(
                repository.rollback.preview(SnapshotRef(ref)),
            ).value
            val operation = repository.rollback as SnapshotRollbackOperation
            operation.beforeResetHook = { error("сбой перед reset") }

            val outcome = repository.rollback.revert(SnapshotRef(ref), preview.headCommit)

            assertEquals(
                SnapshotRollbackRefusal.GIT_FAILED,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
            assertEquals(headBefore, GitCliFixture.headHash(root), "HEAD остался прежним")
            val log = GitCliFixture.logField(root, "%H", limit = 1)
            assertEquals(listOf(headBefore), log, "обратный коммит не попал в историю")
        }
    }

    @Test
    fun `предпросмотр отказывает по снапшоту с неизвестной меткой`() {
        val root = tempDir("aide-rollback-unknown-label-preview-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, "unknown-label")
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.preview(SnapshotRef(ref))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат отказывает по снапшоту с неизвестной меткой`() {
        val root = tempDir("aide-rollback-unknown-label-revert-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = snapshotRefName(FIRST_MILLIS, "unknown-label")
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `список пропускает malformed ссылки`() {
        val root = tempDir("aide-rollback-malformed-list-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val validRef = snapshotRefName(FIRST_MILLIS, LABEL)
            val malformedNoSeparator = "$SNAPSHOT_REF_PREFIX$FIRST_MILLIS"
            val missingSeparator = "$SNAPSHOT_REF_PREFIX-$LABEL"
            val nonNumericTimestamp = "${SNAPSHOT_REF_PREFIX}abc-$LABEL"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(validRef))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(malformedNoSeparator))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(missingSeparator))
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(nonNumericTimestamp))

            val snapshots = repository.rollback.list()

            assertEquals(listOf(validRef), snapshots.map { it.ref.value })
        }
    }

    @Test
    fun `предпросмотр отказывает по malformed ссылке без разделителя`() {
        val root = tempDir("aide-rollback-malformed-preview-no-sep-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "$SNAPSHOT_REF_PREFIX$FIRST_MILLIS"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.preview(SnapshotRef(ref))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `предпросмотр отказывает по malformed ссылке с пустой меткой времени`() {
        val root = tempDir("aide-rollback-malformed-preview-empty-ts-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "$SNAPSHOT_REF_PREFIX-$LABEL"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.preview(SnapshotRef(ref))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `предпросмотр отказывает по malformed ссылке с нечисловой меткой времени`() {
        val root = tempDir("aide-rollback-malformed-preview-non-num-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "${SNAPSHOT_REF_PREFIX}abc-$LABEL"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.preview(SnapshotRef(ref))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат отказывает по malformed ссылке без разделителя`() {
        val root = tempDir("aide-rollback-malformed-revert-no-sep-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "$SNAPSHOT_REF_PREFIX$FIRST_MILLIS"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат отказывает по malformed ссылке с пустой меткой времени`() {
        val root = tempDir("aide-rollback-malformed-revert-empty-ts-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "$SNAPSHOT_REF_PREFIX-$LABEL"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `откат отказывает по malformed ссылке с нечисловой меткой времени`() {
        val root = tempDir("aide-rollback-malformed-revert-non-num-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            commitFixture(root)
            val ref = "${SNAPSHOT_REF_PREFIX}abc-$LABEL"
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }

    @Test
    fun `неотслеживаемый файл дает грязное дерево`() {
        val root = tempDir("aide-rollback-untracked-")
        GitCliFixture.createRepo(root)
        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(FIRST_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            root.resolve("src/Untracked.kt").writeText("class Untracked\n")

            val outcome = repository.rollback.revert(SnapshotRef(ref), GitCliFixture.headHash(root))

            assertEquals(
                SnapshotRollbackRefusal.DIRTY_WORKTREE,
                assertIs<SnapshotRollbackOutcome.Refused>(outcome).reason,
            )
        }
    }
}
