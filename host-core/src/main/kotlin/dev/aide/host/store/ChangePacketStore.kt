package dev.aide.host.store

import dev.aide.domain.ChangePacket
import dev.aide.domain.PacketId
import dev.aide.domain.TaskId
import dev.aide.host.store.db.HostDatabase

/**
 * Хранилище неизменяемых пакетов изменений (T-1.24).
 *
 * Пакет не обновляется: доработка создаёт новую ревизию с тем же идентификатором,
 * а старая остаётся в истории (инвариант 2 § 4.1). Повторный `(id, revision)`
 * отклоняется базой.
 */
class ChangePacketStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет пакет; повторная запись с тем же `(id, revision)` отклоняется базой. */
    fun save(packet: ChangePacket) {
        database.changePacketQueries.insert(
            id = packet.id.value,
            revision = packet.revision.toLong(),
            task_id = packet.taskId.value,
            status = packet.status.name,
            risk = packet.risk.name,
            branch = packet.branch,
            created_at = packet.createdAt.toEpochMilliseconds(),
            payload = StoreCodec.encode(ChangePacket.serializer(), packet),
        )
    }

    /** Читает конкретную ревизию пакета; null, если такой пары нет. */
    fun load(id: PacketId, revision: Int): ChangePacket? =
        database.changePacketQueries.byIdRevision(id.value, revision.toLong())
            .executeAsOneOrNull()?.let(RowMapper::changePacket)

    /** Все ревизии пакетов задачи в порядке возрастания ревизии. */
    fun byTask(taskId: TaskId): List<ChangePacket> =
        database.changePacketQueries.byTask(taskId.value).executeAsList().map(RowMapper::changePacket)

    /** Последняя ревизия пакета; 0, если пакета ещё нет. */
    fun latestRevision(id: PacketId): Int =
        database.changePacketQueries.latestRevision(id.value).executeAsOne().toInt()

    /** Все пакеты хоста в порядке создания. */
    fun all(): List<ChangePacket> =
        database.changePacketQueries.all().executeAsList().map(RowMapper::changePacket)

    /** Сколько пакетов в базе. */
    fun count(): Long = database.changePacketQueries.count().executeAsOne()
}
