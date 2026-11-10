package dev.aide.host.store

import dev.aide.domain.ChangeProposal
import dev.aide.domain.ProposalId
import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCallId
import dev.aide.host.store.db.HostDatabase
import dev.aide.tools.ports.ChangeProposals

/**
 * Очередь неприменённых файловых предложений в хранилище хоста (T-1.15).
 *
 * Предложения хранятся целиком в `payload`; идентификаторы прогона и вызова
 * вынесены в колонки, чтобы можно было искать предложения по прогону без
 * разбора CBOR. Удаление предложений не поддерживается: пакет ревью (T-1.24)
 * сам решит, когда предложение становится частью пакета.
 */
class ChangeProposalStore internal constructor(private val database: HostDatabase) : ChangeProposals {

    /** Сохраняет неизменяемое предложение; повторный тот же `id` отклоняется базой. */
    override fun save(proposal: ChangeProposal) {
        database.changeProposalQueries.insert(
            id = proposal.id.value,
            run_id = proposal.runId.value,
            call_id = proposal.callId.value,
            tool = proposal.tool,
            risk = proposal.risk.name,
            created_at = proposal.createdAt.toEpochMilliseconds(),
            payload = StoreCodec.encode(ChangeProposal.serializer(), proposal),
        )
    }

    /** Читает предложение по идентификатору; неизвестный идентификатор даёт null. */
    fun load(id: ProposalId): ChangeProposal? =
        database.changeProposalQueries.byId(id.value).executeAsOneOrNull()?.let(RowMapper::changeProposal)

    /** Все предложения прогона в порядке создания. */
    fun byRun(runId: RunId): List<ChangeProposal> =
        database.changeProposalQueries.byRun(runId.value).executeAsList().map(RowMapper::changeProposal)

    /** Все предложения задачи, включая разные её прогоны, в порядке создания. */
    fun byTask(taskId: TaskId): List<ChangeProposal> =
        database.changeProposalQueries.byTask(taskId.value).executeAsList().map(RowMapper::changeProposal)

    /** Предложение, созданное конкретным вызовом; null, если вызов ничего не предложил. */
    fun byCall(callId: ToolCallId): ChangeProposal? =
        database.changeProposalQueries.byCall(callId.value).executeAsOneOrNull()?.let(RowMapper::changeProposal)

    /** Все предложения хоста в порядке создания. */
    fun all(): List<ChangeProposal> =
        database.changeProposalQueries.all().executeAsList().map(RowMapper::changeProposal)

    /** Сколько предложений в базе. */
    fun count(): Long = database.changeProposalQueries.count().executeAsOne()
}
