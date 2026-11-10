package dev.aide.tools

import dev.aide.domain.AutonomyMode
import dev.aide.domain.RiskLevel
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

/** Матрица режима × ось вызова × право × риск (T-1.15). */
class AutonomyDecisionTest {

    @Test
    fun `SUGGEST_ONLY предлагает запись, а чтение и исполнение оставляет обычным правам`() {
        assertEquals(AutonomyOutcome.Propose, decide(AutonomyMode.SUGGEST_ONLY, call(changesRepository = true)))
        assertEquals(
            AutonomyOutcome.Ask,
            decide(AutonomyMode.SUGGEST_ONLY, call(changesRepository = false), PermissionDecision.Ask),
        )
        assertEquals(
            AutonomyOutcome.Ask,
            decide(
                AutonomyMode.SUGGEST_ONLY,
                call(changesRepository = false, executionOnly = true),
                PermissionDecision.Ask,
            ),
        )
        assertIs<AutonomyOutcome.Deny>(
            decide(AutonomyMode.SUGGEST_ONLY, call(changesRepository = true, isProposable = false)),
        )
    }

    @Test
    fun `ASK_BEFORE_CHANGES спрашивает ASK, но не дублирует явное ALLOW`() {
        assertEquals(
            AutonomyOutcome.Ask,
            decide(AutonomyMode.ASK_BEFORE_CHANGES, call(changesRepository = true), PermissionDecision.Ask),
        )
        assertEquals(
            AutonomyOutcome.Ask,
            decide(
                AutonomyMode.ASK_BEFORE_CHANGES,
                call(changesRepository = false, executionOnly = true),
                PermissionDecision.Ask,
            ),
        )
        assertEquals(AutonomyOutcome.Execute, decide(AutonomyMode.ASK_BEFORE_CHANGES, call(changesRepository = true)))
        assertEquals(
            AutonomyOutcome.Ask,
            decide(AutonomyMode.ASK_BEFORE_CHANGES, call(changesRepository = false), PermissionDecision.Ask),
        )
    }

    @Test
    fun `AUTO_APPLY_SAFE применяет только SAFE, а остальное предлагает без вопроса`() {
        assertEquals(
            AutonomyOutcome.Execute,
            decide(AutonomyMode.AUTO_APPLY_SAFE, call(changesRepository = true, risk = RiskLevel.SAFE)),
        )
        assertEquals(
            AutonomyOutcome.Propose,
            decide(
                AutonomyMode.AUTO_APPLY_SAFE,
                call(changesRepository = true, risk = RiskLevel.NORMAL),
                PermissionDecision.Ask,
            ),
        )
        assertEquals(
            AutonomyOutcome.Propose,
            decide(AutonomyMode.AUTO_APPLY_SAFE, call(changesRepository = true, risk = RiskLevel.RISKY)),
        )
        assertIs<AutonomyOutcome.Deny>(
            decide(AutonomyMode.AUTO_APPLY_SAFE, call(changesRepository = true, isProposable = false)),
        )
    }

    @Test
    fun `AUTO_APPLY_SAFE не раздаёт автоматическое SAFE исполнению или чтению`() {
        assertEquals(
            AutonomyOutcome.Ask,
            decide(
                AutonomyMode.AUTO_APPLY_SAFE,
                call(changesRepository = false, executionOnly = true, risk = RiskLevel.SAFE),
                PermissionDecision.Ask,
            ),
        )
        assertEquals(
            AutonomyOutcome.Execute,
            decide(AutonomyMode.AUTO_APPLY_SAFE, call(changesRepository = false, risk = RiskLevel.SAFE)),
        )
    }

    @Test
    fun `FULL_AUTO исполняет ASK и ALLOW без подтверждения`() {
        for (changes in listOf(false, true)) {
            assertEquals(
                AutonomyOutcome.Execute,
                decide(
                    AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS,
                    call(changesRepository = changes),
                    PermissionDecision.Ask,
                ),
            )
            assertEquals(
                AutonomyOutcome.Execute,
                decide(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, call(changesRepository = changes)),
            )
        }
    }

    @Test
    fun `запрет настроек отклоняет вызов в любом режиме`() {
        for (mode in AutonomyMode.entries) {
            val outcome = AutonomyDecision.decide(
                mode = mode,
                permission = PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS),
                call = AutonomyCall(
                    changesRepository = true,
                    executionOnly = false,
                    isProposable = true,
                    risk = RiskLevel.SAFE,
                ),
            )
            assertIs<AutonomyOutcome.Deny>(outcome, "режим $mode не должен обходить запрет настроек")
        }
    }

    private fun decide(
        mode: AutonomyMode,
        call: AutonomyCall,
        permission: PermissionDecision = PermissionDecision.Allow,
    ): AutonomyOutcome = AutonomyDecision.decide(mode, permission, call)

    private fun call(
        changesRepository: Boolean,
        executionOnly: Boolean = false,
        isProposable: Boolean = true,
        risk: RiskLevel = RiskLevel.NORMAL,
    ): AutonomyCall = AutonomyCall(changesRepository, executionOnly, isProposable, risk)
}
