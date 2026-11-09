package dev.aide.host.agent

import dev.aide.domain.ApprovalDecision
import dev.aide.domain.RunId
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolPermission
import dev.aide.host.server.ClientSessions
import dev.aide.protocol.HostEvent
import dev.aide.tools.permission.SessionPermissions
import dev.aide.tools.ports.ToolApproval
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.CompletableDeferred
import org.slf4j.LoggerFactory

/**
 * Ожидание подтверждения вызовов инструментов (T-1.13).
 *
 * Реализует порт [ToolApproval] для `host-tools`: точка вызова просит решение,
 * а этот класс рассылает событие клиентам и ждёт ответа. На каждый вызов — одно
 * ожидание; ответ клиента его завершает, стоп прогона или закрытие хоста отменяют.
 *
 * «Разрешить на сессию» запоминается в [SessionPermissions]; «один раз» никуда
 * не пишется, поэтому следующий вызов снова спросит.
 */
class PendingApprovals(
    private val sessions: ClientSessions,
    private val sessionPermissions: SessionPermissions = SessionPermissions(),
) : ToolApproval {

    private val logger = LoggerFactory.getLogger(PendingApprovals::class.java)

    private val pending: ConcurrentHashMap<ToolCallId, Entry> = ConcurrentHashMap()

    private data class Entry(
        val request: ToolApprovalRequest,
        val deferred: CompletableDeferred<ApprovalDecision>,
    )

    /**
     * Показывает пользователю запрос и ждёт решения.
     *
     * Событие рассылается сразу; при отмене корутины запись удаляется и вызов
     * не выполняется.
     */
    override suspend fun request(runId: RunId, request: ToolApprovalRequest): ApprovalDecision {
        val deferred = CompletableDeferred<ApprovalDecision>()
        val entry = Entry(request, deferred)
        val previous = pending.put(request.callId, entry)
        if (previous != null) {
            logger.warn("Запрос подтверждения для ${request.callId.value} уже существует: старый отменён")
            previous.deferred.cancel()
        }
        return try {
            sessions.broadcast(HostEvent.ApprovalRequested(request))
            deferred.await()
        } finally {
            // Удаление по значению: если идентификатор успели переиспользовать,
            // завершение старого ожидания не должно стереть новое.
            pending.remove(request.callId, entry)
            deferred.cancel()
        }
    }

    /**
     * Принимает решение по ожидающему вызову.
     *
     * Решение для неизвестного вызова игнорируется: клиент мог ответить на запрос,
     * который уже отменился (например, по стопу прогона).
     */
    fun answer(runId: RunId, callId: ToolCallId, decision: ApprovalDecision) {
        val entry = pending[callId]
        if (entry == null || entry.request.runId != runId) {
            logger.info("Ответ для неизвестного, чужого или уже отменённого вызова $callId игнорирован")
            return
        }
        // Удаление по значению делает ответ однократным: два одновременных решения
        // по одному вызову не применят сессионное разрешение дважды.
        if (!pending.remove(callId, entry)) {
            logger.info("Ответ для уже завершённого вызова $callId игнорирован")
            return
        }
        if (decision == ApprovalDecision.ALLOW_SESSION) {
            sessionPermissions.allow(entry.request.tool, entry.request.kind)
        }
        entry.deferred.complete(decision)
    }

    /** Снимок ожидающих запросов для подключившегося позже клиента. */
    fun snapshot(): List<ToolApprovalRequest> = pending.values.map { it.request }

    /** Сессионное разрешение инструмента для резолвера прав; null, если его ещё нет. */
    fun sessionPermission(tool: String): ToolPermission? = sessionPermissions.load(tool)
}
