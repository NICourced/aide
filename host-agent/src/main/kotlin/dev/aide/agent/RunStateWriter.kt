package dev.aide.agent

import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
import dev.aide.domain.SnapshotRef
import dev.aide.domain.StepStatus
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant

/**
 * Запись состояния прогона и статуса задачи (T-1.1).
 *
 * Здесь собран порядок «сначала база, потом событие» (О-8) для обоих видов переходов:
 * замок обнимает только чтение-изменение-сохранение, рассылка идёт уже без него — иначе
 * медленный клиент держал бы состояние прогона. Отдельный класс, потому что переходов
 * много, а движок должен остаться читаемым.
 */
internal class RunStateWriter(
    private val runs: RunRepository,
    private val tasks: TaskRepository,
    private val events: AgentEventSink,
    private val clock: () -> Instant,
    private val lock: Mutex,
) {

    /** Сохраняет прогон и, если нужно, сообщает о смене состояния. */
    suspend fun persist(run: AgentRun, emit: Boolean = true): AgentRun {
        lock.withLock { runs.save(run) }
        if (emit) events.runStateChanged(run)
        return run
    }

    /**
     * Накапливает стоимость и время одного вызова модели.
     *
     * Отдельно от [recordStep], потому что вызовов на шаг теперь больше одного (T-1.7):
     * запись после каждого вызова не даёт потерять потраченное, если следующий вызов
     * откажет. Событие не рассылается — состояние прогона от стоимости не меняется (§ 8.4).
     */
    suspend fun accumulate(run: AgentRun, response: LlmResponse.Text): AgentRun = persist(
        run.copy(
            cost = run.cost + response.cost,
            elapsedMillis = run.elapsedMillis + response.elapsedMillis,
        ),
        emit = false,
    )

    /**
     * Отмечает шаг выполненным.
     *
     * Смена состояния прогона при этом не происходит, поэтому событие не рассылается:
     * событие — про состояние прогона, а не про каждый шаг (§ 8.4). Стоимость и время
     * вызовов шага уже накоплены [accumulate]: вызовов внутри шага теперь много (T-1.7),
     * и складывать их здесь значило бы либо терять промежуточные, либо считать дважды.
     */
    suspend fun recordStep(run: AgentRun, step: PlanStep): AgentRun {
        val plan = run.plan.map { if (it.index == step.index) it.copy(status = StepStatus.DONE) else it }
        return persist(run.copy(plan = plan), emit = false)
    }

    /** Записывает задачу и сообщает о её новом статусе — тот же порядок, что у прогона. */
    suspend fun persistTask(task: Task): Task {
        lock.withLock { tasks.save(task) }
        events.taskStateChanged(task)
        return task
    }

    /**
     * Дописывает точку отката из результата изменяющего вызова в прогон (T-1.8).
     *
     * Ссылку приносит точка вызова в [dev.aide.tools.ToolResult], а записывает её
     * по-прежнему единственный писатель состояния прогона (О-8): иначе защита снапшотов
     * незакрытой задачи (T-1.19) не увидела бы снапшот, поставленный перед записью.
     * Повторная ссылка не пишется — одна точка отката на изменение HEAD (решение 4).
     * Событие не рассылается: снапшот состояния прогона не меняет.
     */
    suspend fun snapshot(run: AgentRun, ref: SnapshotRef?): AgentRun {
        val addition = ref?.takeIf { it !in run.snapshots } ?: return run
        return persist(run.copy(snapshots = run.snapshots + addition), emit = false)
    }

    /** Завершает прогон успешно; задача уходит в очередь ревью. */
    suspend fun finish(run: AgentRun): AgentRun {
        completeTask(run.taskId)
        return persist(run.copy(state = RunState.FINISHED, finishedAt = clock()))
    }

    /** Завершает прогон ошибкой; причина сохраняется кодом и на прогоне, и на задаче. */
    suspend fun fail(run: AgentRun, reason: String): AgentRun {
        failTask(run.taskId, reason)
        return persist(run.copy(state = RunState.FAILED, finishedAt = clock(), interruptReason = reason))
    }

    /** Останавливает прогон по команде пользователя; это не ошибка. */
    suspend fun stop(run: AgentRun): AgentRun {
        failTask(run.taskId, RunInterruptReason.USER_STOP)
        return persist(
            run.copy(
                state = RunState.STOPPED,
                finishedAt = clock(),
                interruptReason = RunInterruptReason.USER_STOP,
            ),
        )
    }

    /** Задача дошла до конца прогона и ждёт ревью. */
    suspend fun completeTask(taskId: TaskId) {
        tasks.load(taskId)?.let { persistTask(it.copy(status = TaskStatus.REVIEW, failureReason = null)) }
    }

    /** Прогон не довёл задачу до конца: ошибка, стоп или прерывание. */
    suspend fun failTask(taskId: TaskId, reason: String) {
        tasks.load(taskId)?.let { persistTask(it.copy(status = TaskStatus.FAILED, failureReason = reason)) }
    }
}

/** Сумма стоимостей: неполный итог остаётся неполным (FR-COST-5). */
private operator fun Cost.plus(other: Cost): Cost =
    Cost(amountMicros = amountMicros + other.amountMicros, known = known && other.known)
