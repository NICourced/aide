package dev.aide.agent

import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.RunState
import dev.aide.domain.TaskStatus
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Почему прогон прекратился; код, а не текст, — понятную строку строит UI (NFR-13). */
object RunInterruptReason {

    /** Хост был перезапущен: прогон не продолжается с середины молча (T-1.1). */
    const val HOST_RESTART: String = "host_restart"

    /** Прогон остановил пользователь. */
    const val USER_STOP: String = "user_stop"

    /** Ответ модели не разобран в план. */
    const val PLAN_UNREADABLE: String = "plan_unreadable"

    /** Непредвиденная ошибка клиента модели: сеть, разбор, отказ транспорта. */
    const val UNEXPECTED: String = "unexpected"

    /**
     * Шаг исчерпал предел вызовов инструментов (T-1.7).
     *
     * Отдельный код, а не `unexpected`: это отказ неисправной модели, которая
     * зациклилась на вызовах, и пользователю по коду понятно, что дело в поведении
     * модели, а не в сбое хоста.
     */
    const val TOOL_LOOP_LIMIT: String = "tool_loop_limit"

    /**
     * Репозиторий не открыт: задачу некуда поставить — переключать нечего (T-1.18).
     *
     * Отдельный код, а не `unexpected`: прогон без репозитория не сделал бы ничего
     * полезного (инструменты читают только открытый воркспейс), и тишина вместо отказа
     * показывала бы пользователю успех пустого прогона.
     */
    const val NO_WORKSPACE: String = "no_workspace"

    /** У репозитория нет коммитов: ветку задачи не от чего ответвлять (T-1.18). */
    const val REPOSITORY_EMPTY: String = "repository_empty"

    /** HEAD отсоединён: непонятно, какая ветка базовая, и угадывать её нельзя (T-1.18). */
    const val HEAD_DETACHED: String = "head_detached"

    /** Репозиторий только для чтения: ссылку на ветку записать некуда (T-1.18). */
    const val REPOSITORY_READ_ONLY: String = "repository_read_only"

    /** Git не смог создать ветку или переключиться в неё: правки мешают или ссылка не пишется. */
    const val BRANCH_FAILED: String = "branch_failed"
}

/**
 * Восстановление после падения хоста (T-1.1).
 *
 * При старте хоста незавершённые прогоны помечаются [RunState.INTERRUPTED], а их
 * задачи — [TaskStatus.FAILED]: молчаливое продолжение прерванного прогона запрещено,
 * пользователь мог править репозиторий, пока хоста не было. Очередь при старте пуста —
 * работа не возобновляется сама (решение 8).
 */
class InterruptedRuns(
    private val runs: RunRepository,
    private val tasks: TaskRepository,
    private val clock: () -> Instant = Clock.System::now,
) {

    /** Помечает прерванными ровно незавершённые прогоны; возвращает их для лога. */
    fun markInterrupted(): List<AgentRun> {
        val interrupted = runs.unfinished().map(::interrupt)
        tasks.unfinished().forEach {
            tasks.save(it.copy(status = TaskStatus.FAILED, failureReason = RunInterruptReason.HOST_RESTART))
        }
        return interrupted
    }

    private fun interrupt(run: AgentRun): AgentRun = run.copy(
        state = RunState.INTERRUPTED,
        finishedAt = clock(),
        interruptReason = RunInterruptReason.HOST_RESTART,
    ).also(runs::save)
}
