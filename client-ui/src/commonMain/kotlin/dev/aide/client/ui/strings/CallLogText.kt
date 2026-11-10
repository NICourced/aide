package dev.aide.client.ui.strings

import dev.aide.domain.ApprovalDecision
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
    ToolOutcome.PROPOSED -> Strings.callLogOutcomeProposed
}

/**
 * Текст решения пользователя по запросу подтверждения вызова (T-1.13).
 *
 * Одна функция на весь словарь [ApprovalDecision]: решение показывается в строке журнала,
 * чтобы ревью видело, разрешили ли вызов и на какой срок.
 */
fun callLogApprovalResource(decision: ApprovalDecision): StringResource = when (decision) {
    ApprovalDecision.ALLOW_ONCE -> Strings.callLogApprovalAllowedOnce
    ApprovalDecision.ALLOW_SESSION -> Strings.callLogApprovalAllowedSession
    ApprovalDecision.ALLOW_ALWAYS -> Strings.callLogApprovalAllowedAlways
    ApprovalDecision.DENY -> Strings.callLogApprovalDenied
}
