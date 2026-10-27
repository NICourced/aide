package dev.aide.host.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/** Сколько коммитов читается в проверках истории: столько их на фикстуре и после шага. */
private const val LOG_LIMIT = 10

/** Метка времени снапшота из T-1.19: миллисекунды от эпохи. */
private const val SNAPSHOT_MILLIS = 1_758_535_200_000L

/** Повод снапшота в имени ссылки. */
private const val LABEL = "before-agent-step"

/** Сообщение коммита шага: номер с единицы и описание (решение 6). */
private const val STEP_MESSAGE = "Шаг 1: поправить вход"

/**
 * Содержимое `src/Login.kt` в коммите «чистое дерево».
 *
 * Фикстура оставляет файл изменённым, и этот коммит принимает правку: дальше состояние
 * точки отсчёта известно точно, и с ним сверяется результат возврата дерева.
 */
private const val CLEAN_LOGIN = "fun login() = \"token\"\n"

/** Игнорируемый файл прогона: возврат дерева обязан его сохранить — его создала не работа агента. */
private const val IGNORED_PATH = "build/artefact.txt"

/** Содержимое игнорируемого файла: по нему видно, что возврат дерева его не тронул. */
private const val IGNORED_CONTENT = "артефакт сборки\n"

/**
 * T-1.4: возврат рабочего дерева к снапшоту (`reset --hard` и очистка неотслеживаемого).
 *
 * Отдельный файл от [JGitRepositoryTest]: там состояние репозитория читается, а здесь
 * меняется — у этих проверок разные предметы, и вместе они перешагнули бы порог
 * `LargeClass`, который проект ослаблять не разрешает.
 *
 * Репозиторий настоящий, и сверка идёт с выводом git: `reset` и `clean` — операции git,
 * и проверять их на заглушке значило бы проверять заглушку.
 */
class JGitRestoreTest {

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
    fun `восстановление возвращает дерево к снапшоту, убирая правки и новые файлы`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-"))

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            // Что сделал бы агент: правка отслеживаемого файла и новый файл вне индекса.
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")
            Files.writeString(root.resolve("src/Generated.kt"), "class Generated\n")

            assertEquals(RestoreOutcome.Restored, repository.restore.restoreTo(ref))

            assertEquals(
                CLEAN_LOGIN,
                Files.readString(root.resolve("src/Login.kt")),
                "отслеживаемая правка откачена к снапшоту",
            )
            assertFalse(
                Files.exists(root.resolve("src/Generated.kt")),
                "файл, созданный прогоном, убран: он не отслеживался, и вернуть его нечем",
            )
            assertEquals(
                IGNORED_CONTENT,
                Files.readString(root.resolve(IGNORED_PATH)),
                "игнорируемое не трогается: его создала не работа агента",
            )
            assertTrue(GitCliFixture.porcelainLines(root).isEmpty(), "дерево вернулось к снапшоту")
        }
    }

    @Test
    fun `восстановление не теряет ссылку снапшота и историю до него`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-history-"))

        JGitRepository.open(root).use { repository ->
            // Коммит шага появляется до снапшота — как в жизни: точка отката перед изменяющим
            // вызовом берётся поверх коммита предыдущего шага (T-1.8, T-1.11).
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"step\"\n")
            assertIs<StepCommitOutcome.Committed>(repository.commitStep("master", STEP_MESSAGE))
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")

            assertEquals(RestoreOutcome.Restored, repository.restore.restoreTo(ref))

            assertEquals(
                listOf(STEP_MESSAGE, "чистое дерево", "второй коммит", "первый коммит"),
                repository.commitLog(limit = LOG_LIMIT).map { it.message },
                "история до снапшота сохранена, коммит шага на месте",
            )
            assertEquals(listOf(ref), GitCliFixture.snapshotRefs(root), "ссылка снапшота не удалена")
            assertEquals(
                GitCliFixture.hashOf(root, ref),
                GitCliFixture.headHash(root),
                "ветка стоит там же, где снапшот",
            )
        }
    }

    /**
     * Возврат дерева — не «отмена коммита»: ветка уходит к снапшоту вместе с рабочим деревом.
     *
     * Так выглядит решение владельца (T-1.4, решение 1): критерий требует репозиторий
     * в состоянии последнего снапшота, и он выполняется буквально. Обратная сторона —
     * коммит, сделанный **после** снапшота, с ветки уходит; объект остаётся в базе (и в reflog),
     * но не частью истории ветки. Это названная плата, а не недосмотр, поэтому она проверяется.
     */
    @Test
    fun `восстановление оставляет ветку в состоянии снапшота, а поздний коммит — только объектом`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-late-"))

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"late\"\n")
            val late = assertIs<StepCommitOutcome.Committed>(repository.commitStep("master", "Шаг 2: позже снапшота"))

            assertEquals(RestoreOutcome.Restored, repository.restore.restoreTo(ref))

            assertEquals(
                listOf("чистое дерево", "второй коммит", "первый коммит"),
                repository.commitLog(limit = LOG_LIMIT).map { it.message },
                "ветка оканчивается снапшотом: позднего коммита в ней нет",
            )
            assertEquals(
                late.hash,
                GitCliFixture.hashOf(root, late.hash),
                "поздний коммит остаётся объектом в базе, а не пропадает",
            )
        }
    }

    @Test
    fun `восстановление по несуществующей ссылке даёт отказ с причиной`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-missing-"))

        JGitRepository.open(root).use { repository ->
            // Ссылки нет: снапшот мог быть вытеснен (T-1.19), и это состояние репозитория,
            // а не сбой — движку нужен отказ-значение с причиной.
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")

            val outcome = assertIs<RestoreOutcome.Refused>(
                repository.restore.restoreTo(snapshotRefName(SNAPSHOT_MILLIS, LABEL)),
            )

            assertEquals(RestoreRefusal.UNKNOWN_REF, outcome.reason)
            assertEquals(
                "fun login() = \"agent\"\n",
                Files.readString(root.resolve("src/Login.kt")),
                "дерево не тронуто: по несуществующей ссылке ничего не откатывается",
            )
        }
    }

    @Test
    fun `сбой git при восстановлении даёт отказ, а не исключение`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-failed-"))
        // Права снимаются у каталога веток: `reset` обновляет ссылку текущей ветки, а её
        // файл-замок в недоступном каталоге не создаётся. Снятия прав с самого `refs` мало:
        // ссылка лежит глубже, в `refs/heads`.
        val headsDir = root.resolve(".git/refs/heads").toFile()

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.writeString(root.resolve("src/Login.kt"), "fun login() = \"agent\"\n")

            check(headsDir.setWritable(false, false)) { "не удалось снять права на ${headsDir.path}" }
            try {
                val outcome = assertIs<RestoreOutcome.Refused>(repository.restore.restoreTo(ref))

                assertEquals(RestoreRefusal.GIT_FAILED, outcome.reason)
                assertEquals(
                    "fun login() = \"agent\"\n",
                    Files.readString(root.resolve("src/Login.kt")),
                    "операция не состоялась, поэтому дерево осталось как было",
                )
            } finally {
                headsDir.setWritable(true, true)
            }
        }
    }

    /**
     * Игнорируемое внутри неотслеживаемого каталога обязано остаться.
     *
     * `git clean` не удаляет игнорируемое по точному пути, но каталог, в котором лежит
     * только игнорируемое, он удаляет целиком — и файл человека пропадает беззвучно.
     * Поэтому неотслеживаемые файлы убираются поштучно, а каталоги — только ставшие пустыми:
     * каталог с оставшимся игнорируемым файлом обязан уцелеть.
     */
    @Test
    fun `восстановление сохраняет игнорируемое внутри неотслеживаемого каталога`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-nested-"))
        // Каталог человека: неотслеживаемый файл рядом с игнорируемым каталогом. `build/`
        // в `.gitignore` совпадает на любой глубине, поэтому `local/build` — игнорируемый.
        root.resolve("local").createDirectories()
        root.resolve("local/main.txt").writeText("правка человека\n")
        root.resolve("local/build").createDirectories()
        root.resolve("local/build/art.bin").writeText("артефакт сборки\n")

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))

            assertEquals(RestoreOutcome.Restored, repository.restore.restoreTo(ref))

            assertFalse(Files.exists(root.resolve("local/main.txt")), "неотслеживаемый файл убран")
            assertEquals(
                "артефакт сборки\n",
                Files.readString(root.resolve("local/build/art.bin")),
                "игнорируемое внутри неотслеживаемого каталога не трогается",
            )
            assertTrue(
                Files.isDirectory(root.resolve("local")),
                "каталог, в котором остался игнорируемый файл, не удаляется",
            )
        }
    }

    /**
     * Файлы вне воркспейса восстановление не трогает.
     *
     * Операция работает только внутри рабочего каталога репозитория; проверка сторожит
     * уборку пустых каталогов: она поднимается вверх по пути удалённого файла и обязана
     * остановиться на корне, а не уйти в соседний каталог.
     */
    @Test
    fun `восстановление не трогает файлы вне воркспейса`() {
        val root = cleanRepoWithIgnoredFile(tempDir("aide-restore-inside-"))
        val neighbour = tempDir("aide-restore-outside-").resolve("neighbour.txt")
        neighbour.writeText("соседний файл\n")

        JGitRepository.open(root).use { repository ->
            val ref = snapshotRefName(SNAPSHOT_MILLIS, LABEL)
            assertEquals(SnapshotOutcome.Created, repository.createSnapshot(ref))
            Files.writeString(root.resolve("src/New.kt"), "class New\n")

            assertEquals(RestoreOutcome.Restored, repository.restore.restoreTo(ref))

            assertEquals(
                "соседний файл\n",
                Files.readString(neighbour),
                "файл за корнем репозитория не имеет отношения к снапшоту и обязан уцелеть",
            )
        }
    }

    /**
     * Репозиторий с чистым деревом и игнорируемым файлом.
     *
     * `.gitignore` коммитится вместе с правками фикстуры: незакоммиченный `.gitignore` —
     * такая же правка, и `clean` убрал бы его вместе с игнорируемым каталогом, а проверка
     * стала бы про чистку правок человека, а не про игнорируемое. Игнорируемый файл создаётся
     * до прогона: он и есть то, что возврат дерева обязан сохранить.
     */
    private fun cleanRepoWithIgnoredFile(root: Path): Path {
        GitCliFixture.createRepo(root)
        root.resolve(".gitignore").writeText("build/\n")
        GitCliFixture.run(listOf("git", "add", "-A"), root)
        GitCliFixture.run(listOf("git", "commit", "-m", "чистое дерево"), root)
        root.resolve(IGNORED_PATH).parent.createDirectories()
        root.resolve(IGNORED_PATH).writeText(IGNORED_CONTENT)
        return root
    }
}
