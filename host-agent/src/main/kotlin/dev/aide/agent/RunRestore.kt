package dev.aide.agent

import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.WorkspaceRestore
import dev.aide.agent.ports.WorkspaceRestoreOutcome
import dev.aide.domain.AgentRun
import org.slf4j.LoggerFactory

/**
 * Чем закончилась подготовка остановки: версия прогона, которую надо записать, и код причины.
 *
 * Версия едет вместе с причиной, чтобы на одну остановку приходился один поход в хранилище:
 * последнюю запись всё равно надо прочитать до возврата дерева (в ней снапшоты шага),
 * и второй раз её читать незачем. Она же — та самая «последняя записанная» версия для
 * [RunStateWriter.stop], поэтому перепланированный план (T-1.2) не теряется.
 */
internal data class StoppedRun(val run: AgentRun, val reason: String)

/**
 * Возврат дерева к последнему снапшоту прогона при остановке (T-1.4).
 *
 * Отдельный класс, как [TaskBranchPlacement], [RunSnapshots] и [TaskWorkStash]: возврат
 * дерева — работа с репозиторием, а не состояние прогона, и движок остаётся в пределах
 * числа функций, которое проверяет линтер. Здесь же лежит правило «последний снапшот
 * берётся из записанного прогона, а не из копии в корутине».
 *
 * @param restore порт к репозиторию: движок о git не знает (О-1).
 * @param runs чтение прогона: последний снапшот берётся из записанной версии.
 */
internal class RunRestore(
    private val restore: WorkspaceRestore,
    private val runs: RunRepository,
) {

    private val logger = LoggerFactory.getLogger(RunRestore::class.java)

    /**
     * Возвращает дерево к снапшоту [run] и говорит, каким кодом помечать остановленный прогон.
     *
     * Снапшот читается из хранилища, а не из переданного прогона: пока шаг шёл, точка отката
     * изменяющего вызова (T-1.8) записалась в прогон, и копия в корутине её не видит. Прогон
     * при этом ещё не закрыт, поэтому защита «в использовании» (T-1.19) не могла вытеснить
     * ссылку — читать её надо именно сейчас.
     *
     * Если записи нет, восстанавливать по копии нельзя: копия помнит снапшот начала шага,
     * то есть заведомо более старую точку, чем настоящая. Честнее не восстанавливать вовсе
     * и сказать об этом кодом причины — молча вернуть дерево «на шаг назад» хуже.
     *
     * Сбой восстановления **не отменяет** остановку: прогон всё равно `STOPPED`, это решение
     * пользователя. Но сбой виден: код причины уезжает в прогон и в задачу, а не остаётся
     * только в журнале — иначе пользователь увидел бы репозиторий, о котором ничего не знает.
     */
    suspend fun afterStop(run: AgentRun): StoppedRun {
        val latest = runs.load(run.id)
        if (latest == null) {
            logger.warn("Прогон ${run.id.value}: последняя запись не прочитана — дерево не восстановлено")
            return StoppedRun(run, RunInterruptReason.RESTORE_FAILED)
        }
        return StoppedRun(latest, restoreReason(latest))
    }

    /** Код причины для остановленного прогона: сбой восстановления важнее самого стопа. */
    private suspend fun restoreReason(run: AgentRun): String =
        when (val outcome = restore.restoreTo(run.snapshots.lastOrNull())) {
            WorkspaceRestoreOutcome.Restored -> RunInterruptReason.USER_STOP

            WorkspaceRestoreOutcome.Nothing -> RunInterruptReason.USER_STOP

            is WorkspaceRestoreOutcome.Refused -> {
                logger.warn("Прогон ${run.id.value}: дерево не восстановлено (${outcome.reason})")
                outcome.reason
            }
        }
}
