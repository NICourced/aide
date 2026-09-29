package dev.aide.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip: сериализация и обратный разбор дают равный объект (T-0.5).
 * Формат — CBOR, тот же, что поедет по сети в задаче 8.
 * CBOR в kotlinx.serialization помечен экспериментальным — отсюда opt-in.
 */
@OptIn(ExperimentalSerializationApi::class)
class DomainRoundTripTest {

    private val cbor = Cbor { ignoreUnknownKeys = true }
    private inline fun <reified T> roundTrip(value: T): T =
        cbor.decodeFromByteArray(cbor.encodeToByteArray(value))

    @Test
    fun `RiskLevel переживает round-trip`() {
        assertEquals(RiskLevel.RISKY, roundTrip(RiskLevel.RISKY))
    }

    @Test
    fun `AutonomyMode переживает round-trip`() {
        assertEquals(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, roundTrip(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS))
    }

    @Test
    fun `ToolPermission переживает round-trip`() {
        assertEquals(DomainFixtures.toolPermission, roundTrip(DomainFixtures.toolPermission))
    }

    @Test
    fun `Task переживает round-trip`() {
        assertEquals(DomainFixtures.task, roundTrip(DomainFixtures.task))
    }

    @Test
    fun `AgentRun переживает round-trip`() {
        assertEquals(DomainFixtures.run, roundTrip(DomainFixtures.run))
    }

    @Test
    fun `AgentRun с пустыми списками и null-полями переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.emptyRun)
        assertEquals(DomainFixtures.emptyRun, decoded)
        assertTrue(decoded.plan.isEmpty())
        assertNull(decoded.finishedAt)
        assertEquals(false, decoded.cost.known)
    }

    @Test
    fun `ToolCall переживает round-trip`() {
        assertEquals(DomainFixtures.toolCall, roundTrip(DomainFixtures.toolCall))
    }

    @Test
    fun `Hunk переживает round-trip`() {
        assertEquals(DomainFixtures.hunk, roundTrip(DomainFixtures.hunk))
    }

    @Test
    fun `FileChange переживает round-trip и сохраняет previousPath`() {
        val decoded = roundTrip(DomainFixtures.fileChangeRenamed)
        assertEquals("src/auth/Login.kt", decoded.previousPath)
        assertEquals(DomainFixtures.fileChangeRenamed, decoded)
    }

    @Test
    fun `ChangePacket с вложенными hunk-ами переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.packet)
        assertEquals(DomainFixtures.packet, decoded)
        assertEquals(2, decoded.files.size)
        assertEquals(DomainFixtures.hunk, decoded.files.first().hunks.first())
    }

    @Test
    fun `ChangePacket без файлов переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.emptyPacket)
        assertTrue(decoded.files.isEmpty())
        assertEquals(TestState.NOT_RUN, decoded.tests.state)
        assertNull(decoded.snapshotRef)
    }

    @Test
    fun `ReviewDecision на уровне блока переживает round-trip`() {
        assertEquals(DomainFixtures.decision, roundTrip(DomainFixtures.decision))
    }

    @Test
    fun `ReviewDecision на уровне пакета переживает round-trip и сохраняет null`() {
        val decoded = roundTrip(DomainFixtures.packetLevelDecision)
        assertEquals(DomainFixtures.packetLevelDecision, decoded)
        assertNull(decoded.targetHunkId)
        assertNull(decoded.comment)
    }

    @Test
    fun `Snapshot переживает round-trip`() {
        assertEquals(DomainFixtures.snapshot, roundTrip(DomainFixtures.snapshot))
    }

    @Test
    fun `производные счётчики строк считаются по файлам`() {
        assertEquals(2, DomainFixtures.packet.addedLines)
        assertEquals(2, DomainFixtures.packet.removedLines)
    }
}
