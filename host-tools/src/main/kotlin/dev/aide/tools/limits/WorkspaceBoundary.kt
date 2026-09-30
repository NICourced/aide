package dev.aide.tools.limits

import dev.aide.tools.permission.DenyReason
import java.nio.file.Path

/** Доступ к файлам воркспейса: выход за корень запрещён всегда (§ 10.1). */
interface WorkspaceBoundary {
    /** Путь внутри корня или [HardLimitViolation]; канонизация — на стороне реализации. */
    fun resolveInside(path: String): Path
}

/**
 * Нарушение жёсткого предела — того, что не отменяется настройками прав (§ 10.1).
 * Отдельный тип, а не общий отказ доступа: у инструмента нет ветки «попробовать иначе»,
 * есть только код причины [reason] для журнала и для экрана.
 *
 * Типизированная причина отказа (ProtocolError) остаётся в [cause] — по ней принимаются
 * решения выше по стеку; [detail] — готовая строка для журнала, собранная из неё.
 */
class HardLimitViolation(val reason: DenyReason, val detail: String, cause: Throwable? = null) :
    Exception("жёсткий предел нарушен: $reason — $detail", cause)
