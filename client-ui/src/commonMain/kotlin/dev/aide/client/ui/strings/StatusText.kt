package dev.aide.client.ui.strings

import dev.aide.domain.ModelFailureCode
import dev.aide.domain.RunState
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskStatus
import dev.aide.domain.TestState
import org.jetbrains.compose.resources.StringResource

/**
 * Тексты состояний прогона и статусов задачи — из ресурсов, а не из кода (NFR-13).
 *
 * Файл назван по общему предмету: здесь и строка прогона, и строка задачи.
 * Тексты настроек моделей живут рядом, в `ModelText.kt`: у них свой предмет.
 */

/**
 * Текст состояния прогона берётся из ресурсов, а не из кода (NFR-13).
 *
 * Функция обязана покрыть все значения [RunState]: строка состояния показывает и
 * `PAUSED`, и `STOPPED`, даже когда кнопки паузы и стопа появятся отдельной задачей (T-1.4).
 */
fun runStateResource(state: RunState): StringResource = when (state) {
    RunState.PLANNED -> Strings.runStatePlanned
    RunState.RUNNING -> Strings.runStateRunning
    RunState.PAUSED -> Strings.runStatePaused
    RunState.FINISHED -> Strings.runStateFinished
    RunState.FAILED -> Strings.runStateFailed
    RunState.STOPPED -> Strings.runStateStopped
    RunState.INTERRUPTED -> Strings.runStateInterrupted
}

/**
 * Текст состояния шага плана из ресурсов (T-1.2, NFR-13).
 *
 * Список плана показывает состояние каждого шага; функция обязана покрыть все значения
 * [StepStatus], иначе шаг с новым состоянием остался бы без подписи.
 */
fun planStepStatusResource(status: StepStatus): StringResource = when (status) {
    StepStatus.PENDING -> Strings.planStepPending
    StepStatus.DONE -> Strings.planStepDone
    StepStatus.FAILED -> Strings.planStepFailed
    StepStatus.SKIPPED -> Strings.planStepSkipped
}

/**
 * Текст статуса задачи из ресурсов (NFR-13).
 *
 * Статус задачи показывается отдельно от состояния прогона: задача бывает `QUEUED`
 * (модель ещё думает) и `FAILED` до появления прогона, и пользователь обязан это видеть.
 */
fun taskStatusResource(status: TaskStatus): StringResource = when (status) {
    TaskStatus.QUEUED -> Strings.taskStatusQueued
    TaskStatus.RUNNING -> Strings.taskStatusRunning
    TaskStatus.REVIEW -> Strings.taskStatusReview
    TaskStatus.ACCEPTED -> Strings.taskStatusAccepted
    TaskStatus.REJECTED -> Strings.taskStatusRejected
    TaskStatus.FAILED -> Strings.taskStatusFailed
}

/**
 * Текст причины отказа задачи по её коду; неизвестный код даёт общий текст.
 *
 * Код приходит от хоста (`NOT_CONFIGURED`, `plan_unreadable`, `host_restart` и т. п.),
 * а строка строится здесь из ресурсов (NFR-13). Новый код без строки не оставит
 * пользователя без объяснения — он получит общий текст.
 */
fun taskFailureResource(reason: String?): StringResource = when (reason) {
    ModelFailureCode.NOT_CONFIGURED -> Strings.taskFailureNotConfigured
    ModelFailureCode.MISSING_KEY -> Strings.taskFailureMissingKey
    ModelFailureCode.UNSUPPORTED -> Strings.taskFailureUnsupported
    ModelFailureCode.UNAUTHORIZED -> Strings.taskFailureUnauthorized
    ModelFailureCode.RATE_LIMITED -> Strings.taskFailureRateLimited
    ModelFailureCode.REQUEST_FAILED -> Strings.taskFailureRequestFailed
    ModelFailureCode.RESPONSE_UNREADABLE -> Strings.taskFailureResponseUnreadable
    else -> codedFailureResource(reason)
}

/**
 * Текст причины по коду-строке, а не по коду провайдера.
 *
 * Отдельная функция, потому что кодов-строк становится больше, и одна большая `when`
 * перешагнула бы порог сложности линтера. Порядок разбора не важен: множества кодов
 * не пересекаются, поэтому проверки идут в произвольном порядке.
 */
private fun codedFailureResource(reason: String?): StringResource = when (reason) {
    PLAN_UNREADABLE -> Strings.taskFailurePlanUnreadable
    PLAN_AWAITING_CONFIRMATION -> Strings.taskFailurePlanAwaitingConfirmation
    USER_STOP -> Strings.taskFailureUserStop
    HOST_RESTART -> Strings.taskFailureHostRestart
    STASH_CONFLICT -> Strings.taskFailureStashConflict
    STASH_RETURN_FAILED -> Strings.taskFailureStashReturnFailed
    STASH_FAILED -> Strings.taskFailureStashFailed
    else -> Strings.taskFailureGeneric
}

private const val PLAN_UNREADABLE = "plan_unreadable"

/** Код «ждал подтверждения плана, когда хост перезапустился» (T-1.2). */
private const val PLAN_AWAITING_CONFIRMATION = "plan_awaiting_confirmation"
private const val USER_STOP = "user_stop"
private const val HOST_RESTART = "host_restart"
private const val STASH_CONFLICT = "stash_conflict"
private const val STASH_RETURN_FAILED = "stash_return_failed"
private const val STASH_FAILED = "stash_failed"

/**
 * Текст строки статуса тестов из ресурсов (NFR-13).
 *
 * Строка одна и на экране агента, и (позже) в карточке пакета: состояния тестов — это
 * тот же словарь кодов, и второй перевод разошёлся бы с первым. Отдельного текста для
 * «отчёта ещё нет» заводится значение [TestState.NOT_RUN] — так отсутствие прогона
 * показывается явно, а не пустотой.
 */
fun testStateResource(state: TestState): StringResource = when (state) {
    TestState.NOT_RUN -> Strings.testStatusNone
    TestState.GREEN -> Strings.testStatusGreen
    TestState.RED -> Strings.testStatusRed
    TestState.TIMEOUT -> Strings.testStatusTimeout
    TestState.INFRA_ERROR -> Strings.testStatusInfraError
    TestState.SKIPPED -> Strings.testStatusSkipped
}
