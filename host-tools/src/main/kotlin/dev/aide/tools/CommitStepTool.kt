package dev.aide.tools

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.file.stringArgument
import dev.aide.tools.permission.ToolKind
import dev.aide.tools.ports.StepCommit
import dev.aide.tools.ports.StepCommits
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Внутренний инструмент фиксации шага (T-1.11, О-3).
 *
 * Вызывается движком после изменяющего шага, а не моделью: показывать модели решение
 * «когда фиксировать» незачем, и в определения для неё инструмент не попадает
 * ([ToolVisibility.INTERNAL]). Работа — один шаг порта [StepCommits]: `host-tools`
 * о git не знает (О-1), а имени ветки и сообщения у порта нет и быть не может.
 *
 * Права и точка отката для него не спрашиваются (см. `ToolInvoker`): изменяющее действие
 * уже одобрено (`write_file`), а второй вопрос за то же действие запрещает О-4.
 */
class CommitStepTool(private val commits: StepCommits) : AgentTool {

    override val name: String = TOOL_NAME

    override val description: String =
        "Зафиксировать изменения шага коммитом в ветке задачи. Вызывается движком прогона, " +
            "а не моделью."

    override val argumentsSchema: JsonObject = buildJsonObject {
        put("type", "object")
        putJsonObject("properties") {
            putJsonObject(BRANCH_ARGUMENT) {
                put("type", "string")
                put("description", "Ветка задачи, в которую попадает коммит")
            }
            putJsonObject(MESSAGE_ARGUMENT) {
                put("type", "string")
                put("description", "Сообщение коммита: номер шага и его краткое описание")
            }
        }
        putJsonArray("required") {
            add(BRANCH_ARGUMENT)
            add(MESSAGE_ARGUMENT)
        }
    }

    override val kind: ToolKind = ToolKind.WRITE

    override val visibility: ToolVisibility = ToolVisibility.INTERNAL

    /**
     * Объявленное умолчание здесь не действует: у внутреннего инструмента права
     * не спрашиваются вовсе (О-4). Значение — не решение, а выполнение контракта.
     */
    override val declaredPermission: ToolPermission =
        ToolPermission(tool = TOOL_NAME, read = Permission.ALLOW, write = Permission.ALLOW)

    override suspend fun execute(arguments: JsonObject, context: ToolContext): ToolResult {
        val branch = arguments.stringArgument(BRANCH_ARGUMENT)
        val message = arguments.stringArgument(MESSAGE_ARGUMENT)
        return when (val outcome = commits.commit(branch, message)) {
            is StepCommit.Committed -> toolSuccess("шаг зафиксирован: ${outcome.hash}")
            StepCommit.NothingToCommit -> toolSuccess("изменений нет: коммит шага не создан")
            is StepCommit.Refused -> toolFailure("коммит шага не сделан (${outcome.reason})")
        }
    }

    companion object {

        /** Имя внутреннего инструмента: по нему его находит точка вызова. */
        const val TOOL_NAME: String = "commit_step"

        /** Имена аргументов: по ним же собирается схема и аргументы вызова у движка. */
        const val BRANCH_ARGUMENT: String = "branch"
        const val MESSAGE_ARGUMENT: String = "message"

        /**
         * Аргументы вызова строкой — так их собирает движок прогона.
         *
         * Схема и сборка аргументов живут рядом: разойдясь, они дали бы вызов, который
         * точка вызова отвергает по своей же схеме.
         */
        fun arguments(branch: String, message: String): String = buildJsonObject {
            put(BRANCH_ARGUMENT, branch)
            put(MESSAGE_ARGUMENT, message)
        }.toString()
    }
}
