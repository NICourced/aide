package dev.aide.tools.file

import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.Permission
import dev.aide.domain.RiskLevel
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
 * Запись текстового файла проекта (T-1.8, T-1.15).
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
object WriteFileTool : AgentTool, ProposableTool {

    /** Имя инструмента: под ним он видит модель. */
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

    override suspend fun describe(arguments: JsonObject, context: ToolContext, risk: RiskLevel): ProposeResult {
        val path = arguments.stringArgument(PATH)
        if (path.isBlank()) return ProposeResult.Failure(
            toolFailure("пустой путь: укажи файл относительно корня проекта"),
        )
        val content = arguments.stringArgument(CONTENT)
        return when (val prepared = prepare(path, content, context)) {
            is WriteTarget.Directory -> ProposeResult.Failure(
                toolFailure("по пути «$path» каталог, а не файл"),
            )

            is WriteTarget.TooBig -> ProposeResult.Failure(
                toolFailure("содержимое «$path» превышает лимит $MAX_WRITE_BYTES байт; файл не изменён"),
            )

            is WriteTarget.Ready -> ProposeResult.Files(
                listOf(fileChange(path, prepared.file, content, risk)),
            )
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
        val prepared = prepare(path, content, context)
        return when (prepared) {
            is WriteTarget.Directory -> toolFailure("по пути «$path» каталог, а не файл")
            is WriteTarget.TooBig ->
                toolFailure("содержимое «$path» превышает лимит $MAX_WRITE_BYTES байт; файл не изменён")

            is WriteTarget.Ready -> put(path, prepared.file, content)
        }
    }

    /**
     * Общая подготовка записи и предложения: граница, каталог, лимит.
     *
     * Возвращает [WriteTarget.Directory], [WriteTarget.TooBig] или [WriteTarget.Ready].
     * Граница воркспейса бросает [dev.aide.tools.limits.HardLimitViolation], как и у
     * обычного выполнения: предложение не должно обходить жёсткие пределы.
     */
    private fun prepare(path: String, content: String, context: ToolContext): WriteTarget {
        val file = context.resolveInside(path)
        return when {
            file.isDirectory() -> WriteTarget.Directory
            content.encodeToByteArray().size > MAX_WRITE_BYTES -> WriteTarget.TooBig
            else -> WriteTarget.Ready(file)
        }
    }

    /**
     * Создаёт родительские каталоги и записывает содержимое; создание и перезапись видны в ответе.
     *
     * Пишутся именно байты (UTF-8), а не строка: строка уже закодирована для проверки лимита,
     * и повторное кодирование внутри `Files.writeString` было бы лишней работой.
     */
    private fun put(path: String, file: Path, content: String): ToolResult {
        val existed = Files.exists(file)
        val bytes = content.encodeToByteArray()
        file.parent?.let(Files::createDirectories)
        Files.write(file, bytes)
        val action = if (existed) "перезаписан" else "создан"
        return toolSuccess("файл «$path» $action (${bytes.size} байт)")
    }

    /**
     * Цельнофайловое изменение для очереди предложений (T-1.15).
     *
     * Новый файл даёт [FileChangeKind.ADDED] с одним hunk [HunkKind.ADD]; существующий —
     * [FileChangeKind.MODIFIED] с [HunkKind.REPLACE]. Номера строк отсчитываются с единицы.
     * Это минимальное представление для инбокса; связанные hunk'и, контекст и word-diff
     * остаются T-1.21…T-1.23.
     */
    private fun fileChange(path: String, file: Path, newContent: String, risk: RiskLevel): FileChange {
        val existed = Files.exists(file)
        val oldLines = if (existed) readLines(file) else emptyList()
        val newLines = contentLines(newContent)
        val changeKind = if (existed) FileChangeKind.MODIFIED else FileChangeKind.ADDED
        val hunkKind = if (existed) HunkKind.REPLACE else HunkKind.ADD
        val lines = buildList {
            if (existed) {
                oldLines.forEachIndexed { index, text ->
                    add(HunkLine(kind = LineKind.REMOVED, oldNumber = index + 1, newNumber = null, text = text))
                }
            }
            newLines.forEachIndexed { index, text ->
                add(HunkLine(kind = LineKind.ADDED, oldNumber = null, newNumber = index + 1, text = text))
            }
        }
        val hunk = Hunk(
            id = HunkId("hunk-$path"),
            filePath = path,
            startLine = 1,
            kind = hunkKind,
            risk = risk,
            lines = lines,
        )
        return FileChange(
            path = path,
            changeKind = changeKind,
            hunks = listOf(hunk),
            addedLines = newLines.size,
            removedLines = oldLines.size,
        )
    }

    private fun readLines(file: Path): List<String> = contentLines(Files.readString(file))

    /**
     * Строки содержимого для diff: один завершающий перевод строки не порождает
     * дополнительную пустую строку; пустый файл не содержит строк вовсе.
     */
    private fun contentLines(content: String): List<String> {
        if (content.isEmpty()) return emptyList()
        val lines = content.lines()
        return if (content.endsWith('\n') || content.endsWith('\r')) lines.dropLast(1) else lines
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

    /** Промежуточный результат подготовки записи. */
    private sealed interface WriteTarget {
        data class Ready(val file: Path) : WriteTarget
        data object Directory : WriteTarget
        data object TooBig : WriteTarget
    }
}
