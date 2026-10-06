package dev.aide.tools.sandbox

import java.nio.file.Files
import java.nio.file.Path

/** Что хост собирается запустить: тесты или линтер (T-1.10). */
internal enum class ProjectTool { TESTS, LINT }

/**
 * Команда проекта и места, где лежат её отчёты.
 *
 * Глобы отчётов — относительно корня воркспейса; у одной команды их бывает несколько
 * (Gradle разбивает отчёт по классам на файлы), и парсер разбирает все найденные.
 */
internal data class ProjectCommand(
    /** Программа и её аргументы; передаётся [CommandRunner] без оболочки. */
    val command: List<String>,
    /** Глобы отчётов относительно корня воркспейса. */
    val reportGlobs: List<String>,
)

/**
 * Таблица «чем запускать тесты и линтер в этом проекте» (T-1.10, решение 1).
 *
 * Команду определяет хост по маркерам в корне воркспейса, а не модель: иначе модель
 * подсунула бы под видом «тестов» произвольную команду, а инструмент перестал бы
 * обещать то, что обещает. Таблица — данные: строки для npm, Cargo, .NET добавляются
 * сюда, не трогая инструменты.
 *
 * Нераспознанный проект — `null`, а не догадка: инструмент вернёт явный отказ с
 * предложением `run_command`, и модель поправится сама. Линтер в этапе 1 — detekt
 * через Gradle; у Maven-проекта строки линтера нет, и это честный отказ, а не
 * попытка угадать.
 */
internal object ProjectCommands {

    /** Маркеры Gradle-проекта: файлы сборки в корне, в обоих диалектах (Kotlin и Groovy). */
    private val GRADLE_MARKERS: List<String> =
        listOf("settings.gradle.kts", "build.gradle.kts", "settings.gradle", "build.gradle")

    /** Отчёты Gradle: по каталогу на задачу (обычно `test`), по файлу на класс. */
    private const val GRADLE_TEST_REPORTS: String = "build/test-results/*/*.xml"

    /** Отчёт detekt: единственный XML-файл (html и sarif здесь не разбираются). */
    private const val GRADLE_LINT_REPORTS: String = "build/reports/detekt/detekt.xml"

    /** Отчёты Maven Surefire: файл на класс, как у Gradle. */
    private const val MAVEN_TEST_REPORTS: String = "target/surefire-reports/*.xml"

    /**
     * Команда для [tool] или `null`, если проект не распознан.
     *
     * Порядок проверок задаёт приоритет: сначала Gradle (маркеры Gradle и Maven в одном
     * каталоге — редкость, но если оба есть, Gradle-обёртка надёжнее), потом Maven.
     */
    fun detect(root: Path, tool: ProjectTool): ProjectCommand? {
        gradleLauncher(root)?.let { launcher -> return gradleCommand(launcher, tool) }
        return if (Files.exists(root.resolve(MAVEN_MARKER))) mavenCommand(tool) else null
    }

    /** Программа Gradle: своя обёртка, если она исполняема, иначе системная установка. */
    private fun gradleLauncher(root: Path): String? = when {
        Files.isExecutable(root.resolve(GRADLEW)) -> "./$GRADLEW"
        GRADLE_MARKERS.any { Files.exists(root.resolve(it)) } -> GRADLE
        else -> null
    }

    private fun gradleCommand(launcher: String, tool: ProjectTool): ProjectCommand? = when (tool) {
        ProjectTool.TESTS -> ProjectCommand(listOf(launcher, TEST_TASK), listOf(GRADLE_TEST_REPORTS))
        ProjectTool.LINT -> ProjectCommand(listOf(launcher, DETEKT_TASK), listOf(GRADLE_LINT_REPORTS))
    }

    /** Maven: тесты есть, линтера в таблице нет — угадывать его инструмент нечего. */
    private fun mavenCommand(tool: ProjectTool): ProjectCommand? = when (tool) {
        ProjectTool.TESTS -> ProjectCommand(listOf(MAVEN, TEST_TASK), listOf(MAVEN_TEST_REPORTS))
        ProjectTool.LINT -> null
    }
}

private const val GRADLEW: String = "gradlew"
private const val GRADLE: String = "gradle"
private const val MAVEN: String = "mvn"
private const val MAVEN_MARKER: String = "pom.xml"
private const val TEST_TASK: String = "test"
private const val DETEKT_TASK: String = "detekt"
