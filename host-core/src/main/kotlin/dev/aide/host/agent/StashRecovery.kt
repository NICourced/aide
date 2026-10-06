package dev.aide.host.agent

import dev.aide.agent.afterStashReturn
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.WorkStash
import dev.aide.domain.Task
import dev.aide.host.store.HostStore
import org.slf4j.LoggerFactory

/**
 * Возврат отложенных правок после падения хоста (T-1.59).
 *
 * Падение хоста не теряет отложенное: правки лежат ссылкой git и записаны в задаче, поэтому
 * возврат повторяется, как только репозиторий снова доступен. Порядок тот же, что у движка
 * после прогона, — этим и объясняется то, что правило возврата вынесено в [afterStashReturn],
 * а не повторено здесь.
 *
 * Задача с незавершённым прогоном пропускается: значит, прогон идёт прямо сейчас (или стоит
 * на паузе), и возвращать его правки из-под него нельзя.
 *
 * @param stashes порт к репозиторию: ссылки и рабочее дерево — дело хоста (О-1).
 * @param store хранилище задач и прогонов: по нему видно, чьи правки ждут и кто ещё работает.
 * @param events рассылка смены статуса задачи: подключённый клиент обязан увидеть возврат.
 */
class StashRecovery(
    private val stashes: WorkStash,
    private val store: HostStore,
    private val events: AgentEventSink,
) {

    private val logger = LoggerFactory.getLogger(StashRecovery::class.java)

    /**
     * Возвращает правки всех задач, которые не работают прямо сейчас.
     *
     * Вызывается при открытии воркспейса — раньше репозитория нет, и вернуть правки некуда.
     * Сбой на одной задаче не мешает остальным: одна потерянная ссылка не должна оставить
     * без правок все прочие задачи.
     */
    suspend fun afterRestart() {
        val active = store.runs.unfinished().mapTo(mutableSetOf()) { it.taskId }
        store.tasks.pendingStash().filterNot { it.id in active }.forEach { task ->
            runCatching { returnStash(task) }.onFailure { error ->
                logger.warn("Отложенные правки задачи ${task.id.value} не возвращены: ${error.message}", error)
            }
        }
    }

    private suspend fun returnStash(task: Task) {
        val ref = task.stashRef ?: return
        val updated = task.afterStashReturn(stashes.restore(ref, task.stashBranch))
        store.tasks.save(updated)
        events.taskStateChanged(updated)
    }
}
