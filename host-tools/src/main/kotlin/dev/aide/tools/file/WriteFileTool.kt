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
 * Запись текстового файла проекта (T-1.8).
 *
 * `content` — **полное новое содержимое**, а не патч: применить патч значит завести ещё
 * один парсер и класс ошибок (смещения, контекст, конфликт), а полный текст модель
 * генерирует надёжно. Создание и перезапись не различаются ничем, кроме того, был файл
 * или нет; точная правка отдельным инструментом появится позже.
 *
 * Путь, как и у чтения, проходит через [ToolContext.resolveInside]: и `..`, и симлинк
 * наружу отсекаются до записи, и другого санкционированного доступа к файлам у модуля
 * инструментов нет. Точку отката инструмент не ставит сам — её спрашивает точка вызова
 * перед любым изменяющим вызовом (О-3, NFR-SAFE-2).
 */
object WriteFileTool : AgentTool {

    /** Имя инструмента: под ним он виден модели. */
    const val TOOL_NAME: String = "write_file"

    override val name: String = TOOL_NAME

    override val description: String =
        "Записать текстовый файл проекта: полное новое содержимое заменяет прежнее, " +
            "отсутствующий файл создаётся вместе с родительскими каталогами."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(PATH) {
                put("type", "string")
                put("description", "Путь к файлу относительно корня проекта, например src/main/App.kt")
            }
            putJsonObject(CONTENT) {
                put("type", "string")
                put("description", "Полное новое содержимое файла (не патч и не фрагмент)")
            }
        }
        putJsonArray("required") {
            add(PATH)
            add(CONTENT)
        }
    }

    override val kind: ToolKind = ToolKind.WRITE

    /**
     * Запись объявлена требующей подтверждения (О-4): NG7 требует `ask` для изменений
     * по умолчанию, а диалог подтверждения (T-1.13) встанет в эту же точку.
     *
     * Ось `read` инструментом не используется: он не читает файл — чтение дело
     * `read_file`, — и здесь стоит то же объявленное умолчание, что и у записи, чтобы
     * значение не выглядело решением, которого на самом деле никто не принимал.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ASK, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val path = arguments.stringArgument(PATH)
        return if (path.isBlank()) {
            toolFailure("пустой путь: укажи файл относительно корня проекта")
        } else {
            write(path, arguments.stringArgument(CONTENT), context)
        }
    }

    /**
     * Пишет файл, путь которого уже разрешён границей воркспейса.
     *
     * Порядок проверок обязателен: сначала граница (отказ жёсткого предела важнее объёма),
     * потом лимит объёма — до создания каталогов и записи, чтобы отклонённый вызов
     * не оставил ни нового каталога, ни обрезанного файла.
     */
    private fun write(path: String, content: String, context: ToolContext): ToolResult {
        val file = context.resolveInside(path)
        // Кодируется один раз: и лимит, и текст ответа считаются по одному и тому же набору байт.
        val bytes = content.encodeToByteArray()
        return when {
            file.isDirectory() -> toolFailure("по пути «$path» каталог, а не файл")
            bytes.size > MAX_WRITE_BYTES ->
                toolFailure("содержимое «$path» превышает лимит $MAX_WRITE_BYTES байт; файл не изменён")
            else -> put(path, bytes, file)
        }
    }

    /**
     * Создаёт родительские каталоги и записывает содержимое; создание и перезапись видны в ответе.
     *
     * Пишутся именно байты (UTF-8), а не строка: строка уже закодирована для проверки лимита,
     * и повторное кодирование внутри `Files.writeString` было бы лишней работой.
     */
    private fun put(path: String, bytes: ByteArray, file: Path): ToolResult {
        val existed = Files.exists(file)
        file.parent?.let(Files::createDirectories)
        Files.write(file, bytes)
        val action = if (existed) "перезаписан" else "создан"
        return toolSuccess("файл «$path» $action (${bytes.size} байт)")
    }

    /**
     * Сколько байт разрешено записать одним вызовом.
     *
     * Число с причиной: вызов приходит из ответа модели, и ничто, кроме этого предела,
     * не мешает ему записать сотни мегабайт — такие данные не помещаются ни в контекст,
     * ни в ревью, а место на диске занимают. МиБ хватает на любой исходник и текстовый
     * файл данных, то есть лимит не мешает осмысленной работе, но обрывает срывной вызов.
     */
    internal const val MAX_WRITE_BYTES: Int = 1 * 1024 * 1024

    private const val PATH: String = "path"
    private const val CONTENT: String = "content"
}
