package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskBranch
import dev.aide.agent.ports.TaskBranches
import dev.aide.agent.ports.TaskRepository
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.ModelProvider
import dev.aide.agent.provider.ModelUnavailableException
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
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

/** Прогон с заданным состоянием; незавершённые — без `finishedAt`. */
fun testRun(id: String, state: RunState, finishedAt: Instant? = null): AgentRun = AgentRun(
    id = RunId(id),
    taskId = TaskId("t-$id"),
    state = state,
    mode = AutonomyMode.ASK_BEFORE_CHANGES,
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

/** Модель, падающая на первом вызове: проверяет предохранитель движка, а не код ошибки. */
class ThrowingOnceModel : LlmClient {

    private var calls = 0

    override suspend fun complete(request: LlmRequest): LlmResponse {
        calls += 1
        check(calls > 1) { "провайдер упал на первом вызове" }
        return LlmResponse.Text(text = "готово", cost = Cost(), elapsedMillis = 1)
    }
}
