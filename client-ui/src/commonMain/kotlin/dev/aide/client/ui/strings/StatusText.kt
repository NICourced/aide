package dev.aide.client.ui.strings

import dev.aide.domain.ModelFailureCode
import dev.aide.domain.RunState
import dev.aide.domain.TaskStatus
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
    PLAN_UNREADABLE -> Strings.taskFailurePlanUnreadable
    USER_STOP -> Strings.taskFailureUserStop
    HOST_RESTART -> Strings.taskFailureHostRestart
    else -> Strings.taskFailureGeneric
}

private const val PLAN_UNREADABLE = "plan_unreadable"
private const val USER_STOP = "user_stop"
private const val HOST_RESTART = "host_restart"
