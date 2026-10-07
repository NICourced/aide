package dev.aide.agent

import dev.aide.agent.ports.RepositoryPorts
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.domain.AutonomyMode
import dev.aide.domain.PlanStep
import dev.aide.domain.Task
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.Instant

/**
 * T-1.18 в движке: прогон ставится в ветку задачи до планирования, базовая ветка
 * запоминается один раз, а отказ репозитория — отказ задачи, а не прогон в чужой ветке.
 *
 * Git здесь не участвует: проверяется поведение движка, а сам репозиторий — тестами
 * `JGitRepository` и `TaskBranchGuard`. Модель скриптованная (О-11).
 */
class AgentRunEngineBranchTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)
    private val branches = FakeTaskBranches()

    /** Порядок обращений к ветке и к планированию: планирование обязано идти после ветки. */
    private val order = mutableListOf<String>()

    private val tools = stepTools()

    private fun engine(plan: List<PlanStep> = plan("шаг")): AgentRunEngine = AgentRunEngine(
        ports = RunPorts(runs, tasks, sink),
        models = fixedModel(textModel()),
        planner = RunPlanner { _, _, _ ->
            order += PLAN
            plan
        },
        tools = tools,
        repositories = RepositoryPorts(
            branches = TaskBranches { branch ->
                order += BRANCH
                branches.ensure(branch)
            },
            snapshots = noSnapshots,
            workStash = noWorkStash,
        ),
        clock = { Instant.fromEpochMilliseconds(1_000) },
    )

    private suspend fun runTask(engine: AgentRunEngine): Task {
        engine.postTask("Почини сборку", AutonomyMode.ASK_BEFORE_CHANGES)
        assertTrue(engine.processNextApproved(runs), "задача обязана быть обработана")
        return tasks.all().single()
    }

    /** Возвращает задачу в очередь: так проверяется повторный запуск той же задачи. */
    private fun requeue(task: Task) {
        tasks.save(task.copy(status = TaskStatus.QUEUED))
    }

    @Test
    fun `ветка задачи ставится до планирования`() {
        runBlocking {
            // План читает репозиторий, и читать его надо уже в ветке задачи: иначе план
            // оказался бы про другое состояние дерева, чем то, в котором работает агент.
            val engine = engine()

            runTask(engine)

            assertEquals(listOf(BRANCH, PLAN), order)
        }
    }

    @Test
    fun `прогон спрашивает ту же ветку, что записана задаче`() {
        runBlocking {
            val engine = engine()

            val task = runTask(engine)

            assertEquals(listOf(task.branch), branches.asked, "имя ветки берётся у задачи, а не собирается заново")
            assertEquals("ai/", task.branch.take(3), "соглашение о имени ветки задачи: ai/<task-id>")
        }
    }

    @Test
    fun `базовая ветка запоминается при первом запуске`() {
        runBlocking {
            branches.answer(TaskBranch.Created("master"))
            val engine = engine()

            val task = runTask(engine)

            assertEquals("master", task.baseBranch, "от какой ветки ответвились — обязан знать движок")
            assertEquals(TaskStatus.REVIEW, task.status)
        }
    }

    @Test
    fun `повторный запуск продолжает ту же ветку и не переписывает базовую`() {
        runBlocking {
            branches.answer(TaskBranch.Created("master"))
            val engine = engine()
            val first = runTask(engine)

            // Ветка задачи уже есть: порт сообщает, что продолжил её. Базовая ветка при этом
            // не переписывается, даже если порт называет другую: вывести прежнюю потом нечем,
            // а приёмка пакета (T-1.20) вливает работу именно в неё.
            branches.answer(TaskBranch.Existing)
            requeue(first)
            assertTrue(engine.processNextApproved(runs))
            val second = tasks.all().single()

            assertEquals("master", second.baseBranch, "записанная база не переписывается вторым запуском")
            assertEquals(listOf(first.branch, first.branch), branches.asked, "оба запуска идут в одну ветку")
            assertEquals(2, runs.all().size, "повторный запуск — второй прогон той же задачи")
        }
    }

    @Test
    fun `базовая ветка не переписывается и когда ветку создали заново от другой`() {
        runBlocking {
            branches.answer(TaskBranch.Created("master"))
            val engine = engine()
            val first = runTask(engine)

            // Ветку задачи удалили и создали заново: `Created` приходит второй раз и называет
            // другую базу. Ответвление сегодняшнего дня не отменяет того, откуда задача
            // начиналась, поэтому база остаётся прежней.
            branches.answer(TaskBranch.Created("release"))
            requeue(first)
            assertTrue(engine.processNextApproved(runs))

            assertEquals("master", tasks.all().single().baseBranch)
        }
    }

    @Test
    fun `отказ репозитория даёт задаче код причины, а не прогон в чужой ветке`() {
        runBlocking {
            branches.answer(TaskBranch.Refused(RunInterruptReason.REPOSITORY_EMPTY))
            val engine = engine()

            val task = runTask(engine)

            assertEquals(TaskStatus.FAILED, task.status)
            assertEquals(RunInterruptReason.REPOSITORY_EMPTY, task.failureReason, "код причины — на задаче")
            assertEquals(listOf(BRANCH), order, "после отказа ветки планирование не начинается")
            assertTrue(runs.all().isEmpty(), "прогона нет: он создаётся только после плана")
            assertEquals(null, task.baseBranch, "отказ ветки базу не записывает")
        }
    }

    private companion object {
        /** Отметки порядка в [order]: ветка обязана спрашиваться раньше плана. */
        const val BRANCH = "ветка"
        const val PLAN = "план"
    }
}
