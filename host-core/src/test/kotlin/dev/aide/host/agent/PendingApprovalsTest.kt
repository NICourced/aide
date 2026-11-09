package dev.aide.host.agent

import dev.aide.domain.ApprovalDecision
import dev.aide.domain.RunId
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolKind
import dev.aide.host.server.ClientSessions
import dev.aide.tools.file.WriteFileTool
import dev.aide.tools.permission.PermissionDecision
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.permission.SessionPermissions
import java.util.UUID
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class PendingApprovalsTest {

    @Test
    fun allowSessionIsRemembered() = runBlocking {
        val sessions = ClientSessions()
        val sessionPermissions = SessionPermissions()
        val pending = PendingApprovals(sessions, sessionPermissions)
        val request = request()

        launch {
            pending.answer(request.runId, request.callId, ApprovalDecision.ALLOW_SESSION)
        }

        val decision = pending.request(request.runId, request)
        assertEquals(ApprovalDecision.ALLOW_SESSION, decision)
        assertTrue(pending.snapshot().isEmpty(), "отвеченный запрос обязан исчезнуть из ожидающих")

        val resolver = PermissionResolver(stored = { null }, session = sessionPermissions::load)
        val effective = resolver.resolve(WriteFileTool.TOOL_NAME, ToolKind.WRITE)
        assertIs<PermissionDecision.Allow>(effective)
    }

    @Test
    fun `ответ на чужой прогон игнорируется`() = runBlocking {
        val pending = PendingApprovals(ClientSessions(), SessionPermissions())
        val request = request()
        val decision = CompletableDeferred<ApprovalDecision>()

        launch { decision.complete(pending.request(request.runId, request)) }
        assertTrue(awaitSnapshot(pending), "запрос обязан появиться в ожидающих")

        pending.answer(RunId("other-run"), request.callId, ApprovalDecision.DENY)
        assertFalse(decision.isCompleted, "чужой прогон не должен завершать ожидание")

        pending.answer(request.runId, request.callId, ApprovalDecision.ALLOW_ONCE)
        assertEquals(ApprovalDecision.ALLOW_ONCE, decision.await())
    }

    @Test
    fun `отмена ожидания убирает запрос из снимка`() = runBlocking {
        val pending = PendingApprovals(ClientSessions(), SessionPermissions())
        val request = request()
        val job = launch { pending.request(request.runId, request) }
        assertTrue(awaitSnapshot(pending), "запрос обязан появиться в ожидающих")

        job.cancelAndJoin()

        assertTrue(pending.snapshot().isEmpty(), "отменённое ожидание не должно висеть в снимке")
    }

    private suspend fun awaitSnapshot(pending: PendingApprovals): Boolean =
        withTimeoutOrNull(SNAPSHOT_TIMEOUT_MILLIS) {
            while (pending.snapshot().isEmpty()) delay(POLL_MILLIS)
            true
        } == true

    private fun request(): ToolApprovalRequest = ToolApprovalRequest(
        callId = ToolCallId(UUID.randomUUID().toString()),
        runId = RunId(UUID.randomUUID().toString()),
        tool = WriteFileTool.TOOL_NAME,
        arguments = "{}",
        kind = ToolKind.WRITE,
        changesRepository = true,
    )

    private companion object {
        const val SNAPSHOT_TIMEOUT_MILLIS: Long = 1_000
        const val POLL_MILLIS: Long = 10
    }
}
