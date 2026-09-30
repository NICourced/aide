package dev.aide.agent.llm

/**
 * Роль реплики в диалоге с моделью (T-1.7).
 *
 * Перечисление появилось вместе с циклом инструментов: пока шаг был одним вызовом
 * модели, хватало списка строк, но ответ ассистента с вызовами инструментов и их
 * результаты — разные роли, и отправить их одной означало бы сломать разговор
 * ровно на том месте, где модель просит файл.
 */
enum class LlmRole {
    /** Правила и роль агента: у chat completions реплика, у Anthropic — отдельное поле. */
    SYSTEM,

    /** Реплика пользователя: постановка задачи, план, задание шага. */
    USER,

    /** Ответ модели; может состоять из одних вызовов инструментов. */
    ASSISTANT,

    /** Результат выполненного инструмента: в диалоге обязан найтись по [LlmMessage.toolCallId]. */
    TOOL,
}

/**
 * Одна реплика диалога.
 *
 * Роль, текст и — для вызовов инструментов — их список или идентификатор результата.
 * Общий тип для всех протоколов и намеренно бедный: он не знает ни про блоки
 * Anthropic, ни про `tool_calls` chat completions, а только про то, что реплика
 * в диалоге означает. Перевод на формат протокола — дело адаптера (§ 3.2, О-2).
 */
data class LlmMessage(
    /** Кто говорит. */
    val role: LlmRole,
    /** Текст реплики; у ответа одним вызовом инструмента пустой. */
    val content: String = "",
    /** Идентификатор вызова, результат которого несёт реплика; только у роли [LlmRole.TOOL]. */
    val toolCallId: String? = null,
    /** Вызовы инструментов, которые просит модель; только у роли [LlmRole.ASSISTANT]. */
    val toolCalls: List<LlmToolCall> = emptyList(),
) {

    companion object {

        /** Системная реплика: правила и роль агента. */
        fun system(text: String): LlmMessage = LlmMessage(role = LlmRole.SYSTEM, content = text)

        /** Реплика пользователя. */
        fun user(text: String): LlmMessage = LlmMessage(role = LlmRole.USER, content = text)

        /**
         * Ответ модели.
         *
         * Вызовы инструментов едут вместе с текстом, а не вместо него: модель вправе
         * сказать «сейчас посмотрю» и попросить файл тем же ответом, и выбросить текст
         * значило бы показать пользователю диалог, которого модель не вела.
         */
        fun assistant(text: String, toolCalls: List<LlmToolCall> = emptyList()): LlmMessage =
            LlmMessage(role = LlmRole.ASSISTANT, content = text, toolCalls = toolCalls)

        /** Результат вызова инструмента: передаётся модели от имени того же вызова. */
        fun tool(callId: String, text: String): LlmMessage =
            LlmMessage(role = LlmRole.TOOL, content = text, toolCallId = callId)
    }
}
