package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.host.git.TaskBranchOutcome
import dev.aide.host.git.TaskBranchRefusal
import dev.aide.host.server.ClientSessions
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.protocol.HostEvent

/**
 * Порт ветки задачи поверх открытого воркспейса (T-1.18).
 *
 * Живёт в `host-core`, а не в движке: переключить репозиторий — значит знать, какой
 * воркспейс открыт, а это знание хостовое (О-1). Здесь же причина отказа git
 * переводится в код словаря [RunInterruptReason]: git-слой о прогонах не знает,
 * а движок о git.
 *
 * После успешного переключения рассылается [HostEvent.WorkspaceChanged] — тем же
 * механизмом, что после открытия репозитория. Шапка клиента показывает ветку из
 * состояния хоста, и без события она осталась бы прежней. Событие уходит и когда
 * ветка уже была: клиент не знает, переключались ли мы, а лишний перезапрос
 * состояния безвреден — потерянное событие оставило бы его с чужой веткой.
 *
 * @param workspaces открытые воркспейсы хоста; движок работает в текущем из них.
 * @param sessions активные сессии: им рассылается событие о смене состояния.
 */
class TaskBranchGuard(
    private val workspaces: OpenWorkspaces,
    private val sessions: ClientSessions,
) : TaskBranches {

    override suspend fun ensure(branch: String): TaskBranch {
        val opened = workspaces.current() ?: return TaskBranch.Refused(RunInterruptReason.NO_WORKSPACE)
        return when (val outcome = opened.git.ensureTaskBranch(branch)) {
            is TaskBranchOutcome.Created -> announced(opened, TaskBranch.Created(outcome.base))
            TaskBranchOutcome.Existing -> announced(opened, TaskBranch.Existing)
            is TaskBranchOutcome.Refused -> TaskBranch.Refused(outcome.reason.code)
        }
    }

    /** Рассылает событие о смене состояния воркспейса и возвращает исход для движка. */
    private suspend fun announced(opened: OpenedWorkspace, branch: TaskBranch): TaskBranch {
        sessions.broadcast(HostEvent.WorkspaceChanged(opened.workspace.id))
        return branch
    }
}

/** Код причины отказа прогона для причины отказа репозитория. */
private val TaskBranchRefusal.code: String
    get() = when (this) {
        TaskBranchRefusal.NO_COMMITS -> RunInterruptReason.REPOSITORY_EMPTY
        TaskBranchRefusal.DETACHED_HEAD -> RunInterruptReason.HEAD_DETACHED
        TaskBranchRefusal.READ_ONLY -> RunInterruptReason.REPOSITORY_READ_ONLY
        TaskBranchRefusal.GIT_FAILED -> RunInterruptReason.BRANCH_FAILED
    }
