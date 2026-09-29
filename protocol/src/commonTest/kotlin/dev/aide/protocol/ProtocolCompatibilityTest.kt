package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

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
        assertTrue(result.userMessage.contains("обновите приложение", ignoreCase = true))
    }

    @Test
    fun `версия клиента новее по major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(3, 1), host = ProtocolVersion(2, 9)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
        assertTrue(result.userMessage.contains("обновите хост", ignoreCase = true))
    }

    @Test
    fun `клиент с большим minor при том же major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
    }

    @Test
    fun `сообщение несовместимости содержит обе версии, чтобы пользователь видел, что обновлять`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertTrue(result.userMessage.contains("1.5"))
        assertTrue(result.userMessage.contains("1.2"))
    }

    @Test
    fun `сообщение о несовпадении major называет обе версии`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 0), host = ProtocolVersion(2, 0)),
        )
        assertTrue(result.userMessage.contains("1.0"))
        assertTrue(result.userMessage.contains("2.0"))
    }

    @Test
    fun `ответ хоста при несовместимости содержит причину и версию хоста`() {
        val incompatible = ProtocolCompatibility.toHostMessage(
            result = ProtocolCompatibility.Incompatible(
                reason = IncompatibilityReason.CLIENT_OUTDATED,
                userMessage = "Обновите приложение",
            ),
            hostVersion = ProtocolVersion(2, 0),
        )
        assertEquals(IncompatibilityReason.CLIENT_OUTDATED, incompatible.reason)
        assertEquals(ProtocolVersion(2, 0), incompatible.hostVersion)
    }
}
