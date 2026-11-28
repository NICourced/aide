package dev.aide.host.packet

import dev.aide.domain.ChangePacket
import dev.aide.domain.PacketId
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.WorkspaceAccessException
import dev.aide.protocol.DiffContextPayload
import dev.aide.protocol.DiffContextSource
import dev.aide.protocol.ProtocolError

/**
 * Чтение контекста из закреплённых версий пакета (T-1.24).
 *
 * Версии берутся из blob'ов git по [baseCommit]/[headCommit] сохранённой ревизии,
 * а не из текущего рабочего дерева: доработка не должна менять контекст старого пакета.
 */
class PacketContextReader(
    private val workspaces: OpenWorkspaces,
    private val store: HostStore,
) {

    /** Читает диапазон строк файла из OLD/NEW версии пакета. */
    @Suppress("LongParameterList") // публичный API: параметры соответствуют протокольному сообщению
    fun read(
        packetId: PacketId,
        revision: Int,
        path: String,
        source: DiffContextSource,
        startLine: Int,
        lineCount: Int,
    ): DiffContextPayload {
        val packet = store.packets.load(packetId, revision)
            ?: throw WorkspaceAccessException(ProtocolError.NotFound("пакет ${packetId.value}:$revision"))
        val commit = resolveCommit(packet, source)
        val opened = workspaces.current()
            ?: throw WorkspaceAccessException(ProtocolError.NotFound("нет открытого воркспейса"))
        val lines = opened.git.changeSets.readContext(commit, path, startLine, lineCount)
        return DiffContextPayload(
            workspaceId = opened.workspace.id,
            path = path,
            startLine = startLine,
            lines = lines,
        )
    }

    private fun resolveCommit(packet: ChangePacket, source: DiffContextSource): String {
        val commit = when (source) {
            DiffContextSource.PACKET_OLD -> packet.baseCommit
            DiffContextSource.PACKET_NEW -> packet.headCommit
            DiffContextSource.WORKSPACE -> error("WORKSPACE обрабатывается WorkspaceFileSystem")
        }
        return commit ?: throw WorkspaceAccessException(
            ProtocolError.NotImplemented("пакет ${packet.id.value}:${packet.revision} не хранит commit для $source"),
        )
    }
}
