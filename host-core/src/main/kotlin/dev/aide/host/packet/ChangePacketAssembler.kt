package dev.aide.host.packet

import dev.aide.agent.ports.ChangePackets
import dev.aide.agent.ports.PacketAssemblyException
import dev.aide.domain.AgentRun
import dev.aide.domain.ChangePacket
import dev.aide.domain.ChangeProposal
import dev.aide.domain.ChangeSource
import dev.aide.domain.FileChange
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.PacketId
import dev.aide.domain.PacketStatus
import dev.aide.domain.RiskLevel
import dev.aide.domain.Task
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import dev.aide.host.diff.LineDiffEngine
import dev.aide.host.risk.DeterministicChangeRisk
import dev.aide.host.server.ClientSessions
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.protocol.HostEvent
import kotlinx.coroutines.CancellationException
import kotlinx.datetime.Instant

/**
 * Сборка и сохранение неизменяемого пакета изменений после прогона (T-1.24).
 *
 * Пакет собирается из git-diff между базовой и задачной ветками и из очереди
 * неприменённых предложений по задаче. Доработка создаёт новую ревизию;
 * прежняя остаётся неизменной в базе.
 */
class ChangePacketAssembler(
    private val workspaces: OpenWorkspaces,
    private val store: HostStore,
    private val sessions: ClientSessions,
    private val clock: () -> Instant,
    private val diffEngine: LineDiffEngine = LineDiffEngine(),
) : ChangePackets {

    @Suppress("TooGenericExceptionCaught")
    // точка входа: любая непредвиденная ошибка сборки оборачивается в PacketAssemblyException
    override suspend fun publish(task: Task, run: AgentRun): ChangePacket = try {
        assemble(task, run)
    } catch (cancel: CancellationException) {
        throw cancel
    } catch (error: PacketAssemblyException) {
        throw error
    } catch (error: Exception) {
        throw PacketAssemblyException("сборка пакета не удалась: ${error.message}", error)
    }

    private suspend fun assemble(task: Task, run: AgentRun): ChangePacket {
        val opened = workspaces.current() ?: throw PacketAssemblyException("нет открытого воркспейса")
        val baseBranch = task.baseBranch ?: throw PacketAssemblyException("базовая ветка задачи не записана")

        val changeSet = opened.git.changeSets.read(baseBranch, task.branch)
        val diffFiles = changeSet.files.map { diffEngine.diff(it) }
        val proposals = store.proposals.byTask(task.id)
        val merged = mergeFiles(diffFiles, proposals)

        val risk = packetRisk(run, merged, proposals)
        val tests = packetTests(run)
        val packet = ChangePacket(
            id = PacketId(task.id.value),
            revision = store.packets.latestRevision(PacketId(task.id.value)) + 1,
            taskId = task.id,
            title = task.title,
            summary = run.plan.joinToString("; ") { it.summary },
            files = merged,
            risk = risk,
            tests = tests,
            source = ChangeSource.AGENT,
            status = PacketStatus.AWAITING_REVIEW,
            branch = task.branch,
            baseCommit = changeSet.baseCommit,
            headCommit = changeSet.headCommit,
            snapshotRef = run.snapshots.firstOrNull(),
            createdAt = clock(),
        )
        store.packets.save(packet)
        sessions.broadcast(HostEvent.ChangePacketRecorded(packet))
        return packet
    }

    private fun mergeFiles(diffFiles: List<FileChange>, proposals: List<ChangeProposal>): List<FileChange> {
        val byPath = linkedMapOf<String, MutableList<FileChange>>()
        diffFiles.forEach { byPath.getOrPut(it.path) { mutableListOf() } += it }
        proposals.flatMap { it.files }.forEach { byPath.getOrPut(it.path) { mutableListOf() } += it }
        return byPath.map { (_, list) -> if (list.size == 1) list.single() else mergeFileChanges(list) }
    }

    private fun mergeFileChanges(changes: List<FileChange>): FileChange {
        val path = changes.last().path
        val hunks = changes.flatMapIndexed { fileIndex, fileChange ->
            fileChange.hunks.mapIndexed { hunkIndex, hunk ->
                hunk.withId(HunkId("${hunk.id.value}#${fileIndex + 1}.${hunkIndex + 1}"))
            }
        }
        return FileChange(
            path = path,
            changeKind = changes.last().changeKind,
            hunks = hunks,
            contextGaps = emptyList(),
            addedLines = changes.sumOf { it.addedLines },
            removedLines = changes.sumOf { it.removedLines },
            previousPath = changes.first().previousPath,
        )
    }

    private fun Hunk.withId(id: HunkId): Hunk = copy(id = id)

    private suspend fun packetRisk(run: AgentRun, files: List<FileChange>, proposals: List<ChangeProposal>): RiskLevel {
        if (files.isEmpty() && proposals.isEmpty()) return RiskLevel.NORMAL
        val proposalRisks = proposals.map { it.risk }
        val diffRisk = DeterministicChangeRisk(store.runs).assess(run.id, "change_packet", files)
        return (proposalRisks + diffRisk).maxByOrNull { it.ordinal } ?: RiskLevel.NORMAL
    }

    private fun packetTests(run: AgentRun): TestStatus {
        val report = run.testReport
        return if (report == null) {
            TestStatus(state = TestState.NOT_RUN)
        } else {
            TestStatus(
                state = report.state ?: TestState.NOT_RUN,
                failed = report.failures.map { it.name },
                lintFindings = report.lintFindings?.size ?: 0,
            )
        }
    }
}
