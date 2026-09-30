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
import kotlin.io.path.isDirectory
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Чтение файла проекта (T-1.7).
 *
 * Путь проходит через [ToolContext.resolveInside], поэтому и `..`, и симлинк наружу
 * отклоняются до открытия файла: инструмент не «проверяет корень сам», он иначе не
 * умеет ходить на диск.
 */
object ReadFileTool : AgentTool {

    /** Имя инструмента: под ним он виден модели. */
    const val TOOL_NAME: String = "read_file"

    override val name: String = TOOL_NAME

    override val description: String =
        "Прочитать текстовый файл проекта по пути относительно корня. " +
            "Большой файл отдаётся начало с пометкой об обрезке."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(PATH) {
                put("type", "string")
                put("description", "Путь к файлу относительно корня проекта, например src/main/App.kt")
            }
        }
        putJsonArray("required") { add(PATH) }
    }

    override val kind: ToolKind = ToolKind.READ

    /**
     * Чтение объявлено разрешённым (О-4): внутри воркспейса это безопасное действие,
     * и диалог подтверждения на каждый прочитанный файл сделал бы агента неприменимым.
     * Настройка пользователя это умолчание перекрывает — `deny` остаётся возможным.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ALLOW, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = arguments.stringArgument(PATH)
        return if (path.isBlank()) {
            toolFailure("пустой путь: укажи файл относительно корня проекта")
        } else {
            read(path, context)
        }
    }

    /**
     * Читает файл, путь которого уже прошёл [ToolContext.resolveInside].
     *
     * Разрешение пути происходит ровно один раз: канонизация — это обращения к файловой
     * системе, и повторять её ради второй проверки значило бы платить дважды.
     */
    private fun read(path: String, context: ToolContext): ToolResult {
        val file = context.resolveInside(path)
        val complaint = complaintAbout(path, file)
        return if (complaint != null) toolFailure(complaint) else contents(path, file)
    }

    /** Почему файл не прочитать; null — можно. */
    private fun complaintAbout(path: String, file: Path): String? = when {
        !Files.exists(file) -> "файл не найден: $path"
        file.isDirectory() -> "по пути «$path» каталог, а не файл"
        else -> null
    }

    /** Содержимое файла: начало с пометкой об обрезке, если файл больше лимита. */
    private fun contents(path: String, file: Path): ToolResult {
        val size = Files.size(file)
        val bytes = readUpTo(file)
        val text = String(bytes, Charsets.UTF_8)
        return when {
            bytes.any { it == NUL } -> toolFailure("«$path» не текстовый файл: в нём есть байт 0")
            size > MAX_READ_BYTES -> toolSuccess("$text\n\n[файл обрезан: показано $MAX_READ_BYTES байт из $size]")
            else -> toolSuccess(text)
        }
    }

    /** Читает не больше лимита: файл на гигабайт не должен попадать в контекст модели. */
    private fun readUpTo(file: Path): ByteArray =
        Files.newInputStream(file).use { stream -> stream.readNBytes(MAX_READ_BYTES) }

    /**
     * Сколько байт файла отдаётся модели.
     *
     * Не то же число, что лимит показа в UI (`WorkspaceFileSystem.MAX_DISPLAY_BYTES`,
     * 512 КиБ): у модели контекст общий на весь диалог и оплачивается по токенам, а
     * человеку файл показывается целиком. 64 КиБ — примерно 16 тысяч токенов, этого
     * хватает на большой исходник, и один `read_file` при таком лимите не съедает
     * контекст целиком.
     */
    internal const val MAX_READ_BYTES: Int = 64 * 1024

    /** Байт NUL: по нему текстовый файл отличается от двоичного. */
    private const val NUL: Byte = 0

    private const val PATH: String = "path"
}
