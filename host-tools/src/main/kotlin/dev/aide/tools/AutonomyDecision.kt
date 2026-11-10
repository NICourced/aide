package dev.aide.tools

import dev.aide.domain.AutonomyMode
import dev.aide.domain.RiskLevel
import dev.aide.tools.permission.PermissionDecision

/** Свойства вызова, влияющие на решение режима автономности. */
data class AutonomyCall(
    val changesRepository: Boolean,
    val executionOnly: Boolean,
    val isProposable: Boolean,
    val risk: RiskLevel,
)

/** Решение точки вызова с учётом режима автономности. */
sealed interface AutonomyOutcome {

    /** Выполнить вызов обычным порядком: с точкой отката, если вызов меняет репозиторий. */
    data object Execute : AutonomyOutcome

    /** Показать диалог подтверждения и ждать решения пользователя. */
    data object Ask : AutonomyOutcome

    /** Сохранить изменение как предложение, не применяя к файлам. */
    data object Propose : AutonomyOutcome

    /** Отказать; [reason] — понятный модели текст. */
    data class Deny(val reason: String) : AutonomyOutcome
}

/**
 * Матрица решений по режимам автономности (T-1.15, § 5.3.1).
 *
 * Явный `DENY` важнее любого режима и отклоняет вызов без предложения. Явное `ALLOW` —
 * уже данное разрешение, поэтому режим «спрашивать перед изменениями» не дублирует его
 * (инвариант `ALLOW_SESSION` из T-1.13). Режим передаётся из конкретного `AgentRun`,
 * а не читается из текущей настройки (FR-AGENT-5).
 */
object AutonomyDecision {

    /** Решает, что делать с вызовом [call] при текущем разрешении [permission]. */
    fun decide(
        mode: AutonomyMode,
        permission: PermissionDecision,
        call: AutonomyCall,
    ): AutonomyOutcome = when (permission) {
        is PermissionDecision.Deny -> denySettings(permission)
        else -> byMode(mode, permission, call)
    }

    private fun byMode(
        mode: AutonomyMode,
        permission: PermissionDecision,
        call: AutonomyCall,
    ): AutonomyOutcome = when (mode) {
        AutonomyMode.SUGGEST_ONLY -> suggestOnly(permission, call)
        AutonomyMode.ASK_BEFORE_CHANGES -> askBeforeChanges(permission, call)
        AutonomyMode.AUTO_APPLY_SAFE -> autoApplySafe(permission, call)
        AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS -> fullAuto(permission)
    }

    private fun suggestOnly(permission: PermissionDecision, call: AutonomyCall): AutonomyOutcome = when {
        !call.changesRepository -> normalRights(permission)
        call.isProposable -> AutonomyOutcome.Propose
        else -> AutonomyOutcome.Deny(
            "режим «только предлагать» не может сохранить изменение этого инструмента как предложение",
        )
    }

    private fun askBeforeChanges(permission: PermissionDecision, call: AutonomyCall): AutonomyOutcome = when {
        permission == PermissionDecision.Allow -> AutonomyOutcome.Execute
        call.changesRepository || call.executionOnly -> AutonomyOutcome.Ask
        else -> normalRights(permission)
    }

    private fun autoApplySafe(permission: PermissionDecision, call: AutonomyCall): AutonomyOutcome = when {
        !call.changesRepository && !call.executionOnly -> normalRights(permission)
        call.changesRepository -> when {
            call.risk == RiskLevel.SAFE -> AutonomyOutcome.Execute
            call.isProposable -> AutonomyOutcome.Propose
            else -> AutonomyOutcome.Deny(
                "изменение уровня риска ${call.risk.name} не может быть применено автоматически " +
                    "и не представимо как предложение",
            )
        }

        else -> normalRights(permission)
    }

    private fun fullAuto(permission: PermissionDecision): AutonomyOutcome = when (permission) {
        PermissionDecision.Ask, PermissionDecision.Allow -> AutonomyOutcome.Execute
        is PermissionDecision.Deny -> denySettings(permission)
    }

    private fun normalRights(permission: PermissionDecision): AutonomyOutcome = when (permission) {
        PermissionDecision.Ask -> AutonomyOutcome.Ask
        PermissionDecision.Allow -> AutonomyOutcome.Execute
        is PermissionDecision.Deny -> denySettings(permission)
    }

    private fun denySettings(permission: PermissionDecision.Deny): AutonomyOutcome =
        AutonomyOutcome.Deny("вызов запрещён настройками (${permission.reason.name})")
}
