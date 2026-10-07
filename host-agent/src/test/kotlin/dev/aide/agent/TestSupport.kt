package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.Snapshots
import dev.aide.agent.ports.StashReturn
import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.ports.TaskSnapshot
import dev.aide.agent.ports.TaskStash
import dev.aide.agent.ports.WorkStash
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.agent.provider.ModelUnavailableException
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.PlanDecision
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.yield
import kotlinx.datetime.Instant

/** Прогоны в памяти: тесты движка не зависят ни от базы, ни от сервера. */
class FakeRunRepository : RunRepository {

    private val stored = LinkedHashMap<RunId, AgentRun>()

    override fun save(run: AgentRun) {
        stored[run.id] = run
    }

    override fun load(id: RunId): AgentRun? = stored[id]

    override fun unfinished(): List<AgentRun> =
        stored.values.filter { it.finishedAt == null }.sortedBy { it.startedAt }

    /** Все сохранённые прогоны в порядке записи — для утверждений теста. */
    fun all(): List<AgentRun> = stored.values.toList()
}

/** Задачи в памяти; порядок очереди — по времени постановки, как в хранилище хоста. */
class FakeTaskRepository : TaskRepository {

    private val stored = LinkedHashMap<TaskId, Task>()

    override fun save(task: Task) {
        stored[task.id] = task
    }

    override fun load(id: TaskId): Task? = stored[id]

    override fun unfinished(): List<Task> =
        stored.values
            .filter { it.status != TaskStatus.REVIEW && it.status != TaskStatus.ACCEPTED &&
                it.status != TaskStatus.REJECTED && it.status != TaskStatus.FAILED }
            .sortedBy { it.createdAt }

    /** Все сохранённые задачи в порядке записи — для утверждений теста. */
    fun all(): List<Task> = stored.values.toList()
}

/**
 * События прогонов и задач вместе со снимком записи в базе на момент рассылки.
 *
 * Снимок нужен, чтобы доказать порядок «сначала запись, потом событие» (О-8):
 * в момент рассылки в хранилище уже обязано лежать то же состояние. Точка
 * приостановки ([yield]) делает это наблюдаемым — без неё запись в отменённой
 * корутине всё равно бы прошла, и отсутствие `NonCancellable` не отличалось бы.
 */
class RecordingEventSink(
    private val runs: RunRepository,
    private val tasks: TaskRepository,
) : AgentEventSink {

    val events = mutableListOf<AgentRun>()
    val storedAtEmit = mutableListOf<AgentRun?>()
    val taskEvents = mutableListOf<Task>()
    val taskStoredAtEmit = mutableListOf<Task?>()

    override suspend fun runStateChanged(run: AgentRun) {
        yield()
        events += run
        storedAtEmit += runs.load(run.id)
    }

    override suspend fun taskStateChanged(task: Task) {
        yield()
        taskEvents += task
        taskStoredAtEmit += tasks.load(task.id)
    }

    /** Состояния прогонов в порядке рассылки — по ним проверяется последовательность. */
    val states: List<RunState> get() = events.map { it.state }

    /** Статусы задач в порядке рассылки. */
    val taskStates: List<TaskStatus> get() = taskEvents.map { it.status }
}

/**
 * Приёмник, у которого падает рассылка терминального события прогона.
 *
 * Проверяет, что сбой доставки не меняет исход: запись уже сделана, и успешный прогон
 * не должен превратиться в отказ.
 */
class ThrowingOnFinishSink(
    runs: RunRepository,
    tasks: TaskRepository,
) : AgentEventSink {

    private val delegate = RecordingEventSink(runs, tasks)

    override suspend fun runStateChanged(run: AgentRun) {
        if (run.state == RunState.FINISHED) error("рассылка терминального события не удалась")
        delegate.runStateChanged(run)
    }

    override suspend fun taskStateChanged(task: Task) {
        delegate.taskStateChanged(task)
    }
}

/**
 * Ветка задачи в тестах (T-1.18): по умолчанию «ветка уже была», а чем она обеспечена
 * и спрашивали ли её вообще — решает тест.
 */
class FakeTaskBranches(private var outcome: TaskBranch = TaskBranch.Existing) : TaskBranches {

    /** Ветки, о которых движок спрашивал; по списку проверяется и то, что спросил. */
    val asked = mutableListOf<String>()

    /** Меняет ответ порта: так проверяются отказы репозитория. */
    fun answer(outcome: TaskBranch) {
        this.outcome = outcome
    }

    override suspend fun ensure(branch: String): TaskBranch {
        asked += branch
        return outcome
    }
}

/** Порт ветки, отвечающий «ветка уже была»: тестам без T-1.18 важно лишь, что прогон не отказал. */
val branchAlreadyExists: TaskBranches = TaskBranches { TaskBranch.Existing }

/** Порт снапшотов, отвечающий «коммитов нет»: тестам без T-1.19 важно лишь, что прогон идёт без снапшота. */
val noSnapshots: Snapshots = Snapshots { TaskSnapshot.NoHead }

/** Порт отложенных правок, отвечающий «откладывать нечего»: тестам без T-1.59 важен лишь ход прогона. */
val noWorkStash: WorkStash = object : WorkStash {
    override suspend fun stash(taskId: TaskId): TaskStash = TaskStash.Nothing

    override suspend fun restore(ref: String, branch: String?): StashReturn = StashReturn.Returned
}

/**
 * Порт отложенных правок в тестах (T-1.59): запоминает, что и когда у него спросили.
 *
 * По умолчанию «откладывать нечего» и «правки вернулись»: так прогон идёт как раньше,
 * а конкретный исход задаёт тест.
 */
class FakeWorkStash(
    private var stashOutcome: TaskStash = TaskStash.Nothing,
    private var restoreOutcome: StashReturn = StashReturn.Returned,
) : WorkStash {

    /** Задачи, правки которых просили отложить, в порядке обращений. */
    val stashed = mutableListOf<TaskId>()

    /** Ссылки и ветки, которые просили вернуть, в порядке обращений. */
    val restored = mutableListOf<Pair<String, String?>>()

    fun answerStash(outcome: TaskStash) {
        stashOutcome = outcome
    }

    fun answerRestore(outcome: StashReturn) {
        restoreOutcome = outcome
    }

    override suspend fun stash(taskId: TaskId): TaskStash {
        stashed += taskId
        return stashOutcome
    }

    override suspend fun restore(ref: String, branch: String?): StashReturn {
        restored += ref to branch
        return restoreOutcome
    }
}

/** Прогон с заданным состоянием; незавершённые — без `finishedAt`. */
fun testRun(
    id: String,
    state: RunState,
    finishedAt: Instant? = null,
    plan: List<PlanStep> = emptyList(),
    mode: AutonomyMode = AutonomyMode.ASK_BEFORE_CHANGES,
): AgentRun = AgentRun(
    id = RunId(id),
    taskId = TaskId("t-$id"),
    state = state,
    mode = mode,
    plan = plan,
    startedAt = Instant.fromEpochMilliseconds(1),
    finishedAt = finishedAt,
)

/**
 * Источник модели с фиксированным клиентом: так тесты движка подставляют скриптованную
 * модель вместо реестра провайдеров (T-1.56, О-11). Алиас виден в `AgentRun.modelAlias`,
 * и по нему проверяется, что модель записана на прогон.
 */
fun fixedModel(llm: LlmClient, alias: String = "test/scripted"): ModelProvider =
    ModelProvider { Result.success(ConfiguredModel(alias, llm)) }

/** Источник модели, отвечающий типизированным отказом: так проверяются коды причин. */
fun failingModel(failure: ModelCheckFailure): ModelProvider =
    ModelProvider { Result.failure(ModelUnavailableException(failure)) }

/** Задача с заданным статусом. */
fun testTask(id: String, status: TaskStatus): Task = Task(
    id = TaskId(id),
    title = "Задача $id",
    prompt = "Постановка задачи $id",
    branch = "ai/$id",
    status = status,
    createdAt = Instant.fromEpochMilliseconds(1),
)

/** Код отказа задачи, который ничего не значит: проверяется общий текст UI. */
const val UNKNOWN_FAILURE_REASON: String = "совсем_неизвестная_причина"

/** Сколько ждать появления стоящего прогона: короче шага планирования, длиннее переключения корутин. */
private const val PLAN_POLL_MILLIS: Long = 2L

/**
 * Ждёт появления стоящего прогона, ничего не решая.
 *
 * Так проверяется сама стоянка: до решения работа не начинается, и у прогона есть время
 * показать, что он действительно стоит, а не просто ещё не дошёл до работы.
 */
suspend fun awaitPlannedRun(runs: FakeRunRepository, runId: RunId? = null): AgentRun = withTimeout(5_000) {
    var planned = runs.all().lastOrNull { it.state == RunState.PLANNED && (runId == null || it.id == runId) }
    while (planned == null) {
        delay(PLAN_POLL_MILLIS)
        planned = runs.all().lastOrNull { it.state == RunState.PLANNED && (runId == null || it.id == runId) }
    }
    planned
}

/**
 * Ждёт, пока у прогона появится план с ожидаемым первым описанием.
 *
 * Перепланирование записывается асинхронно, поэтому его результат — не то же самое, что
 * отправленное решение: тест ждёт именно записанного плана, а не момента отправки (T-1.2).
 */
suspend fun awaitPlanSummary(runs: FakeRunRepository, runId: RunId, summary: String): AgentRun = withTimeout(5_000) {
    var run = runs.all().firstOrNull { it.id == runId }
    while (run?.plan?.firstOrNull()?.summary != summary) {
        delay(PLAN_POLL_MILLIS)
        run = runs.all().firstOrNull { it.id == runId }
    }
    requireNotNull(run)
}

/**
 * Ждёт появления стоящего плана и отвечает на стоянку заданным решением (T-1.2).
 *
 * Возвращает прогон, которому отправлено решение. Стоянка — часть контракта: без решения
 * работа не начинается, поэтому проверки, которым нужен ход прогона, проходят шлюз явно.
 */
suspend fun AgentRunEngine.awaitShownPlan(
    runs: FakeRunRepository,
    decision: PlanDecision = PlanDecision.Approve,
): AgentRun = awaitPlannedRun(runs).also { decidePlan(it.id, decision) }

/**
 * Выполняет одну задачу очереди, подтверждая показанный план (T-1.2).
 *
 * Прежнее `processNext` без решения теперь останавливается на стоянке; там, где тесту
 * важен ход работы, этот вызов проходит шлюз явно и сохраняет смысл проверки.
 */
suspend fun AgentRunEngine.processNextApproved(
    runs: FakeRunRepository,
    decision: PlanDecision = PlanDecision.Approve,
): Boolean {
    var processed = false
    coroutineScope {
        val worker = launch { processed = processNext() }
        val approver = launch {
            while (isActive) {
                runs.all().lastOrNull { it.state == RunState.PLANNED }?.let { decidePlan(it.id, decision) }
                delay(PLAN_POLL_MILLIS)
            }
        }
        awaitCompletion(worker)
        approver.cancel()
    }
    return processed
}

/**
 * Дожидается корутины с ограничением по времени: регрессия даёт падение, а не зависание.
 *
 * Без ограничения `join()` навсегда оставил бы набор висеть (класс дефектов из AGENTS.md),
 * поэтому по истечении срока корутина отменяется, а тест падает.
 */
suspend fun awaitCompletion(job: Job, timeoutMillis: Long = 5_000) {
    val finished = withTimeoutOrNull(timeoutMillis) {
        job.join()
        true
    }
    if (finished == null) {
        job.cancel()
        throw AssertionError("Корутина не завершилась за $timeoutMillis мс")
    }
}

/** Модель, падающая на первом вызове: проверяет предохранитель движка, а не код ошибки. */
class ThrowingOnceModel : LlmClient {

    private var calls = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        check(calls > 1) { "провайдер упал на первом вызове" }
        return LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }
}
