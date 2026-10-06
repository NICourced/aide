package dev.aide.host.agent

import dev.aide.agent.RunInterruptReason
import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.host.git.GitRepository
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.tools.ports.ChangeSnapshot
import dev.aide.tools.ports.ChangeSnapshots
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Точка отката перед изменяющим вызовом — поверх открытого воркспейса (T-1.8).
 *
 * Живёт в `host-core` по той же причине, что и [TaskSnapshotGuard]: поставить ссылку значит
 * знать, какой воркспейс открыт, а модуль инструментов о git не знает (О-1). Именование
 * ссылки и вытеснение общие с [TaskSnapshotGuard] и лежат в [SnapshotPlacement].
 *
 * **Один снапшот на изменение HEAD, а не на вызов** (решение 4). Внутри шага несколько
 * записей идут подряд, и до коммита шага (T-1.11) HEAD между ними не двигается: вторая
 * ссылка указывала бы на тот же коммит и вытесняла бы осмысленные снапшоты ради дублей.
 * Поэтому запоминается тройка «прогон → HEAD → ссылка», и при неизменном HEAD внутри того
 * же прогона возвращается прежняя ссылка. Прогон в ключе обязателен: иначе следующая задача
 * у того же коммита получила бы ссылку предыдущего прогона, помеченную его временем.
 *
 * Поля кэша `@Volatile`: их пишет корутина прогона, а читает следующая его итерация, и без
 * волатильности JMM не гарантирует видимость. Синхронизация здесь не нужна — прогонов
 * одновременно не бывает (О-8), и все вызовы идут из одной корутины, — поэтому гонки нет,
 * а волатильность закрывает единственный риск: увидеть устаревшее значение между итерациями.
 *
 * @param workspaces открытые воркспейсы хоста; снапшот ставится в текущем из них.
 * @param store хранилище задач и прогонов: по нему видно, какие снапшоты ещё в использовании.
 * @param clock источник времени; метка времени входит в имя ссылки, поэтому она подменяется в тестах.
 */
class ChangeSnapshotGuard(
    private val workspaces: OpenWorkspaces,
    store: HostStore,
    clock: () -> Instant = Clock.System::now,
) : ChangeSnapshots {

    private val placement = SnapshotPlacement(workspaces, store, clock)

    @Volatile
    private var cachedRun: RunId? = null

    @Volatile
    private var lastHead: String? = null

    @Volatile
    private var lastRef: SnapshotRef? = null

    override suspend fun beforeChange(runId: RunId): ChangeSnapshot {
        val opened = workspaces.current() ?: return ChangeSnapshot.Failed(RunInterruptReason.NO_WORKSPACE)
        val head = opened.git.headCommit()
        val cached = reusable(runId, opened.git, head)
        return cached?.let { ChangeSnapshot.Taken(it) } ?: place(runId, head)
    }

    /** Ставит новый снапшот и запоминает тройку «прогон → HEAD → ссылка». */
    private suspend fun place(runId: RunId, head: String): ChangeSnapshot =
        when (val placed = placement.place(SnapshotTrigger.BEFORE_AGENT_STEP)) {
            // Здесь снапшот обязателен: без него изменяющий вызов не состоится.
            is SnapshotPlacement.Result.Created -> remember(runId, head, placed.ref)

            SnapshotPlacement.Result.NoHead -> {
                forget()
                ChangeSnapshot.NoHead
            }

            is SnapshotPlacement.Result.Failed -> {
                forget()
                ChangeSnapshot.Failed(placed.reason)
            }
        }

    /** Запомненная ссылка, годная к повторному использованию; null — нужен новый снапшот. */
    private fun reusable(runId: RunId, git: GitRepository, head: String): SnapshotRef? {
        val cached = lastRef?.takeIf { cachedRun == runId } ?: return null
        val sameHead = head.isNotEmpty() && head == lastHead
        return cached.takeIf { sameHead && stillPresent(git, cached) }
    }

    /** Запоминает тройку «прогон → HEAD → ссылка», пока HEAD не сдвинется. */
    private fun remember(runId: RunId, head: String, ref: SnapshotRef): ChangeSnapshot {
        cachedRun = runId
        lastHead = head
        lastRef = ref
        return ChangeSnapshot.Taken(ref)
    }

    /** Сбрасывает память: следующий вызов поставит новый снапшот, а не вернёт несуществующий. */
    private fun forget() {
        cachedRun = null
        lastHead = null
        lastRef = null
    }

    /**
     * Жива ли запомненная ссылка.
     *
     * Ссылку мог вытеснить [SnapshotPlacement] у другой задачи: вернуть её как точку отката
     * значило бы записать в прогон ссылку, которой в репозитории уже нет. Сбой чтения читаем
     * как «живость неизвестна» и ставим снапшот заново — лишняя ссылка дешевле несуществующей.
     */
    private fun stillPresent(git: GitRepository, ref: SnapshotRef): Boolean =
        runCatching { ref.value in git.snapshotRefs() }.getOrDefault(false)
}
