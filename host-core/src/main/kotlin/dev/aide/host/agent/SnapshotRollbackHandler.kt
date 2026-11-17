package dev.aide.host.agent

import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotRef
import dev.aide.host.git.SnapshotRollbackOutcome
import dev.aide.host.git.SnapshotRollbackRefusal
import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.ClientSessions
import dev.aide.host.store.RunStore
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SnapshotRollbackRejection
import dev.aide.protocol.WorkspaceId
import dev.aide.protocol.requestIdOrNull
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Обработчик списка снапшотов, предпросмотра и отката к снапшоту (T-1.6).
 *
 * Связь снапшота с задачей строится через [RunStore]: снапшоты создаются в прогоне,
 * и прогон знает свою задачу. Git-слой не знает о задачах, поэтому обогащение
 * происходит здесь, а не в репозитории.
 */
class SnapshotRollbackHandler(
    private val workspaces: OpenWorkspaces,
    private val runs: RunStore,
    private val sessions: ClientSessions,
) : ClientMessageHandler {

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.SnapshotList -> withContext(Dispatchers.IO) {
            handleList(message)
        }

        is ClientMessage.SnapshotPreviewRequest -> withContext(Dispatchers.IO) {
            handlePreview(message)
        }

        is ClientMessage.SnapshotRevert -> withContext(Dispatchers.IO) {
            handleRevert(message)
        }

        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("snapshot"),
            error = ProtocolError.Internal("SnapshotRollbackHandler получил сообщение другого вида"),
        )
    }

    private fun handleList(message: ClientMessage.SnapshotList): HostMessage {
        val workspace = workspaces.find(message.workspaceId)
            ?: return workspaceClosed(message.requestId, message.workspaceId)
        val snapshots = workspace.git.rollback.list().map { it.withTaskId() }
        return HostMessage.SnapshotListResponse(
            requestId = message.requestId,
            snapshots = snapshots,
        )
    }

    private fun handlePreview(message: ClientMessage.SnapshotPreviewRequest): HostMessage {
        val workspace = workspaces.find(message.workspaceId)
            ?: return workspaceClosed(message.requestId, message.workspaceId)
        return when (val outcome = workspace.git.rollback.preview(message.ref)) {
            is SnapshotRollbackOutcome.Success -> HostMessage.SnapshotPreviewResponse(
                requestId = message.requestId,
                preview = outcome.value.copy(
                    snapshot = outcome.value.snapshot.withTaskId(),
                ),
            )

            is SnapshotRollbackOutcome.Refused -> HostMessage.Failure(
                requestId = message.requestId,
                error = ProtocolError.SnapshotRollbackRejected(outcome.reason.toProtocol()),
            )
        }
    }

    private suspend fun handleRevert(message: ClientMessage.SnapshotRevert): HostMessage =
        workspaces.find(message.workspaceId)?.let { workspace ->
            when {
                runs.unfinished().isNotEmpty() -> HostMessage.Failure(
                    requestId = message.requestId,
                    error = ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.ACTIVE_RUN),
                )

                else -> when (val outcome = workspace.git.rollback.revert(message.ref, message.expectedHead)) {
                    is SnapshotRollbackOutcome.Success -> {
                        sessions.broadcast(HostEvent.WorkspaceChanged(message.workspaceId))
                        HostMessage.SnapshotReverted(
                            requestId = message.requestId,
                            ref = message.ref,
                            commit = outcome.value.commit,
                        )
                    }

                    is SnapshotRollbackOutcome.Refused -> HostMessage.Failure(
                        requestId = message.requestId,
                        error = ProtocolError.SnapshotRollbackRejected(outcome.reason.toProtocol()),
                    )
                }
            }
        } ?: workspaceClosed(message.requestId, message.workspaceId)

    /** Находит задачу, к которой относится снапшот, по прогонам в хранилище. */
    private fun Snapshot.withTaskId(): Snapshot {
        val taskId = runs.all()
            .firstOrNull { run -> run.snapshots.any { it.value == ref.value } }
            ?.taskId
        return copy(taskId = taskId)
    }

    private fun SnapshotRollbackRefusal.toProtocol(): SnapshotRollbackRejection = when (this) {
        SnapshotRollbackRefusal.UNKNOWN_SNAPSHOT -> SnapshotRollbackRejection.UNKNOWN_SNAPSHOT
        SnapshotRollbackRefusal.NOT_IN_CURRENT_BRANCH -> SnapshotRollbackRejection.NOT_IN_CURRENT_BRANCH
        SnapshotRollbackRefusal.DIRTY_WORKTREE -> SnapshotRollbackRejection.DIRTY_WORKTREE
        SnapshotRollbackRefusal.HEAD_CHANGED -> SnapshotRollbackRejection.HEAD_CHANGED
        SnapshotRollbackRefusal.NOTHING_TO_REVERT -> SnapshotRollbackRejection.NOTHING_TO_REVERT
        SnapshotRollbackRefusal.GIT_FAILED -> SnapshotRollbackRejection.GIT_FAILED
    }

    private fun workspaceClosed(requestId: RequestId, workspaceId: WorkspaceId): HostMessage =
        HostMessage.Failure(
            requestId = requestId,
            error = ProtocolError.WorkspaceClosed(workspaceId),
        )
}
