package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolResult
import dev.aide.tools.limits.NetworkPolicy
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.toolFailure
import dev.aide.tools.toolSuccess
import java.io.IOException
import java.nio.file.Path
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Терминал агента: команда в рабочей директории воркспейса (T-1.9, FR-TOOLS-12).
 *
 * Аргументы — **список**, а не строка для оболочки: процесс запускается напрямую, и нет
 * ни конвейеров, ни перенаправлений, ни подстановок. Причина не в удобстве, а в том, что
 * оболочка отменяет предполётную сверку: `sh -c "cat /etc/passwd"` — один аргумент, в
 * котором проверка путей ничего не увидит. Обойти это можно и явным `sh` в списке, и это
 * осознанная цена: настоящая песочница с изоляцией — T-3.22.
 *
 * Ненулевой код возврата — **не сбой инструмента**: «команда не нашла» и «команду не дали»
 * агент обязан различать, поэтому код, stdout и stderr уезжают модели обычным успехом.
 * Сбоем считаются отказ прав, жёсткий предел, таймаут и невозможность запустить процесс.
 *
 * @param network предел сети: хост из аргументов, не перечисленный явно, — отказ.
 * @param timeoutMillis предел времени вызова; по отмене [CommandRunner] убивает дерево.
 */
class RunCommandTool(
    private val network: NetworkPolicy,
    override val timeoutMillis: Long = COMMAND_TIMEOUT_MILLIS,
) : AgentTool {

    private val runner: CommandRunner = CommandRunner()

    override val name: String = TOOL_NAME

    override val description: String =
        "Выполнить команду в корне проекта и получить её stdout, stderr и код возврата. " +
            "Команда передаётся списком аргументов без оболочки: конвейеры и перенаправления " +
            "не поддерживаются. Рабочая директория — корень проекта, пути наружу отклоняются."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(COMMAND_ARGUMENT) {
                put("type", "array")
                putJsonObject("items") { put("type", "string") }
                put(
                    "description",
                    "Программа и её аргументы по отдельности, например [\"git\", \"status\", \"--short\"]",
                )
            }
        }
        putJsonArray("required") { add(COMMAND_ARGUMENT) }
    }

    override val kind: ToolKind = ToolKind.WRITE

    /**
     * Исполнение идёт по оси записи, умолчание `ASK` (О-4, NG7): команда может менять
     * файлы, и опасность у неё та же, что у записи. Пока диалога подтверждения нет
     * (T-1.13), `ASK` означает отказ — это и есть настоящий барьер этой задачи.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ASK, write = Permission.ASK)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val command = arguments.stringArrayArgument(COMMAND_ARGUMENT)
        if (command.isEmpty()) {
            return toolFailure("пустая команда: укажи программу и её аргументы списком")
        }
        val root = context.root
        CommandGuard(network).check(command, context)
        return launch(command, root)
    }

    /** Запускает уже проверенную команду; невозможность запуска — сбой, а не отказ. */
    private suspend fun launch(command: List<String>, root: Path): ToolResult = try {
        toolSuccess(render(runner.run(command, root, commandEnvironment(root))))
    } catch (error: IOException) {
        toolFailure("команду «${command.first()}» запустить не удалось: ${error.message}")
    }

    /** Текст для модели: код возврата и оба потока по отдельности, с пометкой об обрезке. */
    private fun render(result: CommandResult): String = buildString {
        append("код возврата: ").append(result.exitCode).append('\n')
        append("stdout:\n").append(result.stdout)
        append(terminator(result.stdout))
        if (result.stdoutTruncated) append(truncationNote("stdout"))
        append("stderr:\n").append(result.stderr)
        append(terminator(result.stderr))
        if (result.stderrTruncated) append(truncationNote("stderr"))
    }

    /** Перевод строки перед следующим разделом: без него последний не приклеится к выводу. */
    private fun terminator(text: String): String = if (text.isEmpty() || text.endsWith('\n')) "" else "\n"

    private fun truncationNote(stream: String): String =
        "[$stream обрезан: показаны первые $MAX_COMMAND_OUTPUT_BYTES байт из больших]\n"

    companion object {

        /** Имя инструмента: под ним он виден модели. */
        const val TOOL_NAME: String = "run_command"

        /** Имя аргумента со списком команды. */
        const val COMMAND_ARGUMENT: String = "command"

        /**
         * Предел времени команды.
         *
         * Больше общего предела инструмента (30 с у чтения файла): сборка, тесты и
         * `git`-операция законно идут дольше, и обрывать их на середине значило бы
         * объявлять таймаутом нормальную работу. Две минуты остаются границей, за
         * которой команда почти наверняка зависла.
         */
        const val COMMAND_TIMEOUT_MILLIS: Long = 120_000
    }
}

/**
 * Окружение команды: только перечисленные переменные, а не унаследованный набор.
 *
 * Ключ провайдера живёт в переменных окружения хоста (T-1.58), и команда с наследуемым
 * окружением прочитала бы его через `env` — «урезанное окружение» стало бы фикцией.
 * Поэтому передаются только `PATH`, локаль и часовой пояс ([PASSED_NAMES]), а `HOME`
 * и `TMPDIR` указывают внутрь воркспейса: так команда не пишет во временный каталог
 * хоста и не читает домашние файлы пользователя.
 *
 * @param host источник переменных; по умолчанию — окружение процесса хоста.
 */
internal fun commandEnvironment(root: Path, host: Map<String, String> = System.getenv()): Map<String, String> {
    val environment = LinkedHashMap<String, String>()
    PASSED_NAMES.forEach { name -> host[name]?.let { environment[name] = it } }
    environment[HOME_NAME] = root.toString()
    environment[TMPDIR_NAME] = root.toString()
    return environment
}

/** Переменные, которые команде нужны, чтобы найти программу и говорить на одном языке. */
private val PASSED_NAMES: List<String> = listOf("PATH", "LANG", "LC_ALL", "TZ")

private const val HOME_NAME: String = "HOME"

/** Имя каталога временных файлов: `java.io.tmpdir` читается как `TMPDIR` в Unix и `TEMP` в Windows. */
private const val TMPDIR_NAME: String = "TMPDIR"

/** Список команды из аргументов; элементы других типов — пустой список, отказ объяснит схема. */
private fun JsonObject.stringArrayArgument(name: String): List<String> =
    (this[name] as? JsonArray)
        ?.mapNotNull { (it as? JsonPrimitive)?.takeIf(JsonPrimitive::isString)?.content }
        .orEmpty()
