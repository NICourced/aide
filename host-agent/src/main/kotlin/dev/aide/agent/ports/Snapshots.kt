package dev.aide.agent.ports

import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger

/**
 * Как движку достался снапшот (T-1.19).
 *
 * Результат, а не «ссылка или исключение»: пустой репозиторий — обычное состояние,
 * в котором снапшот поставить не на что, и прогон из-за этого падать не должен.
 * Код отказа лежит в [Refused.reason] — из словаря [dev.aide.agent.RunInterruptReason]:
 * понятную строку строит UI (NFR-13).
 */
sealed interface TaskSnapshot {

    /** Снапшот поставлен: [ref] — ссылка вида `refs/ai/snap/<timestamp>-<label>`. */
    data class Created(val ref: SnapshotRef) : TaskSnapshot

    /** Коммитов нет: ссылаться не на что; снапшота нет, и это не ошибка прогона. */
    data object NoHead : TaskSnapshot

    /** Снапшот поставить нельзя; [reason] — код причины отказа прогона. */
    data class Refused(val reason: String) : TaskSnapshot
}

/**
 * Единственное, что движку нужно от снапшотов (T-1.19, О-1).
 *
 * Рантайм агента о git не знает: ссылки, скрытое пространство имён и вытеснение —
 * дело хоста, а движок получает узкий порт «поставь снапшот перед действием агента».
 * О том, какие ссылки в использовании, знают задачи и прогоны, — этого знания у движка
 * нет, поэтому защита от вытеснения живёт в адаптере, а не здесь.
 */
fun interface Snapshots {

    /** Ставит снапшот с поводом [trigger]; исход — [TaskSnapshot]. */
    suspend fun create(trigger: SnapshotTrigger): TaskSnapshot
}
