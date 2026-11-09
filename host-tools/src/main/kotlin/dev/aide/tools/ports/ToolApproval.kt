package dev.aide.tools.ports

import dev.aide.domain.ApprovalDecision
import dev.aide.domain.RunId
import dev.aide.domain.ToolApprovalRequest

/**
 * Порт запроса подтверждения вызова (T-1.13).
 *
 * Реализация живёт в `host-core`: там рассылается событие клиентам и ждётся ответ.
 * `host-tools` не знает ни протокола, ни сессий (О-1): точка вызова просто спрашивает
 * решение и продолжает в зависимости от него.
 */
fun interface ToolApproval {

    /**
     * Показать пользователю запрос и дождаться решения.
     *
     * Ожидание отменяемое: стоп прогона или закрытие хоста прерывают корутину,
     * вызов не выполняется, а прогон завершается как остановленный.
     */
    suspend fun request(runId: RunId, request: ToolApprovalRequest): ApprovalDecision
}
