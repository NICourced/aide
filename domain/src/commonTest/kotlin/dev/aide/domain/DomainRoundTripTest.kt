package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /**
     * Двойник [ChangePacket] без `init`-валидации: только через него можно собрать байты,
     * которые конструктор пакета отверг бы. Имена и типы полей совпадают с настоящим
     * пакетом, поэтому [Cbor] разбирает эти байты как [ChangePacket].
     */
    @Serializable
    private data class RawChangePacket(
        val id: PacketId,
        val taskId: TaskId,
        val revision: Int,
        val title: String,
        val summary: String,
        val files: List<FileChange>,
        val risk: RiskLevel,
        val tests: TestStatus,
        val source: ChangeSource,
        val status: PacketStatus,
        val branch: String,
        val snapshotRef: SnapshotRef? = null,
        val createdAt: Instant,
    )

    private fun rawPacket(risk: RiskLevel, files: List<FileChange>): RawChangePacket {
        val packet = DomainFixtures.packet
        return RawChangePacket(
            id = packet.id,
            taskId = packet.taskId,
            revision = packet.revision,
            title = packet.title,
            summary = packet.summary,
            files = files,
            risk = risk,
            tests = packet.tests,
            source = packet.source,
            status = packet.status,
            branch = packet.branch,
            snapshotRef = packet.snapshotRef,
            createdAt = packet.createdAt,
        )
    }

    @Test
    fun `разбор CBOR с риском ниже максимума по hunk-ам отвергается`() {
        val riskyHunk = DomainFixtures.hunk.copy(risk = RiskLevel.RISKY)
        val bytes = cbor.encodeToByteArray(
            rawPacket(risk = RiskLevel.SAFE, files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(riskyHunk)))),
        )

        // Согласованный вход разбирается в объект…
        assertEquals(RiskLevel.RISKY, roundTrip(DomainFixtures.packet.copy(risk = RiskLevel.RISKY)).risk)

        // …а несовместимый с инвариантом 5 — нет: валидация живёт в конструкторе,
        // и десериализатор вызывает именно его, поэтому обойти инвариант готовым CBOR нельзя.
        val violation = assertFailsWith<DomainViolation> { cbor.decodeFromByteArray<ChangePacket>(bytes) }
        assertEquals(DomainInvariant.PACKET_RISK_NOT_BELOW_HUNKS, violation.invariant)
    }

    @Test
    fun `обрезанный CBOR падает на разборе, не доходя до валидации`() {
        // Тест из плана подаёт `{"risk":"SAFE"}` — это не CBOR пакета, поэтому разбор
        // отвергает вход раньше, чем дело дойдёт до `init`-блока и инварианта 5.
        // Ценность теста в другом: неполный вход не превращается в объект.
        assertFailsWith<SerializationException> {
            cbor.decodeFromByteArray<ChangePacket>("""{"risk":"SAFE"}""".toByteArray())
        }
    }
}
