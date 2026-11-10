package dev.aide.agent

import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ChangeProposal
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.ports.ChangeProposals
import dev.aide.tools.ports.ChangeRisk
import dev.aide.tools.ports.ToolApproval
import java.nio.file.Files
import kotlin.io.path.exists
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/** Режимы автономности на движке, настоящих инструментах и временной ФС (T-1.15). */
class AgentRunEngineAutonomyTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val workspace = Files.createTempDirectory("aide-engine-autonomy").toRealPath()

    @AfterTest
    fun tearDown() {
        workspace.toFile().deleteRecursively()
    }

    private data class AutonomyFixture(
        val risk: ChangeRisk = ChangeRisk { _, _, _, _ -> RiskLevel.NORMAL },
        val proposals: RecordingProposals = RecordingProposals(),
        val snapshots: EngineChangeSnapshots = EngineChangeSnapshots(),
        val stored: (String) -> ToolPermission? = { null },
        val approval: ToolApproval = ToolApproval { _, _ -> error("Подтверждение не ожидалось") },
    )

    private fun engine(
        mode: AutonomyMode,
        llm: ScriptedLlmClient,
        recorder: RecordingToolCalls = RecordingToolCalls(),
        fixture: AutonomyFixture = AutonomyFixture(),
    ): TestEngine {
        val engine = AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(llm),
            planner = RunPlanner { _, _, _ ->
                listOf(PlanStep(index = 0, summary = "записать файл", status = StepStatus.PENDING))
            },
            tools = stepToolsIn(
                workspace = workspace,
                recorder = recorder,
                stored = fixture.stored,
                snapshots = fixture.snapshots,
                options = StepToolOptions(
                    risk = fixture.risk,
                    proposals = fixture.proposals,
                    approval = fixture.approval,
                ),
            ),
            repositories = RepositoryPorts(branchAlreadyExists, noSnapshots, noWorkStash, noRestore),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )
        return TestEngine(engine, mode, recorder, fixture.proposals, fixture.snapshots)
    }

    private inner class TestEngine(
        val engine: AgentRunEngine,
        val mode: AutonomyMode,
        val recorder: RecordingToolCalls,
        val proposals: RecordingProposals,
        val snapshots: EngineChangeSnapshots,
    ) {
        suspend fun runTask() {
            engine.postTask("задача", mode)
            assertTrue(engine.processNextApproved(runs))
        }
    }

    @Test
    fun `SUGGEST_ONLY сохраняет предложение, не меняя файл и не коммитя`() {
        runBlocking {
            val test = engine(AutonomyMode.SUGGEST_ONLY, writeThenDone())
            test.runTask()

            assertEquals(RunState.FINISHED, runs.all().single().state)
            assertFalse(workspace.resolve("src/App.kt").exists(), "файл не должен появиться в режиме предложений")
            assertEquals(ToolOutcome.PROPOSED, test.recorder.calls.first { it.tool == "write_file" }.outcome)
            assertEquals("src/App.kt", test.proposals.saved.single().files.single().path)
            assertTrue(test.recorder.calls.none { it.tool == "commit_step" })
            assertEquals(0, test.snapshots.asked)
        }
    }

    @Test
    fun `режим поставленной задачи переживает пересоздание движка`() {
        runBlocking {
            val fixture = AutonomyFixture()
            val posted = engine(AutonomyMode.SUGGEST_ONLY, writeThenDone(), fixture = fixture)
            posted.engine.postTask("задача", posted.mode)

            val restarted = engine(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, writeThenDone(), fixture = fixture)
            assertTrue(restarted.engine.processNextApproved(runs))

            assertEquals(AutonomyMode.SUGGEST_ONLY, runs.all().single().mode)
            assertEquals(ToolOutcome.PROPOSED, restarted.recorder.calls.first { it.tool == "write_file" }.outcome)
            assertFalse(workspace.resolve("src/App.kt").exists())
            assertEquals(1, fixture.proposals.saved.size)
        }
    }

    @Test
    fun `ASK_BEFORE_CHANGES спрашивает каждую ASK запись после смены настройки`() {
        runBlocking {
            var selectedMode = AutonomyMode.ASK_BEFORE_CHANGES
            var approvals = 0
            val llm = ScriptedLlmClient(
                listOf(
                    LlmResponse.Text(
                        text = "",
                        cost = Cost(amountMicros = 0, known = true),
                        elapsedMillis = 1,
                        toolCalls = listOf(
                            dev.aide.agent.llm.LlmToolCall(
                                id = "call-1",
                                name = "write_file",
                                arguments = """{"path":"src/One.kt","content":"one"}""",
                            ),
                            dev.aide.agent.llm.LlmToolCall(
                                id = "call-2",
                                name = "write_file",
                                arguments = """{"path":"src/Two.kt","content":"two"}""",
                            ),
                        ),
                    ),
                    text("готово"),
                ),
            )
            val test = engine(
                selectedMode,
                llm,
                fixture = AutonomyFixture(
                    stored = { null },
                    approval = ToolApproval { _, _ ->
                        approvals += 1
                        selectedMode = AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS
                        dev.aide.domain.ApprovalDecision.DENY
                    },
                ),
            )

            test.runTask()

            assertEquals(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, selectedMode)
            assertEquals(AutonomyMode.ASK_BEFORE_CHANGES, runs.all().single().mode)
            assertEquals(2, approvals, "смена настройки не должна менять правила начатого прогона")
            assertFalse(workspace.resolve("src/One.kt").exists())
            assertFalse(workspace.resolve("src/Two.kt").exists())
        }
    }

    @Test
    fun `AUTO_APPLY_SAFE применяет SAFE при исходном ASK без подтверждения`() {
        runBlocking {
            val test = engine(
                AutonomyMode.AUTO_APPLY_SAFE,
                writeThenDone(),
                fixture = AutonomyFixture(risk = ChangeRisk { _, _, _, _ -> RiskLevel.SAFE }),
            )
            test.runTask()

            assertEquals(RunState.FINISHED, runs.all().single().state)
            assertTrue(workspace.resolve("src/App.kt").exists(), "SAFE изменение должно быть применено")
            assertEquals(ToolOutcome.SUCCESS, test.recorder.calls.first { it.tool == "write_file" }.outcome)
            assertEquals(1, test.snapshots.asked)
            assertTrue(test.proposals.saved.isEmpty())
        }
    }

    @Test
    fun `AUTO_APPLY_SAFE сохраняет NORMAL предложением вместо вопроса`() {
        runBlocking {
            val test = engine(AutonomyMode.AUTO_APPLY_SAFE, writeThenDone())
            test.runTask()

            assertEquals(RunState.FINISHED, runs.all().single().state)
            assertFalse(workspace.resolve("src/App.kt").exists())
            assertEquals(ToolOutcome.PROPOSED, test.recorder.calls.first { it.tool == "write_file" }.outcome)
            assertEquals(RiskLevel.NORMAL, test.proposals.saved.single().risk)
            assertEquals(0, test.snapshots.asked)
        }
    }

    @Test
    fun `FULL_AUTO при ASK исполняет после снапшота без подтверждения`() {
        runBlocking {
            val test = engine(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, writeThenDone())
            test.runTask()

            assertEquals(RunState.FINISHED, runs.all().single().state)
            assertTrue(workspace.resolve("src/App.kt").exists())
            assertEquals(ToolOutcome.SUCCESS, test.recorder.calls.first { it.tool == "write_file" }.outcome)
            assertEquals(1, test.snapshots.asked)
            assertTrue(runs.all().single().snapshots.map { it.value }.contains(CHANGE_SNAPSHOT_REF))
        }
    }

    private fun writeThenDone(): ScriptedLlmClient = ScriptedLlmClient(
        listOf(
            call("write_file", """{"path":"src/App.kt","content":"fun main() = Unit\n"}"""),
            text("готово"),
        ),
    )

    private fun call(tool: String, arguments: String, id: String = CALL_ID): LlmResponse.Text = LlmResponse.Text(
        text = "",
        cost = Cost(amountMicros = 0, known = true),
        elapsedMillis = 1,
        toolCalls = listOf(dev.aide.agent.llm.LlmToolCall(id = id, name = tool, arguments = arguments)),
    )

    private class RecordingProposals : ChangeProposals {
        val saved: MutableList<ChangeProposal> = mutableListOf()

        override fun save(proposal: ChangeProposal) {
            saved += proposal
        }
    }

    companion object {
        private const val CALL_ID: String = "autonomy-call-1"
    }
}
