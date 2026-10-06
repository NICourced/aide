package dev.aide.agent

import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.domain.AgentRun
import dev.aide.domain.SnapshotTrigger
import org.slf4j.LoggerFactory

/**
 * Снапшот в начале прогона (T-1.19).
 *
 * Отдельный класс, как и [TaskBranchPlacement]: снапшот — работа с репозиторием, а не
 * состояние прогона, и движок остаётся в пределах числа функций, которое проверяет линтер.
 *
 * Здесь ставится снапшот начала прогона — перед первым шагом агента. Снапшот перед
 * изменяющим вызовом ставит точка вызова через свой порт (T-1.8): у неё другая частота
 * и другой повод, и складывать их в один класс значило бы смешивать два решения.
 *
 * @param snapshots порт к репозиторию: движок о git не знает (О-1).
 * @param writer запись прогона: ссылка обязана пережить перезапуск хоста.
 */
internal class RunSnapshots(
    private val snapshots: Snapshots,
    private val writer: RunStateWriter,
) {

    private val logger = LoggerFactory.getLogger(RunSnapshots::class.java)

    /**
     * Ставит снапшот перед работой агента; null — прогон продолжать нельзя, причина записана.
     *
     * Пустой репозиторий — не отказ: читать и планировать можно и без коммитов, поэтому
     * прогон идёт дальше без снапшота (решение 5 T-1.19). Отказ порта (снапшот поставить
     * нечем) — отказ прогона: обещание «перед изменением есть снапшот» иначе стало бы пустым.
     *
     * Событие о смене состояния не рассылается: снапшот состояния прогона не меняет,
     * а ссылка уезжает клиенту вместе с прогоном на ближайшем событии (§ 8.4).
     */
    suspend fun beforeRun(run: AgentRun): AgentRun? =
        when (val outcome = snapshots.create(SnapshotTrigger.BEFORE_AGENT_STEP)) {
            is TaskSnapshot.Created -> writer.persist(
                run.copy(snapshots = run.snapshots + outcome.ref),
                emit = false,
            )

            TaskSnapshot.NoHead -> run

            is TaskSnapshot.Refused -> {
                logger.warn("Прогон ${run.id.value} не начат: снапшот не поставлен (${outcome.reason})")
                writer.fail(run, outcome.reason)
                null
            }
        }
}
