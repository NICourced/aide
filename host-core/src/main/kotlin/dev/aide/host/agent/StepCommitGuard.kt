package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.host.git.StepCommitOutcome
import dev.aide.host.git.StepCommitRefusal
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.tools.ports.StepCommit
import dev.aide.tools.ports.StepCommits
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Фиксация шага поверх открытого воркспейса (T-1.11).
 *
 * Живёт в `host-core`, а не в движке: закоммитить значит знать, какой воркспейс открыт,
 * а это знание хостовое (О-1). Здесь же причина отказа git переводится в код словаря
 * [RunInterruptReason]: git-слой о прогонах не знает, а движок о git.
 *
 * Git-работа уходит на [Dispatchers.IO] (долг T-1.18): коммит пишет объекты и индекс,
 * то есть блокирует поток, а корутина прогона не должна стоять на диске.
 *
 * @param workspaces открытые воркспейсы хоста; коммит идёт в текущем из них.
 */
class StepCommitGuard(private val workspaces: OpenWorkspaces) : StepCommits {

    override suspend fun commit(branch: String, message: String): StepCommit {
        val opened = workspaces.current() ?: return StepCommit.Refused(RunInterruptReason.NO_WORKSPACE)
        return withContext(Dispatchers.IO) {
            when (val outcome = opened.git.commitStep(branch, message)) {
                is StepCommitOutcome.Committed -> StepCommit.Committed(outcome.hash)
                StepCommitOutcome.NothingToCommit -> StepCommit.NothingToCommit
                is StepCommitOutcome.Refused -> StepCommit.Refused(outcome.reason.code)
            }
        }
    }
}

/** Код причины отказа прогона для причины отказа коммита шага. */
private val StepCommitRefusal.code: String
    get() = when (this) {
        StepCommitRefusal.WRONG_BRANCH -> RunInterruptReason.COMMIT_WRONG_BRANCH
        StepCommitRefusal.GIT_FAILED -> RunInterruptReason.COMMIT_FAILED
    }
