package dev.aide.agent.tools

import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.domain.RunId
import dev.aide.domain.ToolOutcome
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.ToolResult
import dev.aide.tools.ToolVisibility
import dev.aide.tools.permission.ToolKind

/**
 * Инструменты, доступные шагу прогона (T-1.7).
 *
 * Движку нужны от инструментов три вещи: то, что уезжает модели определениями, одна
 * точка вызова и различение «меняет ли вызов файлы» (T-1.11). Держать их рядом
 * обязательно — определения, вызов и вид инструмента обязаны относиться к одному
 * реестру: разойдясь, они дали бы модели право звать инструмент, которого точка вызова
 * не знает, а шаг — коммит не тем инструментом.
 *
 * Определения строятся из реестра, а не задаются списком: `LlmToolDefinition` —
 * транспортное описание протокола, и переводит на него инструмент тот, кто знает
 * оба типа, — этот класс. В определения попадают только [ToolVisibility.MODEL]:
 * внутренний инструмент модели не показывают (T-1.11).
 */
class StepTools private constructor(
    /** Определения инструментов для модели, в порядке реестра: уезжают в каждом запросе. */
    val definitions: List<LlmToolDefinition>,
    /** Все инструменты реестра по имени: по виду вызова решается, изменяющий ли шаг. */
    private val tools: Map<String, AgentTool>,
    private val invoker: ToolInvoker,
    private val context: ToolContext,
) {

    /**
     * Выполняет вызов, предложенный моделью; идентификатор вызова берётся у неё.
     *
     * Результат, а не исключение: отказ инструмента — такое же окончание вызова,
     * как и успех, и диалог продолжается в обоих случаях (О-3).
     */
    suspend fun invoke(runId: RunId, call: LlmToolCall): ToolResult =
        invoker.invoke(runId = runId, tool = call.name, arguments = call.arguments, context = context)

    /**
     * Выполняет внутренний инструмент, которого модель не видит (T-1.11).
     *
     * Через ту же точку вызова, что и вызовы модели, но по хост-пути: журнал, таймаут и
     * замер времени обязаны быть одинаковыми для любого инструмента, а обход точки вызова
     * завёл бы вторую, где что-нибудь из этого забывается (О-3). Хост-путь — единственный,
     * где внутренний инструмент исполняется: из модельного он недоступен по имени.
     */
    suspend fun invokeInternal(runId: RunId, name: String, arguments: String): ToolResult =
        invoker.invokeInternal(runId = runId, tool = name, arguments = arguments, context = context)

    /**
     * Состоялось ли изменение файлов вызовом [call].
     *
     * Мало того, что инструмент изменяющий по виду: нужно, чтобы вызов **прошёл** —
     * отклонённый правами, схемой или пределом ничего не изменил, и коммитить после него
     * нечего. Поэтому берётся исход: `snapshotRef` не годится — точка отката ставится до
     * выполнения, и у изменяющего вызова, упавшего внутри (например, по пути каталог),
     * ссылка тоже есть, хотя файл не тронут.
     *
     * Вид знает реестр, а не движок: список «что считается записью» обязан совпадать
     * с тем, по какой оси инструмент спрашивает права (FR-TOOLS-8), и второй такой список
     * разошёлся бы с первым. Неизвестное имя изменяющим не считается.
     */
    fun isChanging(call: LlmToolCall, result: ToolResult): Boolean =
        result.outcome == ToolOutcome.SUCCESS && tools[call.name]?.kind == ToolKind.WRITE

    companion object {

        /** Собирает инструменты шага из реестра хоста. */
        fun of(registry: ToolRegistry, invoker: ToolInvoker, context: ToolContext): StepTools {
            val visible = registry.tools.filter { it.visibility == ToolVisibility.MODEL }
            return StepTools(
                definitions = visible.map(::definitionOf),
                tools = registry.tools.associateBy { it.name },
                invoker = invoker,
                context = context,
            )
        }
    }
}

/** Описание инструмента для модели: имя, объяснение и схема аргументов. */
private fun definitionOf(tool: AgentTool): LlmToolDefinition = LlmToolDefinition(
    name = tool.name,
    description = tool.description,
    argumentsSchema = tool.argumentsSchema,
)
