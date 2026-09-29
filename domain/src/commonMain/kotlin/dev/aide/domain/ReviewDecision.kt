package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Решение пользователя по пакету или по отдельному блоку (§ 4). */
@Serializable
data class ReviewDecision(
    /** Пакет, к которому относится решение. */
    val packetId: PacketId,
    /** Ревизия пакета на момент решения; к новой ревизии решение не применяется (§ 9, правило 2). */
    val packetRevision: Int,
    /** Уровень решения: весь пакет или отдельный блок. */
    val scope: DecisionScope,
    /** Идентификатор блока при [scope] = [DecisionScope.HUNK]; null при [scope] = [DecisionScope.PACKET]. */
    val targetHunkId: HunkId? = null,
    /** Что решил пользователь. */
    val value: DecisionValue,
    /** Комментарий пользователя при возврате на доработку (FR-INBOX-13). */
    val comment: String? = null,
    /** Платформа клиента, принявшего решение; нужна для продуктовых метрик (§ 2.3). */
    val clientPlatform: ClientPlatform,
    /** Момент решения. */
    val decidedAt: Instant,
)
