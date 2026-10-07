package dev.aide.agent

import dev.aide.agent.ports.RunRepository
import dev.aide.agent.ports.TaskRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.RunState
import dev.aide.domain.TaskStatus
import dev.aide.domain.planNeedsApproval
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant

/** Почему прогон прекратился; код, а не текст, — понятную строку строит UI (NFR-13). */
object RunInterruptReason {

    /** Хост был перезапущен: прогон не продолжается с середины молча (T-1.1). */
    const val HOST_RESTART: String = "host_restart"

    /**
     * Прогон ждал подтверждения плана, когда хост перезапустился (T-1.2).
     *
     * Отдельный код, а не `host_restart`: возобновить стоянку нечем (воркер берёт только
     * `QUEUED`, а прогон в `PLANNED` — живая корутина, которой после падения нет), но
     * пользователю важно понять, что работа даже не начиналась, а не что она прервалась.
     * План при этом сохраняется в базе.
     */
    const val PLAN_AWAITING_CONFIRMATION: String = "plan_awaiting_confirmation"

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

    /**
     * Git не смог записать ссылку снапшота: каталог недоступен, ссылка не открывается (T-1.19).
     *
     * Отдельный код, а не `unexpected`: без снапшота у прогона нет точки отката (NFR-SAFE-2),
     * и задача обязана упасть с понятной причиной, а не остаться `RUNNING` без объяснения.
     */
    const val SNAPSHOT_FAILED: String = "snapshot_failed"

    /**
     * Незакоммиченные правки пользователя отложить не удалось (T-1.59).
     *
     * Отдельный код, а не `unexpected`: прогон обязан начинаться с чистого дерева, иначе
     * `checkout` ветки задачи перенёс бы правки человека в ветку агента, а коммит шага
     * (T-1.11) записал бы их от его имени (§ 8.3). Лучше явный отказ, чем тихая подмена автора.
     */
    const val STASH_FAILED: String = "stash_failed"

    /**
     * Отложенные правки пользователя вернуть не удалось: git отказал (T-1.59).
     *
     * Правки не потеряны — ссылка остаётся в репозитории и на задаче, — но пользователю
     * об этом нужно сказать: прогон, оставивший их отложенными, для человека выглядит
     * как потеря правок (NFR-SAFE-2).
     */
    const val STASH_RETURN_FAILED: String = "stash_return_failed"

    /**
     * Возврат отложенных правок конфликтует с работой агента (T-1.59).
     *
     * Правки человека сохранены и ждут разбора: тихая потеря здесь дороже неудобства
     * явного конфликта. Никакого drop при конфликте не делается (§ 8.3).
     */
    const val STASH_CONFLICT: String = "stash_conflict"

    /**
     * Коммит шага не создан: git отказал (T-1.11).
     *
     * Отдельный код, а не `unexpected`: сбой коммита прогон не роняет, но обязан быть
     * виден в журнале — расхождение «шаг прошёл, коммита нет» иначе нашлось бы только
     * в истории репозитория, и уже задним числом.
     */
    const val COMMIT_FAILED: String = "commit_failed"

    /**
     * Коммит шага не создан: текущая ветка не та, куда собирались коммитить (T-1.11).
     *
     * Отдельный код: это не сбой git, а сработавшая защита «работа агента не уезжает
     * в чужую ветку», и пользователю по коду понятно, что дело в состоянии репозитория.
     */
    const val COMMIT_WRONG_BRANCH: String = "commit_wrong_branch"
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
        val unfinished = runs.unfinished()
        val interrupted = unfinished.map(::interrupt)
        // Причина на задаче — от её прогона: ждавший плана прогон и работавший
        // прерываются по-разному, и на задаче это тоже обязано быть видно (T-1.2).
        val reasons = unfinished.associate { it.taskId to reasonOf(it) }
        tasks.unfinished().forEach { task ->
            val reason = reasons[task.id] ?: RunInterruptReason.HOST_RESTART
            tasks.save(task.copy(status = TaskStatus.FAILED, failureReason = reason))
        }
        return interrupted
    }

    private fun interrupt(run: AgentRun): AgentRun = run.copy(
        state = RunState.INTERRUPTED,
        finishedAt = clock(),
        interruptReason = reasonOf(run),
    ).also(runs::save)

    /**
     * Ждавший подтверждения плана прогон прерывается отдельным кодом, работавший — общим (T-1.2).
     *
     * Код «ждал плана» ставится только там, где шлюз действительно есть: в режиме без
     * подтверждения (`SUGGEST_ONLY`) `PLANNED` — мгновение внутри прогона, и называть
     * его ожиданием было бы неправдой.
     */
    private fun reasonOf(run: AgentRun): String =
        if (run.state == RunState.PLANNED && planNeedsApproval(run.mode)) {
            RunInterruptReason.PLAN_AWAITING_CONFIRMATION
        } else {
            RunInterruptReason.HOST_RESTART
        }
}
