package dev.aide.client.state

import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.IncompatibilityReason
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.SessionId
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateStoreTest {

    private val workspaceId = WorkspaceId("ws-1")
    private val store = AppStateStore()

    private val tree = FileTreePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        entries = listOf(
            FileTreeEntry("src", isDirectory = true),
            FileTreeEntry("src/Login.kt", isDirectory = false, sizeBytes = 12),
        ),
        truncated = false,
    )

    private val hostState = HostStatePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        branch = "master",
        headCommit = "abc1234",
        uptimeMillis = 10,
        mode = HostMode.LOCAL,
    )

    @Test
    fun `начальное состояние — репозиторий не выбран`() {
        assertEquals(ScreenState.NoRepository, store.treeState.value)
        assertNull(store.selectedFile.value)
        assertNull(store.hostState.value)
    }

    @Test
    fun `успешная загрузка даёт данные`() {
        store.onTreeLoaded(tree)

        val loaded = assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState.value)
        assertEquals(2, loaded.data.entries.size)
    }

    @Test
    fun `пустое дерево даёт состояние «пусто», а не пустой экран`() {
        store.onTreeLoaded(tree.copy(entries = emptyList()))

        assertEquals(ScreenState.Empty, store.treeState.value)
    }

    @Test
    fun `ошибка пути даёт состояние ошибки с путём в параметрах`() {
        store.onTreeFailed(ProtocolError.NotFound("/nope"))

        val failed = assertIs<ScreenState.Failed>(store.treeState.value)
        assertEquals(ScreenState.ErrorKind.PATH_MISSING, failed.kind)
        assertEquals(listOf("/nope"), failed.arguments)
    }

    @Test
    fun `каталог без git даёт отдельный вид ошибки`() {
        store.onTreeFailed(ProtocolError.NotAGitRepository("/tmp/not-a-repo"))

        val failed = assertIs<ScreenState.Failed>(store.treeState.value)
        assertEquals(ScreenState.ErrorKind.NOT_A_REPOSITORY, failed.kind)
        assertEquals(listOf("/tmp/not-a-repo"), failed.arguments)
    }

    @Test
    fun `закрытый воркспейс даёт отдельный вид ошибки, а не внутреннюю`() {
        store.onTreeFailed(ProtocolError.WorkspaceClosed(workspaceId))

        val failed = assertIs<ScreenState.Failed>(store.treeState.value)
        assertEquals(ScreenState.ErrorKind.WORKSPACE_CLOSED, failed.kind)
    }

    @Test
    fun `внутренняя ошибка остаётся видом OTHER с текстом хоста`() {
        store.onTreeFailed(ProtocolError.Internal("не удалось открыть репозиторий", "trace"))

        val failed = assertIs<ScreenState.Failed>(store.treeState.value)
        assertEquals(ScreenState.ErrorKind.OTHER, failed.kind)
        assertEquals(listOf("не удалось открыть репозиторий"), failed.arguments)
        assertEquals("trace", failed.technical)
    }

    @Test
    fun `ошибка доступа даёт состояние «нет прав»`() {
        store.onTreeFailed(ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"))

        val denied = assertIs<ScreenState.NoPermission>(store.treeState.value)
        assertEquals("/etc/passwd", denied.path)
        assertEquals("вне корня воркспейса", denied.reason)
    }

    @Test
    fun `потеря связи перекрывает загруженные данные, но не стирает их`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(ConnectionState.Reconnecting(attempt = 1, nextRetryMillis = 100))

        val offline = assertIs<ScreenState.Offline<FileTreePayload>>(store.treeState.value)
        // Тройной assertEquals с `Int` разрешается в перегрузку с допуском `Double` —
        // поэтому сравнение через assertTrue, а не через трёхаргументный assertEquals.
        assertTrue(offline.cached.entries.size == 2, "Кэш остаётся доступен офлайн")
    }

    @Test
    fun `восстановление связи возвращает загруженное состояние`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(ConnectionState.Reconnecting(1, 100))
        store.onConnectionState(ConnectionState.Connected(sessionId = SessionId("s"), reconnected = true))

        assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState.value)
    }

    @Test
    fun `несовместимость версий показывается как ошибка и не даёт работать`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(
            ConnectionState.Incompatible(
                reason = IncompatibilityReason.CLIENT_OUTDATED,
                clientVersion = ProtocolVersion(1, 0),
                hostVersion = ProtocolVersion(2, 0),
            ),
        )

        val failed = assertIs<ScreenState.Failed>(store.treeState.value)
        assertEquals(ScreenState.ErrorKind.INCOMPATIBLE, failed.kind)
        assertEquals(listOf("CLIENT_OUTDATED", "1.0", "2.0"), failed.arguments)
    }

    @Test
    fun `событие остановки хоста переводит загруженные данные в офлайн, сохраняя кэш`() {
        store.onTreeLoaded(tree)

        store.onHostShuttingDown()

        val offline = assertIs<ScreenState.Offline<FileTreePayload>>(store.treeState.value)
        assertTrue(offline.cached.entries.size == 2, "Кэш остаётся доступен офлайн")
    }

    @Test
    fun `выбор файла и его содержимое`() {
        store.onTreeLoaded(tree)
        store.selectFile("src/Login.kt")

        assertEquals("src/Login.kt", store.selectedFile.value)
        assertIs<ScreenState.Loading>(store.fileState.value)

        val content = FileContentPayload(workspaceId, "src/Login.kt", "fun login() = Unit\n", 19, false, "kotlin")
        store.onFileLoaded(content)

        val loaded = assertIs<ScreenState.Loaded<FileContentPayload>>(store.fileState.value)
        assertEquals("fun login() = Unit\n", loaded.data.text)
    }

    @Test
    fun `снятие выбора файла возвращает пустое состояние`() {
        store.onTreeLoaded(tree)
        store.selectFile("src/Login.kt")
        store.selectFile(null)

        assertNull(store.selectedFile.value)
        assertEquals(ScreenState.Empty, store.fileState.value)
    }

    @Test
    fun `состояние хоста сохраняется для шапки`() {
        store.onHostState(hostState)

        assertEquals("master", store.hostState.value?.branch)
    }

    @Test
    fun `загрузка показывается отдельным состоянием`() {
        store.onTreeLoading()

        assertEquals(ScreenState.Loading, store.treeState.value)
    }

    @Test
    fun `сессия клиента отдаёт обновлённое дерево на экран`() {
        store.onHostSession(HostSession(tree = tree))
        val loaded = assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState.value)
        assertEquals(2, loaded.data.entries.size)

        val updated = tree.copy(entries = listOf(FileTreeEntry("src/New.kt", isDirectory = false)))
        store.onHostSession(HostSession(tree = updated))

        val refreshed = assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState.value)
        assertEquals("src/New.kt", refreshed.data.entries.single().path)
    }

    @Test
    fun `сессия без нового дерева не затирает показанную ошибку`() {
        store.onHostSession(HostSession(tree = tree))
        store.onTreeFailed(ProtocolError.NotFound("путь не существует: /nope"))

        // Сессия меняется по другому поводу (состояние хоста), дерево — тот же объект.
        store.onHostSession(HostSession(tree = tree, hostState = hostState))

        assertIs<ScreenState.Failed>(store.treeState.value)
    }
}
