package dev.aide.agent

import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.domain.Task
import org.slf4j.LoggerFactory

/**
 * Постановка задачи в её ветку `ai/<task-id>` (T-1.18).
 *
 * Отдельный класс, а не пара методов движка: ветка задачи — работа с репозиторием,
 * а не состояние прогона, и правило «базовая ветка запоминается один раз» целиком
 * живёт здесь. Движок остаётся в пределах числа функций, которое проверяет линтер,
 * — тем же способом вынесены очередь ([RunQueue]) и запись состояния ([RunStateWriter]).
 *
 * @param branches порт к репозиторию: движок о git не знает (О-1).
 * @param writer запись задачи: событие о смене идёт после записи в базу (О-8).
 */
internal class TaskBranchPlacement(
    private val branches: TaskBranches,
    private val writer: RunStateWriter,
) {

    private val logger = LoggerFactory.getLogger(TaskBranchPlacement::class.java)

    /**
     * Ставит прогон в ветку задачи; null — задача не начата, причина уже записана.
     *
     * Отказ порта — отказ задачи, а не прогон в чужой ветке: коммиты шагов (T-1.11)
     * обязаны попадать в ветку задачи, и начинать прогон вне неё значило бы записывать
     * их пользователю в его собственную ветку.
     */
    suspend fun place(task: Task): Task? = when (val branch = branches.ensure(task.branch)) {
        is TaskBranch.Created -> recordBase(task, branch.base)

        TaskBranch.Existing -> task

        is TaskBranch.Refused -> {
            logger.warn("Задача ${task.id.value} не начата: ветку ${task.branch} не получить (${branch.reason})")
            writer.failTask(task.id, branch.reason)
            null
        }
    }

    /**
     * Запоминает, от какой ветки ответвилась задача, — один раз и навсегда.
     *
     * База нужна приёмке пакета (T-1.20): вливать ветку задачи нужно именно в неё, а вывести
     * её потом нечем. Уже записанная база не переписывается: ветку задачи могли удалить
     * и создать заново от другой ветки, и ответвление сегодняшнего дня не отменяет того,
     * что задача начиналась с прежней.
     */
    private suspend fun recordBase(task: Task, base: String): Task =
        if (task.baseBranch == null) writer.persistTask(task.copy(baseBranch = base)) else task
}
