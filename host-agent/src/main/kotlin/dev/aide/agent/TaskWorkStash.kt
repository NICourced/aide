package dev.aide.agent

import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.ports.TaskStash
import dev.aide.agent.ports.WorkStash
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import org.slf4j.LoggerFactory

/**
 * Откладывание и возврат незакоммиченных правок пользователя (T-1.59).
 *
 * Отдельный класс, как [TaskBranchPlacement] и [RunSnapshots]: работа с репозиторием —
 * не состояние прогона, и движок остаётся в пределах числа функций, которое проверяет
 * линтер. Здесь же лежит правило «отложил до ветки, вернул после прогона», потому что
 * порядок этих двух действий и есть суть задачи (§ 8.3, NFR-SAFE-2).
 *
 * @param stashes порт к репозиторию: движок о git не знает (О-1).
 * @param tasks чтение задачи: возврат начинается с её текущего состояния, а не с копии в памяти.
 * @param writer запись задачи: событие о смене идёт после записи в базу (О-8).
 */
internal class TaskWorkStash(
    private val stashes: WorkStash,
    private val tasks: TaskRepository,
    private val writer: RunStateWriter,
) {

    private val logger = LoggerFactory.getLogger(TaskWorkStash::class.java)

    /**
     * Откладывает правки перед веткой задачи; null — прогон начинать нельзя, причина записана.
     *
     * Правки уезжают в сторону до `checkout` ветки задачи: иначе переключение молча перенесло
     * бы их в ветку агента, а коммит шага записал бы от его имени. Чистое дерево — не отказ:
     * откладывать нечего, и прогон идёт дальше.
     */
    suspend fun beforeRun(task: Task): Task? = when (val outcome = stashes.stash(task.id)) {
        is TaskStash.Stashed ->
            writer.persistTask(task.copy(stashRef = outcome.ref, stashBranch = outcome.branch))

        TaskStash.Nothing -> task

        is TaskStash.Refused -> {
            logger.warn("Задача ${task.id.value} не начата: правки не отложены (${outcome.reason})")
            writer.failTask(task.id, outcome.reason)
            null
        }
    }

    /**
     * Возвращает отложенные правки после прогона, когда бы он ни завершился.
     *
     * Состояние читается из хранилища, а не берётся из памяти: прогон мог завершиться успехом,
     * отказом или остановкой, и статус задачи к этому моменту уже записан. Отсутствие ссылки
     * означает, что откладывать было нечего, — возвращать нечего и теперь.
     */
    suspend fun afterRun(taskId: TaskId) {
        val task = tasks.load(taskId) ?: return
        val ref = task.stashRef ?: return
        val outcome = stashes.restore(ref, task.stashBranch)
        when (outcome) {
            StashReturn.Returned -> Unit

            is StashReturn.Conflict ->
                logger.warn("Задача ${task.id.value}: возврат правок конфликтует с работой агента ($ref)")

            is StashReturn.Refused ->
                logger.warn("Задача ${task.id.value}: правки не возвращены (${outcome.reason}, $ref)")
        }
        // Конфликт и отказ тоже записываются: пользователь обязан увидеть, что его правки ждут
        // разбора. Правки при этом остаются отложенными — [afterStashReturn] их не очищает.
        writer.persistTask(task.afterStashReturn(outcome))
    }
}

/**
 * Состояние задачи после попытки вернуть правки (T-1.59).
 *
 * Единственное место, где решается, что значит исход возврата для задачи: тем же правилом
 * пользуется восстановление после падения хоста, поэтому оно вынесено из движка.
 * Успех очищает ссылку и не трогает статус — прогон завершился своим исходом, а правки
 * вернулись. Конфликт и отказ оставляют отложенное на месте и помечают задачу ошибкой:
 * молча оставить пользователя с чужой веткой значило бы потерять его правки.
 */
fun Task.afterStashReturn(outcome: StashReturn): Task = when (outcome) {
    StashReturn.Returned -> copy(stashRef = null, stashBranch = null)

    is StashReturn.Conflict -> copy(status = TaskStatus.FAILED, failureReason = outcome.reason)

    is StashReturn.Refused -> copy(status = TaskStatus.FAILED, failureReason = outcome.reason)
}
