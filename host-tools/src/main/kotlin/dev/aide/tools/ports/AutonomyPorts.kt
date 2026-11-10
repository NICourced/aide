package dev.aide.tools.ports

import dev.aide.domain.ApprovalDecision
import dev.aide.domain.RiskLevel

/** Порты режимов автономности: подтверждение, доверенный риск и очередь предложений. */
data class AutonomyPorts(
    val approval: ToolApproval = ToolApproval { _, _ -> ApprovalDecision.DENY },
    val risk: ChangeRisk = ChangeRisk { _, _, _, _ -> RiskLevel.NORMAL },
    val proposals: ChangeProposals = ChangeProposals {
        error("Очередь предложений не подключена: изменение не сохранено")
    },
)
