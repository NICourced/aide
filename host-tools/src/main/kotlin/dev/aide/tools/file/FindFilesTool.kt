package dev.aide.tools.file

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolResult
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.toolFailure
import dev.aide.tools.toolSuccess
import java.nio.file.FileSystems
import java.nio.file.Path
import java.nio.file.PathMatcher
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Поиск файлов по маске (T-1.7).
 *
 * Маска — glob-выражение, и проверяется она и по пути от корня, и по имени файла:
 * агенту одинаково естественны `*.kt` (все Kotlin-файлы проекта) и `src/` с `**`
 * (и файлы под `src`, и файлы в самом `src`). Часть «на любой глубине» означает
 * ноль каталогов и более — иначе привычная маска молча теряла бы файлы на уровне,
 * который сама же и обозначает.
 */
object FindFilesTool : AgentTool {

    /** Имя инструмента: под ним он виден модели. */
    const val TOOL_NAME: String = "find_files"

    override val name: String = TOOL_NAME

    override val description: String =
        "Найти файлы проекта по маске (glob), например *.kt или src/**/*.test.ts. " +
            "Каталог .git в выдачу не попадает."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(MASK) {
                put("type", "string")
                put("description", "Маска файлов: *.kt, src/**/*.ts, **/build.gradle.kts")
            }
        }
        putJsonArray("required") { add(MASK) }
    }

    override val kind: ToolKind = ToolKind.READ

    /** Как у чтения файла: поиск внутри воркспейса разрешён без подтверждения (О-4). */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ALLOW, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val mask = arguments.stringArgument(MASK).trim()
        val matchers = matchersFor(mask)
        return when {
            mask.isBlank() -> toolFailure("пустая маска: укажи маску файлов, например *.kt")
            matchers == null -> toolFailure("маска «$mask» не разбирается; примеры: *.kt, src/**/*.kt")
            else -> listing(mask, matchers, context)
        }
    }

    /** Список найденного; пустой результат — не ошибка, а ответ. */
    private fun listing(mask: String, matchers: List<PathMatcher>, context: ToolContext): ToolResult {
        val found = workspaceFiles(context.root)
            .filter { matches(matchers, context.root, it) }
            .map { relativePath(context.root, it) }
            .sorted()
        if (found.isEmpty()) return toolSuccess("Файлов по маске «$mask» не найдено")
        val shown = found.take(MAX_RESULTS)
        val text = buildString {
            appendLine("Найдено файлов: ${found.size}")
            append(shown.joinToString("\n"))
        }
        return toolSuccess(
            if (found.size > shown.size) "$text\n[показаны первые $MAX_RESULTS файлов — уточни маску]" else text,
        )
    }

    /**
     * Матчеры для маски: сама маска и она же без частей «на любой глубине».
     *
     * «На любой глубине» в glob пересекает границы каталогов, но требует хотя бы
     * одного каталога на своём месте: файл прямо в этом каталоге такой маске уже
     * не подходит. Поэтому кроме самой маски проверяется вариант, где эти части
     * убраны целиком, — он и означает «ноль каталогов здесь». Вместе они дают
     * привычное агенту чтение: маска ловит и вложенные файлы, и файлы в самом
     * каталоге.
     *
     * null — маска не разбирается: `PatternSyntaxException` наружу отдавать нельзя,
     * это не сбой инструмента, а негодный аргумент, о котором модель обязана узнать
     * понятным текстом.
     */
    private fun matchersFor(mask: String): List<PathMatcher>? {
        if (mask.isBlank()) return null
        val collapsed = mask.replace(ANY_DEPTH, "")
        val bases = if (collapsed == mask) listOf(mask) else listOf(mask, collapsed)
        val matchers = bases.map { base -> runCatching { FileSystems.getDefault().getPathMatcher("glob:$base") } }
        return if (matchers.any { it.isFailure }) null else matchers.mapNotNull { it.getOrNull() }
    }

    /** Совпадение по пути от корня или по имени файла: см. KDoc инструмента. */
    private fun matches(matchers: List<PathMatcher>, root: Path, file: Path): Boolean {
        val relative = root.relativize(file)
        return matchers.any { matcher -> matcher.matches(relative) || matcher.matches(file.fileName) }
    }

    /**
     * Сколько файлов отдаётся модели.
     *
     * Число с причиной: широкой маской («все файлы») агент мог бы запросить весь
     * репозиторий сразу и съесть контекст одним вызовом. Ста файлов хватает, чтобы
     * понять устройство проекта, а пометка об обрезке говорит, что маску надо уточнить.
     */
    internal const val MAX_RESULTS: Int = 100

    /** Ведущая часть маски, означающая «на любой глубине». */
    private const val ANY_DEPTH: String = "**/"

    private const val MASK: String = "mask"
}
