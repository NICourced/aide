package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskBranches
import dev.aide.agent.ports.TaskStash
import dev.aide.agent.ports.WorkStash
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunCommand
import dev.aide.domain.RunState
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.59 в движке: правки человека откладываются до ветки задачи и возвращаются после
 * прогона — любым исходом, включая остановку; конфликт возврата не теряет отложенное.
 *
 * Git здесь не участвует: проверяется порядок и состояние задачи, а сам репозиторий —
 * тестами `JGitRepository` и `WorkStashGuard`. Модель скриптованная (О-11).
 */
class AgentRunEngineWorkStashTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val branches = FakeTaskBranches()
    private val stash = FakeWorkStash()
    private val tools = stepTools()

    /** Порядок обращений: правки обязаны откладываться до ветки задачи. */
    private val order = mutableListOf<String>()

    private fun engine(llm: LlmClient = textModel()): AgentRunEngine =
        AgentRunEngine(
            ports = RunPorts(runs, tasks, sink),
            models = fixedModel(llm),
            planner = RunPlanner { _, _ -> plan("шаг") },
            tools = tools,
            repositories = RepositoryPorts(
                branches = TaskBranches { branch ->
                    order += BRANCH
                    branches.ensure(branch)
                },
                snapshots = noSnapshots,
                workStash = object : WorkStash {
                    override suspend fun stash(taskId: TaskId): TaskStash {
                        order += STASH
                        return stash.stash(taskId)
                    }

                    override suspend fun restore(ref: String, branch: String?): StashReturn =
                        stash.restore(ref, branch)
                },
            ),
            clock = { Instant.fromEpochMilliseconds(1_000) },
        )

    private suspend fun runTask(engine: AgentRunEngine) {
        engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNext(), "задача обязана быть обработана")
    }

    /** Возвраты, которых потребовал движок; пара «ссылка — ветка». */
    private fun assertRestored(vararg expected: Pair<String, String?>) {
        assertEquals(expected.toList(), stash.restored)
    }

    @Test
    fun `правки откладываются до ветки задачи`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            val engine = engine()

            runTask(engine)

            assertEquals(listOf(STASH, BRANCH), order, "checkout ветки перенёс бы правки в ветку агента")
        }
    }

    @Test
    fun `чистое дерево — откладывать нечего, прогон идёт и ничего не возвращается`() {
        runBlocking {
            val engine = engine()

            runTask(engine)

            val task = tasks.all().single()
            assertNull(task.stashRef, "откладывать было нечего — и ссылки нет")
            assertEquals(TaskStatus.REVIEW, task.status)
            assertTrue(stash.restored.isEmpty(), "возвращать нечего: правок не было")
        }
    }

    @Test
    fun `отказ отложить правки — задача падает кодом, ветка не ставится`() {
        runBlocking {
            stash.answerStash(TaskStash.Refused(RunInterruptReason.STASH_FAILED))
            val engine = engine()

            runTask(engine)

            val task = tasks.all().single()
            assertEquals(TaskStatus.FAILED, task.status)
            assertEquals(RunInterruptReason.STASH_FAILED, task.failureReason)
            assertTrue(branches.asked.isEmpty(), "без чистого дерева в ветку задачи не переключаются")
            assertTrue(runs.all().isEmpty(), "прогона нет: он начинается только с чистого дерева")
        }
    }

    @Test
    fun `после успешного прогона правки возвращаются на свою ветку, а ссылка очищается`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            val engine = engine()

            runTask(engine)

            assertRestored(STASH_REF to "master")
            val task = tasks.all().single()
            assertNull(task.stashRef, "после успешного возврата задача больше не держит ссылку")
            assertNull(task.stashBranch)
            assertEquals(TaskStatus.REVIEW, task.status, "возврат правок не меняет исход прогона")
            assertEquals(RunState.FINISHED, runs.all().single().state)
        }
    }

    @Test
    fun `после остановки пользователем правки возвращаются так же`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            val gate = CompletableDeferred<Unit>()
            val llm = ScriptedLlmClient(listOf(text())) { gate.await() }
            val engine = engine(llm)
            engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)

            val worker = launch { engine.processNext() }
            llm.awaitCall(0)
            engine.control(sink.events.first().id, RunCommand.STOP)
            worker.join()

            assertRestored(STASH_REF to "master")
            assertEquals(RunState.STOPPED, runs.all().single().state)
            assertNull(tasks.all().single().stashRef)
        }
    }

    @Test
    fun `после падения прогона правки возвращаются`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            val llm = ScriptedLlmClient(listOf(LlmResponse.Error(LlmErrorKind.UNAUTHORIZED)))
            val engine = engine(llm)

            runTask(engine)

            assertRestored(STASH_REF to "master")
            assertEquals(RunState.FAILED, runs.all().single().state)
            assertNull(tasks.all().single().stashRef)
        }
    }

    @Test
    fun `конфликт возврата — код на задаче, а отложенное сохранено`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            stash.answerRestore(StashReturn.Conflict(RunInterruptReason.STASH_CONFLICT))
            val engine = engine()

            runTask(engine)

            val task = tasks.all().single()
            assertEquals(TaskStatus.FAILED, task.status, "конфликт обязан быть виден пользователю")
            assertEquals(RunInterruptReason.STASH_CONFLICT, task.failureReason)
            assertEquals(STASH_REF, task.stashRef, "отложенное при конфликте не теряется")
            assertEquals("master", task.stashBranch)
        }
    }

    @Test
    fun `неудачный возврат — код на задаче, а отложенное сохранено`() {
        runBlocking {
            stash.answerStash(TaskStash.Stashed(STASH_REF, "master"))
            stash.answerRestore(StashReturn.Refused(RunInterruptReason.STASH_RETURN_FAILED))
            val engine = engine()

            runTask(engine)

            val task = tasks.all().single()
            assertEquals(RunInterruptReason.STASH_RETURN_FAILED, task.failureReason)
            assertEquals(STASH_REF, task.stashRef, "не вернулось — значит, всё ещё ждёт возврата")
        }
    }

    @Test
    fun `возврат не переключает ветку вслепую, когда её имени нет`() {
        runBlocking {
            // Отсоединённый HEAD: имени ветки нет, и выдумывать его нельзя. Ссылка при этом
            // обязана доехать до возврата той же самой.
            stash.answerStash(TaskStash.Stashed(STASH_REF, null))
            val engine = engine()

            runTask(engine)

            assertRestored(STASH_REF to null)
            assertNull(tasks.all().single().stashRef)
        }
    }

    private companion object {
        /** Ссылка на отложенные правки: движку её значение не важно, важно, что она доехала. */
        const val STASH_REF = "refs/ai/stash/t-1"

        /** Отметки порядка в [order]: правки обязаны откладываться раньше ветки. */
        const val STASH = "правки"
        const val BRANCH = "ветка"
    }
}
