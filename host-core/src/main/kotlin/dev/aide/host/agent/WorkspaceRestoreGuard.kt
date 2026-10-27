package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.WorkspaceRestore
import dev.aide.agent.ports.WorkspaceRestoreOutcome
import dev.aide.domain.SnapshotRef
import dev.aide.host.git.RestoreOutcome
import dev.aide.host.git.RestoreRefusal
import dev.aide.host.workspace.OpenWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Возврат дерева к снапшоту поверх открытого воркспейса (T-1.4).
 *
 * Живёт в `host-core`, а не в движке: вернуть дерево значит знать, какой воркспейс открыт,
 * а это знание хостовое (О-1). Здесь же причина отказа git переводится в код словаря
 * [RunInterruptReason]: git-слой о прогонах не знает, а движок о git.
 *
 * Git-работа уходит на [Dispatchers.IO] (долг T-1.18): `reset` и `clean` пишут файлы
 * и блокируют поток, а корутина прогона не должна стоять на диске.
 *
 * @param workspaces открытые воркспейсы хоста; дерево возвращается в текущем из них.
 */
class WorkspaceRestoreGuard(private val workspaces: OpenWorkspaces) : WorkspaceRestore {

    /**
     * Отсутствие снапшота решается здесь, а не в git: у пустого репозитория точки отката
     * нет (T-1.19), и звать `reset` по несуществующей ссылке значило бы получить отказ
     * там, где восстанавливать просто нечего.
     */
    override suspend fun restoreTo(snapshot: SnapshotRef?): WorkspaceRestoreOutcome {
        if (snapshot == null) return WorkspaceRestoreOutcome.Nothing
        val opened = workspaces.current()
        // Два ветвления вместо трёх `return`: порог `ReturnCount` в проекте — два, и обойти
        // его можно только так; читается это не хуже, а поведение то же.
        return if (opened == null) {
            WorkspaceRestoreOutcome.Refused(RunInterruptReason.NO_WORKSPACE)
        } else {
            withContext(Dispatchers.IO) {
                when (val outcome = opened.git.restore.restoreTo(snapshot.value)) {
                    RestoreOutcome.Restored -> WorkspaceRestoreOutcome.Restored
                    is RestoreOutcome.Refused -> WorkspaceRestoreOutcome.Refused(outcome.reason.code)
                }
            }
        }
    }
}

/**
 * Код причины отказа прогона для причины отказа восстановления.
 *
 * Обе причины дают один код: для пользователя «ссылки нет» и «git отказал» — одно и то же
 * событие, дерево не вернулось, и разбираться в этом по коду ему нечем. Различие причин
 * остаётся в git-слое и в журнале, где оно и нужно.
 */
private val RestoreRefusal.code: String
    get() = when (this) {
        RestoreRefusal.UNKNOWN_REF, RestoreRefusal.GIT_FAILED -> RunInterruptReason.RESTORE_FAILED
    }
