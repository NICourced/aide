package dev.aide.client.state

import dev.aide.domain.FileChangeKind
import dev.aide.domain.Snapshot
import dev.aide.domain.SnapshotFileChange
import dev.aide.domain.SnapshotPreview
import dev.aide.domain.SnapshotRef
import dev.aide.domain.SnapshotTrigger
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.SnapshotRollbackRejection
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

/**
 * T-1.6: состояние отката к снапшоту складывается из списка, предпросмотра и результата отката.
 */
class SnapshotRollbackStoreTest {

    private val store = SnapshotRollbackStore()
    private val ref = SnapshotRef("refs/ai/snap/1-before-agent-step")

    @Test
    fun `open показывает загрузку и сбрасывает предпросмотр`() {
        store.loaded(listOf(snapshot(ref)))
        store.select(ref)
        store.previewLoaded(preview(ref))

        store.open()

        assertEquals(ScreenState.Loading, store.state.value.list)
        assertNull(store.state.value.selected)
        assertNull(store.state.value.preview)
    }

    @Test
    fun `loaded наполняет список`() {
        store.open()

        store.loaded(listOf(snapshot(ref)))

        val loaded = assertIs<ScreenState.Loaded<List<Snapshot>>>(store.state.value.list)
        assertEquals(listOf(ref.value), loaded.data.map { it.ref.value })
    }

    @Test
    fun `пустой список даёт состояние пусто`() {
        store.open()

        store.loaded(emptyList())

        assertEquals(ScreenState.Empty, store.state.value.list)
    }

    @Test
    fun `failed переводит ошибку в состояние экрана`() {
        store.open()

        store.failed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.UNKNOWN_SNAPSHOT))

        val failed = assertIs<ScreenState.Failed>(store.state.value.list)
        assertEquals(ScreenState.ErrorKind.ROLLBACK_REJECTED, failed.kind)
    }

    @Test
    fun `select показывает загрузку предпросмотра`() {
        store.loaded(listOf(snapshot(ref)))

        store.select(ref)

        assertEquals(ref, store.state.value.selected)
        assertEquals(ScreenState.Loading, store.state.value.preview)
    }

    @Test
    fun `previewLoaded кладёт предпросмотр`() {
        store.select(ref)
        val preview = preview(ref)

        store.previewLoaded(preview)

        val loaded = assertIs<ScreenState.Loaded<SnapshotPreview>>(store.state.value.preview)
        assertEquals(preview, loaded.data)
    }

    @Test
    fun `previewLoaded игнорирует поздний ответ для другого снапшота`() {
        val other = SnapshotRef("refs/ai/snap/2-before-agent-step")
        store.select(ref)

        store.previewLoaded(preview(other))

        assertEquals(ScreenState.Loading, store.state.value.preview)
    }

    @Test
    fun `previewFailed переводит ошибку в состояние экрана`() {
        store.select(ref)

        store.previewFailed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.DIRTY_WORKTREE), ref)

        val failed = assertIs<ScreenState.Failed>(store.state.value.preview)
        assertEquals(ScreenState.ErrorKind.ROLLBACK_REJECTED, failed.kind)
    }

    @Test
    fun `revert ставит признак выполнения`() {
        store.revert(ref)

        assertEquals(ref, store.state.value.reverting)
        assertNull(store.state.value.revertError)
    }

    @Test
    fun `revertSucceeded сохраняет хеш обратного коммита`() {
        store.revert(ref)

        store.revertSucceeded(ref, "revert-commit-1")

        assertNull(store.state.value.reverting)
        assertNull(store.state.value.revertError)
        assertEquals("revert-commit-1", store.state.value.completedCommit)
    }

    @Test
    fun `revertSucceeded игнорирует поздний успех для другого снапшота`() {
        val other = SnapshotRef("refs/ai/snap/2-before-agent-step")
        store.revert(ref)

        store.revertSucceeded(other, "revert-commit-2")

        assertEquals(ref, store.state.value.reverting)
        assertNull(store.state.value.completedCommit)
    }

    @Test
    fun `revertFailed кладёт ошибку`() {
        store.revert(ref)

        store.revertFailed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.ACTIVE_RUN), ref)

        assertNull(store.state.value.reverting)
        val error = assertIs<ScreenState.Failed>(store.state.value.revertError)
        assertEquals(ScreenState.ErrorKind.ROLLBACK_REJECTED, error.kind)
    }

    @Test
    fun `previewFailed игнорирует позднюю ошибку для другого снапшота`() {
        val other = SnapshotRef("refs/ai/snap/2-before-agent-step")
        store.select(ref)

        store.previewFailed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.DIRTY_WORKTREE), other)

        assertEquals(ScreenState.Loading, store.state.value.preview)
    }

    @Test
    fun `revertFailed игнорирует позднюю ошибку для другого снапшота`() {
        val other = SnapshotRef("refs/ai/snap/2-before-agent-step")
        store.revert(ref)

        store.revertFailed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.ACTIVE_RUN), other)

        assertEquals(ref, store.state.value.reverting)
        assertNull(store.state.value.revertError)
    }

    @Test
    fun `select сбрасывает выполняющийся откат`() {
        store.revert(ref)
        val other = SnapshotRef("refs/ai/snap/2-before-agent-step")

        store.select(other)

        assertEquals(other, store.state.value.selected)
        assertNull(store.state.value.reverting)
        assertNull(store.state.value.completedCommit)
        assertNull(store.state.value.revertError)
    }

    @Test
    fun `dismiss сбрасывает состояние отката`() {
        store.loaded(listOf(snapshot(ref)))
        store.select(ref)
        store.revert(ref)
        store.revertFailed(ProtocolError.SnapshotRollbackRejected(SnapshotRollbackRejection.ACTIVE_RUN), ref)

        store.dismiss()

        assertNull(store.state.value.selected)
        assertNull(store.state.value.preview)
        assertNull(store.state.value.reverting)
        assertNull(store.state.value.revertError)
        assertNull(store.state.value.completedCommit)
        assertIs<ScreenState.Loaded<List<Snapshot>>>(store.state.value.list)
    }

    @Test
    fun `потеря связи переводит загруженный список в офлайн`() {
        store.loaded(listOf(snapshot(ref)))

        store.setOffline(true)

        val offline = assertIs<ScreenState.Offline<List<Snapshot>>>(store.state.value.list)
        assertEquals(1, offline.cached.size)
    }

    @Test
    fun `восстановление связи возвращает список из кэша`() {
        store.loaded(listOf(snapshot(ref)))
        store.setOffline(true)

        store.setOffline(false)

        assertIs<ScreenState.Loaded<List<Snapshot>>>(store.state.value.list)
    }

    private companion object {
        fun snapshot(ref: SnapshotRef): Snapshot = Snapshot(
            ref = ref,
            label = "before-agent-step",
            commit = "abc",
            trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
            createdAt = Instant.fromEpochMilliseconds(1),
        )

        fun preview(ref: SnapshotRef): SnapshotPreview = SnapshotPreview(
            snapshot = snapshot(ref),
            headCommit = "head-1",
            files = listOf(
                SnapshotFileChange("a.txt", changeKind = FileChangeKind.MODIFIED, addedLines = 2, removedLines = 1),
            ),
        )
    }
}
