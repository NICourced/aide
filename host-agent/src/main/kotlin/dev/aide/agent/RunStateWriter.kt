package dev.aide.agent

import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.Cost
import dev.aide.domain.PlanStep
import dev.aide.domain.RunState
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
     * Отмечает шаг выполненным и накапливает стоимость и время.
     *
     * Смена состояния прогона при этом не происходит, поэтому событие не рассылается:
     * событие — про состояние прогона, а не про каждый шаг (§ 8.4).
     */
    suspend fun recordStep(run: AgentRun, step: PlanStep, response: LlmResponse.Text): AgentRun {
        val plan = run.plan.map { if (it.index == step.index) it.copy(status = StepStatus.DONE) else it }
        val updated = run.copy(
            plan = plan,
            cost = run.cost + response.cost,
            elapsedMillis = run.elapsedMillis + response.elapsedMillis,
        )
        return persist(updated, emit = false)
    }

    /** Записывает задачу и сообщает о её новом статусе — тот же порядок, что у прогона. */
    suspend fun persistTask(task: Task): Task {
        lock.withLock { tasks.save(task) }
        events.taskStateChanged(task)
        return task
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
