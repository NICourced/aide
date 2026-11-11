package dev.aide.host.risk

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.HunkKind
import dev.aide.domain.HunkLine
import dev.aide.domain.LineKind
import dev.aide.domain.LintFinding
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import dev.aide.host.store.HostStore
import dev.aide.host.store.db.HostDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/** Детерминированная оценка риска на основе файлов и отчёта прогона (T-1.16). */
class DeterministicChangeRiskTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))
    private val risk = DeterministicChangeRisk(store.runs)

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `документация на пороге с зелёным отчётом и пустым линтером — SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 50)))

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    @Test
    fun `тест на пороге с зелёным отчётом и пустым линтером — SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("src/test/AppTest.kt", added = 50)))

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    @Test
    fun `локализация на пороге с зелёным отчётом и пустым линтером — SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(
                runId,
                "write_file",
                listOf(fileChange("src/main/res/values-en/strings.xml", added = 50)),
            )

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    @Test
    fun `51 строка при пороге 50 — NORMAL`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 51)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `250 строк при пороге 50 — NORMAL`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 250)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `251 строка при пороге 50 — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 251)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `обычный исходник — NORMAL`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("src/main/App.kt", added = 10)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `GREEN без отчёта линтера — NORMAL`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = null))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `RED без отчёта линтера — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.RED, lintFindings = null))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `отсутствие отчёта даёт NORMAL`() {
        runBlocking {
            val runId = saveRun(null)

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `отсутствие прогона даёт NORMAL`() {
        runBlocking {
            val level = risk.assess(RunId("missing"), "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `красные тесты — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.RED, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `замечания линтера сбрасывают SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = listOf(LintFinding("x.kt"))))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.NORMAL, level)
        }
    }

    @Test
    fun `удаление файла — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(
                runId,
                "write_file",
                listOf(fileChange("docs/readme.md", added = 0, removed = 10, kind = FileChangeKind.DELETED)),
            )

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `proto файл — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("src/api/service.proto", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `sql файл — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("db/migration/001.sql", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `env файл — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("config/.env.local", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `migration в корне без SQL-расширения — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("migration/001.json", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `migrations в корне без SQL-расширения — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("migrations/002.yaml", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `permissions в корне — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("permissions/roles.json", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `migration под docs без SQL-расширения — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/migration/001.json", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `permissions под tests — RISKY`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("tests/permissions/roles.json", added = 10)))

            assertEquals(RiskLevel.RISKY, level)
        }
    }

    @Test
    fun `docs без спецпризнаков — SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 10)))

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    @Test
    fun `tests без спецпризнаков — SAFE`() {
        runBlocking {
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()))

            val level = risk.assess(runId, "write_file", listOf(fileChange("tests/AppTest.kt", added = 10)))

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    @Test
    fun `настроенный порог читается из прогона`() {
        runBlocking {
            // 100 строк при пороге 100 — ещё SAFE; при умолчании 50 тот же объём дал бы NORMAL,
            // поэтому этот тест различает «читаем порог прогона» от «всегда используем 50».
            val runId = saveRun(TestReport(state = TestState.GREEN, lintFindings = emptyList()), threshold = 100)

            val level = risk.assess(runId, "write_file", listOf(fileChange("docs/readme.md", added = 100)))

            assertEquals(RiskLevel.SAFE, level)
        }
    }

    private fun saveRun(report: TestReport?, threshold: Int = 50): RunId {
        val runId = RunId("r-${java.util.UUID.randomUUID()}")
        store.runs.save(
            AgentRun(
                id = runId,
                taskId = TaskId("t-1"),
                state = RunState.RUNNING,
                mode = AutonomyMode.AUTO_APPLY_SAFE,
                startedAt = Instant.parse("2026-09-22T10:00:00Z"),
                testReport = report,
                safeVolumeThresholdLines = threshold,
            ),
        )
        return runId
    }

    private fun fileChange(
        path: String,
        added: Int,
        removed: Int = 0,
        kind: FileChangeKind = FileChangeKind.MODIFIED,
    ): FileChange {
        val lines = buildList {
            repeat(removed) { add(HunkLine(LineKind.REMOVED, oldNumber = it + 1, text = "old")) }
            repeat(added) { add(HunkLine(LineKind.ADDED, newNumber = it + 1, text = "new")) }
        }
        val hunkKind = when {
            kind == FileChangeKind.DELETED -> HunkKind.REMOVE
            removed == 0 -> HunkKind.ADD
            else -> HunkKind.REPLACE
        }
        return FileChange(
            path = path,
            changeKind = kind,
            hunks = listOf(
                Hunk(
                    id = HunkId("hunk-$path"),
                    filePath = path,
                    startLine = 1,
                    kind = hunkKind,
                    risk = RiskLevel.SAFE,
                    lines = lines,
                ),
            ),
            addedLines = added,
            removedLines = removed,
        )
    }
}
