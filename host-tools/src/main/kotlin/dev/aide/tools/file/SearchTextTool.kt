package dev.aide.tools.file

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolResult
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.toolFailure
import dev.aide.tools.toolSuccess
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Поиск строки по проекту (T-1.7).
 *
 * Поиск подстрочный и без индекса: агент ищет «где это написано», и для этого
 * достаточно `contains`. Регистр учитывается — иначе «какие тесты зовут Foo»
 * находило бы `foo`, и модель правила бы не тот код. Индексация и нечёткий поиск —
 * этап 6; цена решения записана в плане: на большом репозитории первый поиск медленный.
 */
object SearchTextTool : AgentTool {

    /** Имя инструмента: под ним он виден модели. */
    const val TOOL_NAME: String = "search_text"

    override val name: String = TOOL_NAME

    override val description: String =
        "Найти строку во всех текстовых файлах проекта. Поиск подстрочный, регистр учитывается. " +
            "Возвращает путь, номер строки и саму строку."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(QUERY) {
                put("type", "string")
                put("description", "Что искать: имя функции, класса, сообщение об ошибке")
            }
        }
        putJsonArray("required") { add(QUERY) }
    }

    override val kind: ToolKind = ToolKind.READ

    /** Как у чтения файла: поиск внутри воркспейса разрешён без подтверждения (О-4). */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ALLOW, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val query = arguments.stringArgument(QUERY)
        return if (query.isEmpty()) {
            toolFailure("пустой запрос: укажи строку, которую надо найти")
        } else {
            report(query, context)
        }
    }

    /** Ответ по найденному: совпадения, пометка о лимите и число пропущенных файлов. */
    private fun report(query: String, context: ToolContext): ToolResult {
        val matches = matches(query, context.root)
        if (matches.found.isEmpty()) return toolSuccess(notFoundText(query))
        val text = buildString {
            appendLine("Совпадения по «$query» (${matches.found.size}):")
            append(matches.found.joinToString("\n"))
            if (matches.truncated) append("\n[лимит в $MAX_MATCHES совпадений исчерпан — уточни запрос]")
            if (matches.skipped > 0) {
                append("\n[пропущено файлов крупнее $MAX_SEARCH_FILE_BYTES байт: ${matches.skipped}]")
            }
        }
        return toolSuccess(text)
    }

    /**
     * Поиск по файлам воркспейса.
     *
     * Крупные файлы отделены до чтения: это не исходники проекта, а данные, и открывать
     * их незачем. Совпадений собирается на одно больше лимита — по лишнему видно, что
     * лимит исчерпан, а не что совпадения кончились; последовательность ленивая, поэтому
     * файлы после исчерпания лимита не читаются вовсе.
     */
    private fun matches(query: String, root: Path): Matches {
        val (oversized, searchable) = workspaceFiles(root)
            .sortedBy { relativePath(root, it) }
            .partition { Files.size(it) > MAX_SEARCH_FILE_BYTES }
        val found = searchable.asSequence()
            .flatMap { file -> matchesIn(root, file, query) }
            .take(MAX_MATCHES + 1)
            .toList()
        return Matches(found.take(MAX_MATCHES), oversized.size, found.size > MAX_MATCHES)
    }

    /** Совпадения одного файла: путь, номер строки и строка, обрезанная по лимиту вывода. */
    private fun matchesIn(root: Path, file: Path, query: String): Sequence<String> {
        val text = textOf(file) ?: return emptySequence()
        val relative = relativePath(root, file)
        return text.lineSequence()
            .withIndex()
            .filter { (_, line) -> line.contains(query) }
            .map { (index, line) -> "$relative:${index + 1}: ${line.trimEnd().take(MAX_MATCH_LINE)}" }
    }

    /**
     * Текст файла; null — файл двоичный или не читается.
     *
     * Размер проверяется вызывающим: двоичный файл не называется в ответе отдельно —
     * он и не текстовый, искать в нём строку бессмысленно.
     */
    private fun textOf(file: Path): String? {
        val bytes = Files.newInputStream(file).use { stream -> stream.readNBytes(MAX_SEARCH_FILE_BYTES) }
        if (bytes.any { it == NUL }) return null
        return String(bytes, Charsets.UTF_8)
    }

    private fun notFoundText(query: String): String =
        "Совпадений по «$query» не найдено (поиск подстрочный, регистр учитывается)"

    /**
     * Сколько совпадений отдаётся модели.
     *
     * Сотни совпадений хватает, чтобы понять, где живёт код; дальше ответ только жрёт
     * контекст: по одной строке на совпадение при сотне совпадений это тысячи токенов.
     */
    internal const val MAX_MATCHES: Int = 100

    /** Длина строки в выдаче: минифицированный файл иначе отдал бы строку на мегабайт. */
    private const val MAX_MATCH_LINE: Int = 200

    /** Файлы крупнее этого не читаются вовсе: это не исходники проекта, а данные. */
    internal const val MAX_SEARCH_FILE_BYTES: Int = 256 * 1024

    /** Байт NUL: по нему текстовый файл отличается от двоичного. */
    private const val NUL: Byte = 0

    private const val QUERY: String = "query"
}

/** Что нашлось: совпадения, сколько файлов пропущено по размеру и исчерпан ли лимит. */
private data class Matches(val found: List<String>, val skipped: Int, val truncated: Boolean)
