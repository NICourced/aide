package dev.aide.host.store

import dev.aide.domain.ClientPlatform
import dev.aide.domain.PacketId
import dev.aide.domain.ReviewDecision
import dev.aide.host.store.db.HostDatabase

/**
 * Решения ревью в хранилище хоста (§ 9, § 2.3).
 *
 * Решение привязано к ревизии пакета: к новой ревизии оно не применяется
 * (§ 9, правило 2). Платформа клиента, принявшего решение, лежит в колонке —
 * по ней считается продуктовая метрика «доля задач, закрытых с телефона».
 */
class DecisionStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет решение ревью. */
    fun save(decision: ReviewDecision) {
        database.reviewDecisionQueries.insert(
            packet_id = decision.packetId.value,
            packet_revision = decision.packetRevision.toLong(),
            scope = decision.scope.name,
            target_hunk_id = decision.targetHunkId?.value,
            // Колонка `value` — ключевое слово Kotlin, генератор добавляет подчёркивание.
            value_ = decision.value.name,
            client_platform = decision.clientPlatform.name,
            decided_at = decision.decidedAt.toEpochMilliseconds(),
            payload = StoreCodec.encode(ReviewDecision.serializer(), decision),
        )
    }

    /** Читает решения по пакету от старых к новым; при [revision] не null — только по этой ревизии. */
    fun forPacket(packetId: PacketId, revision: Int? = null): List<ReviewDecision> {
        val rows = if (revision == null) {
            database.reviewDecisionQueries.byPacket(packetId.value).executeAsList()
        } else {
            database.reviewDecisionQueries.byPacketAndRevision(packetId.value, revision.toLong()).executeAsList()
        }
        return rows.map(RowMapper::decision)
    }

    /** Читает решения, принятые с указанной платформы, от старых к новым (§ 2.3). */
    fun byPlatform(platform: ClientPlatform): List<ReviewDecision> =
        database.reviewDecisionQueries.byPlatform(platform.name).executeAsList().map(RowMapper::decision)

    /**
     * Число решений по платформам — сырьё метрики «доля задач, закрытых с телефона» (§ 2.3).
     *
     * Ключ — имя платформы, а не [ClientPlatform]: запись, сделанная версией хоста
     * со словарём шире текущего, не должна ронять подсчёт метрики.
     */
    fun countsByPlatform(): Map<String, Long> =
        database.reviewDecisionQueries.countByPlatform().executeAsList()
            .associate { it.client_platform to it.decisions }
}
