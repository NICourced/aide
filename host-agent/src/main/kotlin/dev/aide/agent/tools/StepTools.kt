package dev.aide.agent.tools

import dev.aide.agent.llm.LlmToolCall
import dev.aide.agent.llm.LlmToolDefinition
import dev.aide.domain.RunId
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.ToolResult

/**
 * Инструменты, доступные шагу прогона (T-1.7).
 *
 * Движку нужны от инструментов две вещи: то, что уезжает модели определениями, и одна
 * точка вызова. Держать их рядом обязательно — определения и вызов обязаны относиться
 * к одному реестру: разойдясь, они дали бы модели право звать инструмент, которого
 * точка вызова не знает, и отказ выглядел бы как «инструмента нет».
 *
 * Определения строятся из реестра, а не задаются списком: `LlmToolDefinition` —
 * транспортное описание протокола, и переводит на него инструмент тот, кто знает
 * оба типа, — этот класс.
 */
class StepTools private constructor(
    /** Определения инструментов в порядке реестра: они уезжают модели в каждом запросе. */
    val definitions: List<LlmToolDefinition>,
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

    companion object {

        /** Собирает инструменты шага из реестра хоста. */
        fun of(registry: ToolRegistry, invoker: ToolInvoker, context: ToolContext): StepTools =
            StepTools(registry.tools.map(::definitionOf), invoker, context)
    }
}

/** Описание инструмента для модели: имя, объяснение и схема аргументов. */
private fun definitionOf(tool: AgentTool): LlmToolDefinition = LlmToolDefinition(
    name = tool.name,
    description = tool.description,
    argumentsSchema = tool.argumentsSchema,
)
