package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.host.git.GitRepository
import dev.aide.host.git.SnapshotOutcome
import dev.aide.host.git.SnapshotPolicy
import dev.aide.host.git.snapshotRefName
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

/**
 * Общая часть постановки снапшота: имя ссылки, защита «в использовании» и вытеснение (T-1.8, T-1.19).
 *
 * Вынесена, а не скопирована в двух адаптерах портов: [TaskSnapshotGuard] ставит снапшот
 * в начале прогона, [ChangeSnapshotGuard] — перед изменяющим вызовом, и у них общий не только
 * git, но и политика вытеснения. Две копии этой политики разошлись бы, и лимит снапшотов
 * соблюдался бы только той из них, которую правили последней.
 *
 * @param workspaces открытые воркспейсы хоста; снапшот ставится в текущем из них.
 * @param store хранилище задач и прогонов: по нему видно, какие снапшоты ещё в использовании.
 * @param clock источник времени; метка входит в имя ссылки, поэтому подменяется в тестах.
 */
internal class SnapshotPlacement(
    private val workspaces: OpenWorkspaces,
    private val store: HostStore,
    private val clock: () -> Instant,
) {

    private val logger = LoggerFactory.getLogger(SnapshotPlacement::class.java)

    /** Ставит снапшот с поводом [trigger] в текущем открытом воркспейсе. */
    fun place(trigger: SnapshotTrigger): Result {
        val opened = workspaces.current() ?: return Result.Failed(RunInterruptReason.NO_WORKSPACE)
        val ref = snapshotRefName(clock().toEpochMilliseconds(), trigger.label)
        return when (opened.git.createSnapshot(ref)) {
            // Коммитов нет: ссылаться не на что; вызывающий сам решает, отказ это или нет.
            SnapshotOutcome.NoHead -> Result.NoHead

            // Сбой записи — отказ, а не исключение: вызывающий обязан получить код причины.
            SnapshotOutcome.Refused -> Result.Failed(RunInterruptReason.SNAPSHOT_FAILED)

            SnapshotOutcome.Created -> {
                evict(opened.git, keep = ref)
                Result.Created(SnapshotRef(ref))
            }
        }
    }

    /**
     * Вытесняет старые снапшоты сверх хранимого числа, не трогая используемые.
     *
     * Используемые — снапшоты прогонов незакрытых задач: к ним пользователь ещё вернётся
     * (откат T-1.6, чекпоинты T-1.17), и вытеснение, снёсшее такой снапшот, сделало бы
     * обещание «стоп обратим» пустым. Задача закрыта — её снапшоты становятся обычными.
     *
     * @param keep только что созданная ссылка: в хранилище она попадёт лишь после возврата
     *   из порта, а вытеснение идёт прямо сейчас, поэтому без этого запаса ссылка могла бы
     *   снести саму себя — и прогон записал бы снапшот, которого в репозитории нет.
     *
     * Сбой уборки прогон не роняет: снапшот уже поставлен, обещание «перед изменением есть
     * снапшот» выполнено, а тихое несоблюдение лимита хуже шумной записи о нём.
     */
    private fun evict(git: GitRepository, keep: String) {
        runCatching {
            val extra = SnapshotPolicy.evictable(git.snapshotRefs(), inUse() + keep)
            if (extra.isNotEmpty()) git.deleteSnapshots(extra)
        }.onFailure { error -> logger.warn("Снапшоты не вытеснены: ${error.message}", error) }
    }

    /** Ссылки, на которые ссылается незакрытая задача: снапшоты её прогонов. */
    private fun inUse(): Set<String> = store.tasks.unclosed()
        .flatMap { task -> store.runs.byTask(task.id) }
        .flatMap { run -> run.snapshots }
        .mapTo(mutableSetOf()) { it.value }

    /** Исход постановки снапшота; коды причин — из словаря [RunInterruptReason]. */
    sealed interface Result {

        /** Ссылка поставлена на текущий HEAD. */
        data class Created(val ref: SnapshotRef) : Result

        /** Коммитов нет: ссылаться не на что. */
        data object NoHead : Result

        /** Снапшот поставить нельзя; [reason] — код причины из словаря хоста. */
        data class Failed(val reason: String) : Result
    }
}

/** Метка повода для имени ссылки: те же слова, что описаны у `Snapshot` и `SnapshotTrigger` в домене. */
internal val SnapshotTrigger.label: String
    get() = when (this) {
        SnapshotTrigger.BEFORE_AGENT_STEP -> "before-agent-step"
        SnapshotTrigger.AFTER_AGENT_STEP -> "after-agent-step"
        SnapshotTrigger.BEFORE_MANUAL_EDIT -> "before-manual-edit"
        SnapshotTrigger.AFTER_MANUAL_EDIT -> "after-manual-edit"
    }
