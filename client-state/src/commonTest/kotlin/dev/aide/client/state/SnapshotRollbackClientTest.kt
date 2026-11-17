package dev.aide.client.state

import dev.aide.domain.Snapshot
import dev.aide.domain.FileChangeKind
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.SnapshotRollbackRejection
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertSame
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.6: клиент отката к снапшоту переводит запросы в протокольные сообщения и возвращает
 * типизированные ошибки хоста.
 */
class SnapshotRollbackClientTest {

    private val connection = FakeHostConnection()
    private var requestCounter = 0
    private val client = SnapshotRollbackClient(connection) { RequestId("snap-${++requestCounter}") }

    @Test
    fun `list возвращает снапшоты из ответа хоста`() {
        runBlocking {
            val ref = SnapshotRef("refs/ai/snap/1-before-agent-step")
            val snapshot = snapshot(ref)
            connection.respond = {
                HostMessage.SnapshotListResponse(
                    requestId = (it as ClientMessage.SnapshotList).requestId,
                    snapshots = listOf(snapshot),
                )
            }

            val result = client.list(WorkspaceId("ws-1")).getOrThrow()

            assertEquals(listOf(ref.value), result.map { it.ref.value })
            val sent = connection.takeRequests().single() as ClientMessage.SnapshotList
            assertEquals(WorkspaceId("ws-1"), sent.workspaceId)
        }
    }

    @Test
    fun `preview возвращает предпросмотр из ответа хоста`() {
        runBlocking {
            val ref = SnapshotRef("refs/ai/snap/1-before-agent-step")
            val preview = SnapshotPreview(
                snapshot = snapshot(ref),
                headCommit = "head-1",
                files = listOf(
                    SnapshotFileChange("a.txt", changeKind = FileChangeKind.MODIFIED, addedLines = 1, removedLines = 0),
                ),
            )
            connection.respond = {
                HostMessage.SnapshotPreviewResponse(
                    requestId = (it as ClientMessage.SnapshotPreviewRequest).requestId,
                    preview = preview,
                )
            }

            val result = client.preview(WorkspaceId("ws-1"), ref).getOrThrow()

            assertSame(preview, result)
            val sent = connection.takeRequests().single() as ClientMessage.SnapshotPreviewRequest
            assertEquals(ref, sent.ref)
        }
    }

    @Test
    fun `revert возвращает ссылку и хеш обратного коммита при успехе`() {
        runBlocking {
            val ref = SnapshotRef("refs/ai/snap/1-before-agent-step")
            connection.respond = {
                HostMessage.SnapshotReverted(
                    requestId = (it as ClientMessage.SnapshotRevert).requestId,
                    ref = ref,
                    commit = "revert-1",
                )
            }

            val result = client.revert(WorkspaceId("ws-1"), ref, expectedHead = "head-1").getOrThrow()

            assertEquals(ref, result.ref)
            assertEquals("revert-1", result.commit)
            val sent = connection.takeRequests().single() as ClientMessage.SnapshotRevert
            assertEquals("head-1", sent.expectedHead)
        }
    }

    @Test
    fun `ошибка хоста становится SnapshotRollbackRejected`() {
        runBlocking {
            connection.respond = {
                HostMessage.Failure(
                    requestId = (it as ClientMessage.SnapshotPreviewRequest).requestId,
                    error = ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.DIRTY_WORKTREE),
                )
            }

            val result = client.preview(WorkspaceId("ws-1"), SnapshotRef("x"))
            val failure = assertIs<HostCallException>(result.exceptionOrNull())
            val rejection = assertIs<ProtocolError.SnapshotRollbackRejected>(failure.error)
            assertEquals(SnapshotRollbackRejection.DIRTY_WORKTREE, rejection.rejection)
        }
    }

    @Test
    fun `неожиданный ответ становится внутренней ошибкой`() {
        runBlocking {
            connection.respond = {
                HostMessage.AgentSnapshot(requestId = RequestId("x"), runs = emptyList(), tasks = emptyList())
            }

            val result = client.list(WorkspaceId("ws-1"))

            assertTrue(result.isFailure)
            assertIs<HostCallException>(result.exceptionOrNull())
        }
    }

    private companion object {
        fun snapshot(ref: SnapshotRef): Snapshot = Snapshot(
            ref = ref,
            label = "before-agent-step",
            commit = "abc",
            trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
            createdAt = Instant.fromEpochMilliseconds(1),
        )
    }
}
