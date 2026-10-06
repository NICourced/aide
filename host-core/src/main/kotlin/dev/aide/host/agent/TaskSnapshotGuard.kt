package dev.aide.host.agent

import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.domain.SnapshotTrigger
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/**
 * Порт снапшотов поверх открытого воркспейса (T-1.19).
 *
 * Живёт в `host-core`, а не в движке: поставить ссылку — значит знать, какой воркспейс
 * открыт, а вытеснить лишние, не тронув нужные, — значит знать задачи и их прогоны.
 * Оба знания хостовые (О-1), поэтому и защита «в использовании» считается здесь,
 * а не в git-слое: git о задачах не знает. Именование ссылки и вытеснение общие
 * с [ChangeSnapshotGuard] и лежат в [SnapshotPlacement].
 *
 * @param workspaces открытые воркспейсы хоста; снапшот ставится в текущем из них.
 * @param store хранилище задач и прогонов: по нему видно, какие снапшоты ещё в использовании.
 * @param clock источник времени; метка времени входит в имя ссылки, поэтому она подменяется в тестах.
 */
class TaskSnapshotGuard(
    workspaces: OpenWorkspaces,
    store: HostStore,
    clock: () -> Instant = Clock.System::now,
) : Snapshots {

    private val placement = SnapshotPlacement(workspaces, store, clock)

    override suspend fun create(trigger: SnapshotTrigger): TaskSnapshot =
        // Git-работа — на IO-диспетчере (долг T-1.18): запись ссылки блокирует поток,
        // а корутина прогона не должна стоять на диске.
        withContext(Dispatchers.IO) {
            when (val placed = placement.place(trigger)) {
                // Пустой репозиторий — не ошибка прогона: это решает порт, а не адаптер (решение 5).
                SnapshotPlacement.Result.NoHead -> TaskSnapshot.NoHead

                // Сбой записи — отказ, а не исключение: задача получает код причины (T-1.19).
                is SnapshotPlacement.Result.Failed -> TaskSnapshot.Refused(placed.reason)

                is SnapshotPlacement.Result.Created -> TaskSnapshot.Created(placed.ref)
            }
        }
}
