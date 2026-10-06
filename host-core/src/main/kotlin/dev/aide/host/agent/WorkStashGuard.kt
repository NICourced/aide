package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskStash
import dev.aide.agent.ports.WorkStash
import dev.aide.domain.TaskId
import dev.aide.host.git.STASH_REF_PREFIX
import dev.aide.host.git.StashOutcome
import dev.aide.host.git.StashReturnOutcome
import dev.aide.host.server.ClientSessions
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.protocol.HostEvent

/** Метка отложенного в рабочем сообщении stash: по ней при разборе видно, чьи это правки. */
private const val STASH_MESSAGE_PREFIX = "ai/"

/**
 * Порт отложенных правок поверх открытого воркспейса (T-1.59).
 *
 * Живёт в `host-core`, а не в движке: откладывать правки — значит знать, какой воркспейс
 * открыт, а это знание хостовое (О-1). Здесь же причина отказа git переводится в код словаря
 * [RunInterruptReason]: git-слой о прогонах не знает, а движок о git.
 *
 * Имя ссылки собирается здесь (`refs/ai/stash/<task-id>`), а не движком: движку ссылка
 * не нужна как значение — она уезжает в задачу и возвращается оттуда же.
 *
 * Возврат переключает рабочее дерево обратно на ветку пользователя, поэтому после него
 * рассылается [HostEvent.WorkspaceChanged] — тем же механизмом, что после переключения
 * в ветку задачи (T-1.18). Без события шапка клиента осталась бы на ветке агента.
 *
 * @param workspaces открытые воркспейсы хоста; правки откладываются в текущем из них.
 * @param sessions активные сессии: им рассылается событие о смене состояния воркспейса.
 */
class WorkStashGuard(
    private val workspaces: OpenWorkspaces,
    private val sessions: ClientSessions,
) : WorkStash {

    override suspend fun stash(taskId: TaskId): TaskStash {
        val opened = workspaces.current() ?: return TaskStash.Refused(RunInterruptReason.NO_WORKSPACE)
        val ref = STASH_REF_PREFIX + taskId.value
        val stash = opened.git.workStash
        return when (val outcome = stash.stashEdits(ref, STASH_MESSAGE_PREFIX + taskId.value)) {
            is StashOutcome.Stashed -> TaskStash.Stashed(outcome.ref, outcome.branch)
            StashOutcome.Nothing -> TaskStash.Nothing
            StashOutcome.Refused -> TaskStash.Refused(RunInterruptReason.STASH_FAILED)
        }
    }

    override suspend fun restore(ref: String, branch: String?): StashReturn {
        val opened = workspaces.current() ?: return StashReturn.Refused(RunInterruptReason.NO_WORKSPACE)
        val outcome = when (opened.git.workStash.returnStashEdits(ref, branch)) {
            StashReturnOutcome.Returned -> StashReturn.Returned
            StashReturnOutcome.Conflict -> StashReturn.Conflict(RunInterruptReason.STASH_CONFLICT)
            StashReturnOutcome.Refused -> StashReturn.Refused(RunInterruptReason.STASH_RETURN_FAILED)
        }
        sessions.broadcast(HostEvent.WorkspaceChanged(opened.workspace.id))
        return outcome
    }
}
