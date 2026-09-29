package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ProtocolCompatibilityTest {

    @Test
    fun `одинаковые версии совместимы`() {
        val result = ProtocolCompatibility.check(
            client = ProtocolVersion(1, 0),
            host = ProtocolVersion(1, 0),
        )
        assertEquals(ProtocolCompatibility.Compatible, result)
    }

    @Test
    fun `клиент с меньшим minor при том же major совместим`() {
        val result = ProtocolCompatibility.check(
            client = ProtocolVersion(1, 0),
            host = ProtocolVersion(1, 3),
        )
        assertEquals(ProtocolCompatibility.Compatible, result)
    }

    @Test
    fun `версия клиента старше по major — обновите приложение`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 0), host = ProtocolVersion(2, 0)),
        )
        assertEquals(IncompatibilityReason.CLIENT_OUTDATED, result.reason)
    }

    @Test
    fun `версия клиента новее по major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(3, 1), host = ProtocolVersion(2, 9)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
    }

    @Test
    fun `клиент с большим minor при том же major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
    }

    @Test
    fun `результат несовместимости несёт обе версии, чтобы UI показал, что обновлять`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertEquals(ProtocolVersion(1, 5), result.clientVersion)
        assertEquals(ProtocolVersion(1, 2), result.hostVersion)
    }

    @Test
    fun `несовпадение major тоже называет обе версии`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 0), host = ProtocolVersion(2, 0)),
        )
        assertEquals(ProtocolVersion(1, 0), result.clientVersion)
        assertEquals(ProtocolVersion(2, 0), result.hostVersion)
    }

    @Test
    fun `ответ хоста при несовместимости содержит причину и версию хоста`() {
        val incompatible = ProtocolCompatibility.toHostMessage(
            result = ProtocolCompatibility.Incompatible(
                reason = IncompatibilityReason.CLIENT_OUTDATED,
                clientVersion = ProtocolVersion(1, 0),
                hostVersion = ProtocolVersion(2, 0),
            ),
            hostVersion = ProtocolVersion(2, 0),
        )
        assertEquals(IncompatibilityReason.CLIENT_OUTDATED, incompatible.reason)
        assertEquals(ProtocolVersion(2, 0), incompatible.hostVersion)
    }
}
