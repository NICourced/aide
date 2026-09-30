package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.host.git.GitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.SnapshotPolicy
import dev.aide.host.git.snapshotRefName
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Порт снапшотов поверх открытого воркспейса (T-1.19).
 *
 * Живёт в `host-core`, а не в движке: поставить ссылку — значит знать, какой воркспейс
 * открыт, а вытеснить лишние, не тронув нужные, — значит знать задачи и их прогоны.
 * Оба знания хостовые (О-1), поэтому и защита «в использовании» считается здесь,
 * а не в git-слое: git о задачах не знает.
 *
 * @param workspaces открытые воркспейсы хоста; снапшот ставится в текущем из них.
 * @param store хранилище задач и прогонов: по нему видно, какие снапшоты ещё в использовании.
 * @param clock источник времени; метка времени входит в имя ссылки, поэтому она подменяется в тестах.
 */
class TaskSnapshotGuard(
    private val workspaces: OpenWorkspaces,
    private val store: HostStore,
    private val clock: () -> Instant = Clock.System::now,
) : Snapshots {

    override suspend fun create(trigger: SnapshotTrigger): TaskSnapshot {
        val opened = workspaces.current() ?: return TaskSnapshot.Refused(RunInterruptReason.NO_WORKSPACE)
        val ref = snapshotRefName(clock().toEpochMilliseconds(), trigger.label)
        return when (opened.git.createSnapshot(ref)) {
            // Коммитов нет: ссылаться не на что; прогон из-за этого не падает (решение 5).
            SnapshotOutcome.NoHead -> TaskSnapshot.NoHead

            SnapshotOutcome.Created -> {
                evict(opened.git)
                TaskSnapshot.Created(SnapshotRef(ref))
            }
        }
    }

    /**
     * Вытесняет старые снапшоты сверх хранимого числа, не трогая используемые.
     *
     * Используемые — снапшоты прогонов незакрытых задач: к ним пользователь ещё вернётся
     * (откат T-1.6, чекпоинты T-1.17), и вытеснение, снёсшее такой снапшот, сделало бы
     * обещание «стоп обратим» пустым. Задача закрыта — её снапшоты становятся обычными.
     */
    private fun evict(git: GitRepository) {
        val extra = SnapshotPolicy.evictable(git.snapshotRefs(), inUse())
        if (extra.isNotEmpty()) git.deleteSnapshots(extra)
    }

    /** Ссылки, на которые ссылается незакрытая задача: снапшоты её прогонов. */
    private fun inUse(): Set<String> = store.tasks.unclosed()
        .flatMap { task -> store.runs.byTask(task.id) }
        .flatMap { run -> run.snapshots }
        .mapTo(mutableSetOf()) { it.value }
}

/** Метка повода для имени ссылки: те же слова, что описаны у `Snapshot` и `SnapshotTrigger` в домене. */
private val SnapshotTrigger.label: String
    get() = when (this) {
        SnapshotTrigger.BEFORE_AGENT_STEP -> "before-agent-step"
        SnapshotTrigger.AFTER_AGENT_STEP -> "after-agent-step"
        SnapshotTrigger.BEFORE_MANUAL_EDIT -> "before-manual-edit"
        SnapshotTrigger.AFTER_MANUAL_EDIT -> "after-manual-edit"
    }
