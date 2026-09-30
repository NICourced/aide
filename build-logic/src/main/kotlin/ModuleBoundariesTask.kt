package aide.build

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Правило границ: какие префиксы пакетов запрещены в каких модулях.
 * Проверяются только исходники модуля, объявленного в [module].
 */
data class ModuleBoundary(
    val module: String,
    val forbiddenPackagePrefixes: List<String>,
    val explanation: String,
    val action: String,
)

object ModuleBoundaries {

    private const val HOST_API_ACTION =
        "перенести работу на сторону хоста и вызвать её через API хоста."

    val rules: List<ModuleBoundary> = listOf(
        ModuleBoundary(
            module = "client-ui",
            forbiddenPackagePrefixes = listOf("dev.aide.host", "org.eclipse.jgit", "app.cash.sqldelight"),
            explanation = "Клиент не обращается к хосту, git и БД напрямую — только через API хоста (§ 3.2, § 8.1)",
            action = HOST_API_ACTION,
        ),
        ModuleBoundary(
            module = "client-state",
            forbiddenPackagePrefixes = listOf("dev.aide.host", "org.eclipse.jgit", "app.cash.sqldelight"),
            explanation = "Состояние клиента не знает о реализации хоста (§ 8.1)",
            action = HOST_API_ACTION,
        ),
        ModuleBoundary(
            module = "domain",
            forbiddenPackagePrefixes = listOf(
                "androidx.compose", "org.jetbrains.compose", "io.ktor", "dev.aide.host", "dev.aide.client",
            ),
            explanation = "Домен не знает ни о UI, ни о транспорте, ни о хосте (§ 8.1)",
            action = "убрать из домена зависимость на UI, транспорт и хост — эти типы остаются в своих слоях.",
        ),
        ModuleBoundary(
            module = "protocol",
            forbiddenPackagePrefixes = listOf("androidx.compose", "org.jetbrains.compose", "dev.aide.host", "dev.aide.client"),
            explanation = "Протокол не знает ни о UI, ни о реализации хоста (§ 8.1)",
            action = "описать зависимость типами домена вместо UI и реализации хоста.",
        ),
        ModuleBoundary(
            module = "host-core",
            forbiddenPackagePrefixes = listOf("dev.aide.client", "androidx.compose", "org.jetbrains.compose"),
            explanation = "Хост не знает клиента и не зависит от UI (§ 8.1); клиент допустим только в тестах хоста",
            action = "в main-коде хоста не зависеть от клиента; клиент подключать только в тестах хоста.",
        ),
        ModuleBoundary(
            module = "host-tools",
            forbiddenPackagePrefixes = listOf(
                "dev.aide.client", "dev.aide.host", "dev.aide.agent", "dev.aide.protocol",
                "androidx.compose", "org.jetbrains.compose", "app.cash.sqldelight", "org.eclipse.jgit",
            ),
            explanation = "Инструменты не знают ни клиента, ни хоста, ни рантайма агента, ни протокола, ни UI, " +
                "ни базы, ни git: зависит от инструментов хост, а не наоборот (§ 8.1, § 8.2)",
            action = "описать нужный доступ портом в host-tools (WorkspaceBoundary, ToolCallRecorder) " +
                "и реализовать его в host-core.",
        ),
    )

    /** Возвращает список нарушений: «файл: запрещённый пакет» — по одной записи на каждое вхождение в начало строки с import. */
    fun findViolations(module: String, files: Map<String, String>): List<String> {
        val rule = rules.firstOrNull { it.module == module } ?: return emptyList()
        val importRegex = Regex("""^\s*import\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
        return buildList {
            files.forEach { (fileName, text) ->
                importRegex.findAll(text).forEach { match ->
                    val imported = match.groupValues[1]
                    rule.forbiddenPackagePrefixes
                        .firstOrNull { imported.startsWith(it) }
                        ?.let { add("$fileName: $it → $imported") }
                }
            }
        }
    }

    fun message(module: String, violations: List<String>): String {
        val rule = rules.first { it.module == module }
        return buildString {
            appendLine("Нарушены границы модуля '$module':")
            violations.forEach { appendLine("  - $it") }
            appendLine("Почему это запрещено: ${rule.explanation}")
            appendLine("Что делать: ${rule.action}")
        }
    }
}

abstract class VerifyModuleBoundariesTask : DefaultTask() {

    @get:Input
    abstract val module: Property<String>

    // Каталог модуля нужен, чтобы показать в сообщении путь, относительный к модулю.
    // Отдельное свойство, а не `project.projectDir`: обращение к `project` в момент
    // выполнения задачи несовместимо с configuration cache (в проекте он включён).
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val baseDir = projectDirectory.get().asFile
        val files = sources.files.associate { it.relativeTo(baseDir).path to it.readText() }
        val violations = ModuleBoundaries.findViolations(module.get(), files)
        if (violations.isNotEmpty()) {
            throw GradleException(ModuleBoundaries.message(module.get(), violations))
        }
        logger.lifecycle("Границы модуля '${module.get()}' соблюдены (проверено файлов: ${files.size})")
    }
}
