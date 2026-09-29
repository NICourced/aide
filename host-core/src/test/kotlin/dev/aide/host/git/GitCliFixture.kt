package dev.aide.host.git

import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Обёртка над командной строкой git: готовит фикстуру и даёт эталон для сверки результатов JGit. */
object GitCliFixture {

    private const val GIT = "git"
    private const val RENAME_ARROW = " -> "
    private const val GIT_MISSING =
        "Для этих тестов нужен git в PATH: критерий T-0.12 требует сверки с выводом git status и git log"

    /** Проверяет, что git доступен, и падает с понятным сообщением, если нет. */
    fun requireGit() {
        val result = try {
            run(listOf(GIT, "--version"), workingDir = null)
        } catch (error: IOException) {
            throw IllegalStateException(GIT_MISSING, error)
        }
        check(result.exitCode == 0) { GIT_MISSING }
    }

    /** Создаёт репозиторий с двумя коммитами, изменённым отслеживаемым файлом и новым файлом вне индекса. */
    fun createRepo(root: Path): Path {
        requireGit()
        Files.createDirectories(root)
        run(listOf(GIT, "init", "-b", "master"), root)
        run(listOf(GIT, "config", "user.email", "test@aide.dev"), root)
        run(listOf(GIT, "config", "user.name", "Aide Test"), root)
        // Локально: глобальная подпись коммитов сделала бы фикстуру зависимой от настроек машины.
        run(listOf(GIT, "config", "commit.gpgsign", "false"), root)
        // Переименования сверяются явно, поэтому поиск переименований в status включается
        // локально: глобальный diff.renames=false иначе развёл бы git и JGit на фикстуре.
        run(listOf(GIT, "config", "status.renames", "true"), root)

        root.resolve("src").createDirectories()
        root.resolve("src/Login.kt").writeText("fun login() = Unit\n")
        run(listOf(GIT, "add", "."), root)
        run(listOf(GIT, "commit", "-m", "первый коммит"), root)

        root.resolve("src/Api.kt").writeText("class Api\n")
        run(listOf(GIT, "add", "."), root)
        run(listOf(GIT, "commit", "-m", "второй коммит"), root)

        // Незакоммиченное изменение и новый файл — то, что должен увидеть git status.
        root.resolve("src/Login.kt").writeText("fun login() = \"token\"\n")
        root.resolve("src/New.kt").writeText("class New\n")

        return root
    }

    /** Создаёт репозиторий без коммитов. */
    fun createEmptyRepo(root: Path): Path {
        requireGit()
        Files.createDirectories(root)
        run(listOf(GIT, "init", "-b", "master"), root)
        return root
    }

    /**
     * Запись `git status --porcelain=v1`: двухсимвольный код и путь.
     *
     * Для переименования в выводе стоит суффикс «старый -> новый», а в [path] попадает новый путь,
     * в [previousPath] — старый: так запись сравнивается с JGit по конечному расположению файла.
     */
    data class StatusEntry(val code: String, val path: String, val previousPath: String? = null)

    /** `git status --porcelain=v1`, разобранный по строкам. */
    fun status(root: Path): List<StatusEntry> = porcelainLines(root).map { line ->
        val code = line.substring(0, 2).trim()
        val payload = line.substring(2).trim()
        if (code == "R") {
            val (old, new) = payload.split(RENAME_ARROW).map { it.trim() }
            StatusEntry(code = code, path = new, previousPath = old)
        } else {
            StatusEntry(code = code, path = payload)
        }
    }

    /** Строки `git status --porcelain=v1` как есть: нужны там, где важен код строки, а не разобранная запись. */
    fun porcelainLines(root: Path): List<String> =
        run(listOf(GIT, "status", "--porcelain=v1"), root).output
            .lines()
            .filter { it.isNotBlank() }

    /** Значения одного поля `git log` для последних коммитов, от нового к старому. */
    fun logField(root: Path, format: String, limit: Int): List<String> =
        run(listOf(GIT, "log", "--format=$format", "-n", limit.toString()), root).output
            .lines()
            .filter { it.isNotBlank() }

    /**
     * Имя текущей ветки.
     *
     * `symbolic-ref --short HEAD`, а не `rev-parse --abbrev-ref HEAD`: на репозитории
     * без коммитов rev-parse не находит HEAD, печатает «HEAD» и падает с кодом 128,
     * а symbolic-ref отдаёт имя ещё не созданной ветки — то есть то же, что должен вернуть хост.
     */
    fun currentBranch(root: Path): String =
        run(listOf(GIT, "symbolic-ref", "--short", "HEAD"), root).output.trim()

    /** Короткий хеш HEAD по версии git. */
    fun headShortHash(root: Path): String =
        run(listOf(GIT, "rev-parse", "--short", "HEAD"), root).output.trim()

    data class CliResult(val exitCode: Int, val output: String)

    fun run(command: List<String>, workingDir: Path?): CliResult {
        val process = ProcessBuilder(command)
            .apply {
                if (workingDir != null) directory(workingDir.toFile())
                // stdout и stderr слиты: последовательное чтение двух пайпов взаимно блокируется,
                // когда git пишет больше буфера в поток, который в этот момент не читают.
                redirectErrorStream(true)
            }
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CliResult(exitCode, output)
    }
}
