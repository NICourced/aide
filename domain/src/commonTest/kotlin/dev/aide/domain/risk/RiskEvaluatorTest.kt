package dev.aide.domain.risk

import dev.aide.domain.DomainFixtures
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.HunkId
import dev.aide.domain.RiskLevel
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RiskEvaluatorTest {

    private val defaultThreshold = 50

    private fun hunk(path: String, risk: RiskLevel = RiskLevel.SAFE) =
        DomainFixtures.hunk.copy(id = HunkId("h-$path"), filePath = path, risk = risk)

    private fun file(
        path: String,
        kind: FileChangeKind = FileChangeKind.MODIFIED,
        added: Int = 1,
        risk: RiskLevel = RiskLevel.SAFE,
    ) = FileChange(
        path = path,
        changeKind = kind,
        hunks = if (kind == FileChangeKind.DELETED) emptyList() else listOf(hunk(path, risk)),
        addedLines = if (kind == FileChangeKind.DELETED) 0 else added,
        removedLines = if (kind == FileChangeKind.DELETED) 10 else 0,
    )

    private fun evaluate(
        files: List<FileChange>,
        tests: TestStatus = TestStatus(TestState.GREEN),
        volume: Int = files.sumOf { it.addedLines + it.removedLines },
        threshold: Int = defaultThreshold,
    ) = RiskEvaluator.evaluate(
        changes = RiskEvaluator.Input(
            files = files,
            tests = tests,
            totalChangedLines = volume,
            touchesPublicApi = false,
            touchesDatabaseSchema = false,
            touchesPermissionsOrSecrets = false,
        ),
        volumeThresholdLines = threshold,
    )

    // ——— safe ———

    @Test
    fun `только тесты, зелёные тесты и объём ниже порога — safe`() {
        val risk = evaluate(listOf(file("src/test/auth/LoginTest.kt", added = 10)))
        assertEquals(RiskLevel.SAFE, risk)
    }

    @Test
    fun `только документация — safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("docs/auth.md", added = 3))))
    }

    @Test
    fun `только локализация — safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("src/main/res/values/strings.xml", added = 2))))
    }

    // ——— границы safe ———

    @Test
    fun `объём ровно на пороге — ещё safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("src/test/a/ATest.kt", added = 50))))
    }

    @Test
    fun `объём на единицу выше порога — уже normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/test/a/ATest.kt", added = 51))))
    }

    @Test
    fun `тесты красные на тестовом файле — risky, а не safe`() {
        val risk = evaluate(
            files = listOf(file("src/test/auth/LoginTest.kt")),
            tests = TestStatus(state = TestState.RED, failed = listOf("login_issues_token")),
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `замечания линтера снимают safe`() {
        val risk = evaluate(
            files = listOf(file("docs/auth.md")),
            tests = TestStatus(state = TestState.GREEN, lintFindings = 1),
        )
        assertEquals(RiskLevel.NORMAL, risk)
    }

    // ——— risky ———

    @Test
    fun `удаление файла — risky`() {
        val risk = evaluate(listOf(file("src/auth/Legacy.kt", kind = FileChangeKind.DELETED)))
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `публичный API — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/auth/Api.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = true,
                touchesDatabaseSchema = false,
                touchesPermissionsOrSecrets = false,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `схема БД — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/db/Migrations.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = false,
                touchesDatabaseSchema = true,
                touchesPermissionsOrSecrets = false,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `права и секреты — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/auth/Secrets.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = false,
                touchesDatabaseSchema = false,
                touchesPermissionsOrSecrets = true,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `объём выше порога в пять раз — risky`() {
        assertEquals(RiskLevel.RISKY, evaluate(listOf(file("src/auth/Big.kt", added = 251))))
    }

    @Test
    fun `объём ровно в пять раз выше порога — ещё normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/auth/Big.kt", added = 250))))
    }

    // ——— normal ———

    @Test
    fun `обычная правка исходника ниже порога — normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/auth/Login.kt", added = 10))))
    }

    @Test
    fun `тесты не запускались — normal, а не safe`() {
        val risk = evaluate(
            files = listOf(file("docs/auth.md")),
            tests = TestStatus(state = TestState.NOT_RUN),
        )
        assertEquals(RiskLevel.NORMAL, risk)
    }

    // ——— инвариант 5: уровень пакета не ниже максимального уровня его hunk'ов ———

    @Test
    fun `рискованный hunk поднимает безопасный пакет до risky`() {
        assertEquals(RiskLevel.RISKY, evaluate(listOf(file("docs/auth.md", risk = RiskLevel.RISKY))))
    }

    @Test
    fun `hunk уровня normal поднимает безопасный пакет до normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("docs/auth.md", risk = RiskLevel.NORMAL))))
    }

    // ——— детерминированность и композиция ———

    @Test
    fun `вычисление детерминировано`() {
        val files = listOf(file("src/auth/Login.kt"), file("docs/auth.md"))
        assertEquals(evaluate(files), evaluate(files))
    }

    @Test
    fun `риск пакета равен максимуму по входам и не ниже него`() {
        val risk = evaluate(listOf(file("src/auth/Legacy.kt", kind = FileChangeKind.DELETED), file("docs/a.md")))
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `порог по умолчанию равен пятидесяти`() {
        assertEquals(50, RiskEvaluator.DEFAULT_VOLUME_THRESHOLD_LINES)
    }
}
