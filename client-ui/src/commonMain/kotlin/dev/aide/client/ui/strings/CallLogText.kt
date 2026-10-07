package dev.aide.client.ui.strings

import dev.aide.domain.ToolOutcome
import org.jetbrains.compose.resources.StringResource

/**
 * Текст исхода вызова журнала из ресурсов (NFR-13).
 *
 * Одна функция на весь словарь [ToolOutcome]: исход показывается и в строке списка,
 * и (позже) в раскрытой записи, и второй перевод разошёлся бы с первым.
 */
fun callLogOutcomeResource(outcome: ToolOutcome): StringResource = when (outcome) {
    ToolOutcome.SUCCESS -> Strings.callLogOutcomeSuccess
    ToolOutcome.FAILURE -> Strings.callLogOutcomeFailure
    ToolOutcome.DENIED -> Strings.callLogOutcomeDenied
    ToolOutcome.TIMEOUT -> Strings.callLogOutcomeTimeout
}
