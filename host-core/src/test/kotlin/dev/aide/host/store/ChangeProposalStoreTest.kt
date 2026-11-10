package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.ChangeProposal
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.ProposalId
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCallId
import dev.aide.host.store.db.HostDatabase
import java.nio.file.Files
import java.sql.SQLException
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlinx.datetime.Instant

/** Постоянная очередь неприменённых предложений (T-1.15). */
class ChangeProposalStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `сохранённое предложение читается по идентификатору`() {
        val proposal = proposal(id = "cp-1", runId = "r-1")

        store.proposals.save(proposal)

        assertEquals(proposal, store.proposals.load(ProposalId("cp-1")))
    }

    @Test
    fun `предложения читаются по прогону в порядке создания`() {
        store.proposals.save(proposal(id = "cp-1", runId = "r-1", createdAt = 1_000))
        store.proposals.save(proposal(id = "cp-2", runId = "r-1", createdAt = 2_000))
        store.proposals.save(proposal(id = "cp-3", runId = "r-2", createdAt = 3_000))

        val found = store.proposals.byRun(RunId("r-1"))

        assertEquals(listOf("cp-1", "cp-2"), found.map { it.id.value })
    }

    @Test
    fun `предложения читаются по задаче через оба её прогона`() {
        store.runs.save(StoreFixtures.run)
        store.runs.save(StoreFixtures.runWithUnknownCost)
        store.runs.save(StoreFixtures.run.copy(id = RunId("r-3"), taskId = TaskId("t-2")))
        store.proposals.save(proposal(id = "cp-1", runId = "r-1", createdAt = 1_000))
        store.proposals.save(proposal(id = "cp-2", runId = "r-2", createdAt = 2_000))
        store.proposals.save(proposal(id = "cp-3", runId = "r-3", createdAt = 3_000))

        val found = store.proposals.byTask(TaskId("t-1"))

        assertEquals(listOf("cp-1", "cp-2"), found.map { it.id.value })
    }

    @Test
    fun `предложение ищется по вызову`() {
        store.proposals.save(proposal(id = "cp-1", callId = "tc-1"))
        store.proposals.save(proposal(id = "cp-2", callId = "tc-2"))

        assertEquals("cp-1", store.proposals.byCall(ToolCallId("tc-1"))?.id?.value)
        assertNull(store.proposals.byCall(ToolCallId("tc-нет")))
    }

    @Test
    fun `все предложения хоста в порядке создания`() {
        store.proposals.save(proposal(id = "cp-1", createdAt = 2_000))
        store.proposals.save(proposal(id = "cp-2", createdAt = 1_000))

        assertEquals(listOf("cp-2", "cp-1"), store.proposals.all().map { it.id.value })
    }

    @Test
    fun `повторный идентификатор не перезаписывает предложение`() {
        val original = proposal(id = "cp-1", runId = "r-1")
        store.proposals.save(original)

        assertFailsWith<SQLException> {
            store.proposals.save(proposal(id = "cp-1", runId = "r-2"))
        }

        assertEquals(original, store.proposals.load(ProposalId("cp-1")))
        assertEquals(1L, store.proposals.count())
    }

    @Test
    fun `предложение переживает закрытие и повторное открытие базы`() {
        val directory = Files.createTempDirectory("aide-proposal-restart-")
        val path = directory.resolve("host.db")
        val saved = proposal(id = "cp-restart")
        try {
            DatabaseFactory.open(path).use { it.proposals.save(saved) }

            DatabaseFactory.open(path).use { reopened ->
                assertEquals(saved, reopened.proposals.load(ProposalId("cp-restart")))
            }
        } finally {
            directory.toFile().deleteRecursively()
        }
    }

    private fun proposal(
        id: String,
        runId: String = "r-1",
        callId: String = "tc-1",
        createdAt: Long = 1_000,
    ): ChangeProposal = ChangeProposal(
        id = ProposalId(id),
        runId = RunId(runId),
        callId = ToolCallId(callId),
        tool = "write_file",
        files = listOf(
            FileChange(
                path = "src/App.kt",
                changeKind = FileChangeKind.ADDED,
                hunks = listOf(
                    Hunk(
                        id = HunkId("h-$id"),
                        filePath = "src/App.kt",
                        startLine = 1,
                        kind = HunkKind.ADD,
                        risk = RiskLevel.NORMAL,
                        lines = listOf(
                            HunkLine(kind = LineKind.ADDED, oldNumber = null, newNumber = 1, text = "fun main()"),
                        ),
                    ),
                ),
                addedLines = 1,
                removedLines = 0,
            ),
        ),
        risk = RiskLevel.NORMAL,
        createdAt = Instant.fromEpochMilliseconds(createdAt),
    )
}
