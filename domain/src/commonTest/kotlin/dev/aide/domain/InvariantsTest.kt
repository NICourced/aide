package dev.aide.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Инварианты 1 и 5 § 4.1, закрытые валидацией в конструкторе [ChangePacket] (T-0.6).
 *
 * `copy` тоже проходит через конструктор, поэтому пакет с нарушением нельзя собрать
 * ни напрямую, ни копированием. Остальные четыре инварианта § 4.1 — свойства процесса
 * и проверяются в задачах хоста (T-1.24, T-1.19, T-1.11, T-1.37).
 */
class InvariantsTest {

    // Инвариант 1: каждый Hunk принадлежит ровно одному FileChange,
    // каждый FileChange — ровно одному ChangePacket.

    @Test
    fun `пакет с двумя файлами по одному пути отвергается`() {
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(
                    DomainFixtures.fileChange,
                    DomainFixtures.fileChange.copy(addedLines = 5),
                ),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)
    }

    @Test
    fun `файл с hunk-ом, чей filePath не совпадает с путём файла, отвергается`() {
        val foreign = DomainFixtures.hunk.copy(id = HunkId("h-foreign"), filePath = "src/other/File.kt")
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(foreign))),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)
        assertTrue(violation.message!!.contains("src/other/File.kt"))
    }

    @Test
    fun `файл без hunk-ов отвергается, кроме удаления файла`() {
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = emptyList())),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)

        // Удаление файла — единственный случай, когда hunk-ов может не быть.
        val deleted = DomainFixtures.packet.copy(
            files = listOf(
                DomainFixtures.fileChange.copy(
                    changeKind = FileChangeKind.DELETED,
                    hunks = emptyList(),
                    addedLines = 0,
                ),
            ),
            risk = RiskLevel.RISKY,
        )
        assertEquals(FileChangeKind.DELETED, deleted.files.single().changeKind)
    }

    @Test
    fun `корректный пакет создаётся без исключения`() {
        val packet = DomainFixtures.packet.copy(risk = RiskLevel.SAFE)
        assertEquals(2, packet.files.size)
    }

    // Инвариант 5: RiskLevel пакета не может быть ниже максимального RiskLevel его hunk-ов.

    @Test
    fun `риск пакета ниже риска hunk-а отвергается`() {
        val riskyHunk = DomainFixtures.hunk.copy(risk = RiskLevel.RISKY)
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(riskyHunk))),
                risk = RiskLevel.SAFE,
            )
        }
        assertEquals(DomainInvariant.PACKET_RISK_NOT_BELOW_HUNKS, violation.invariant)
        assertTrue(violation.message!!.contains("RISKY"))
    }

    @Test
    fun `риск пакета выше максимума по hunk-ам допускается`() {
        val packet = DomainFixtures.packet.copy(risk = RiskLevel.RISKY)
        assertEquals(RiskLevel.RISKY, packet.risk)
    }

    @Test
    fun `риск пакета равный максимуму по hunk-ам допускается`() {
        val normalHunk = DomainFixtures.hunk.copy(risk = RiskLevel.NORMAL)
        val packet = DomainFixtures.packet.copy(
            files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(normalHunk))),
            risk = RiskLevel.NORMAL,
        )
        assertEquals(RiskLevel.NORMAL, packet.risk)
    }

    @Test
    fun `пакет без файлов проходит проверку риска`() {
        val packet = DomainFixtures.packet.copy(files = emptyList())
        assertTrue(packet.files.isEmpty())
        assertEquals(RiskLevel.SAFE, packet.risk)
    }
}
