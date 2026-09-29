package dev.aide.domain

import kotlinx.serialization.Serializable

/**
 * Уровень риска изменения. Порядок объявления — от меньшего к большему,
 * поэтому [RiskLevel.ordinal] можно сравнивать: инвариант 5 § 4.1 требует,
 * чтобы риск пакета был не ниже максимума по его hunk'ам.
 */
@Serializable
enum class RiskLevel { SAFE, NORMAL, RISKY }

/** Режим автономности агента (§ 5.3.1). */
@Serializable
enum class AutonomyMode {
    /** «Только предлагать» — агент ничего не применяет сам. */
    SUGGEST_ONLY,

    /** «Спрашивать перед изменениями» — каждое изменяющее действие требует подтверждения. */
    ASK_BEFORE_CHANGES,

    /** «Авто-применять безопасное» — изменения уровня SAFE применяются автоматически. */
    AUTO_APPLY_SAFE,

    /** «Полный автомат с чекпоинтами» — агент работает сам, но ставит снапшот перед каждым шагом. */
    FULL_AUTO_WITH_CHECKPOINTS,
}

/** Разрешение на использование инструмента (§ 10.1). */
@Serializable
enum class Permission {
    /** Выполняется без спроса. */
    ALLOW,

    /** Требует подтверждения пользователя. */
    ASK,

    /** Запрещено. */
    DENY,
}

/** Кто автор изменения. */
@Serializable
enum class ChangeSource { AGENT, HUMAN }

/** Состояние задачи. */
@Serializable
enum class TaskStatus {
    /** Поставлена в очередь, прогон ещё не начат. */
    QUEUED,

    /** Прогон идёт. */
    RUNNING,

    /** Есть пакет, ожидающий ревью. */
    REVIEW,

    /** Пакет принят. */
    ACCEPTED,

    /** Пакет отклонён. */
    REJECTED,

    /** Прогон завершился ошибкой. */
    FAILED,
}

/** Состояние прогона агента (FR-AGENT-7, FR-AGENT-11). */
@Serializable
enum class RunState {
    /** План сформирован, изменяющих действий ещё не было. */
    PLANNED,

    /** Прогон выполняется. */
    RUNNING,

    /** Прогон приостановлен пользователем, состояние сохранено. */
    PAUSED,

    /** Прогон завершён успешно. */
    FINISHED,

    /** Прогон завершился ошибкой. */
    FAILED,

    /** Прогон остановлен пользователем. */
    STOPPED,

    /** Прогон прерван падением хоста; не продолжается с середины молча (T-1.1). */
    INTERRUPTED,
}

/** Состояние шага плана. */
@Serializable
enum class StepStatus { PENDING, DONE, FAILED, SKIPPED }

/** Чем закончился вызов инструмента (FR-AGENT-9). */
@Serializable
enum class ToolOutcome {
    SUCCESS,
    FAILURE,

    /** Пользователь запретил вызов; прогон от этого не ломается (T-1.8). */
    DENIED,

    /** Вызов не уложился в лимит времени. */
    TIMEOUT,
}

/** Что ответил пользователь на запрос подтверждения (FR-CTRL-16). */
@Serializable
enum class ApprovalDecision {
    /** Разрешить только этот вызов; не запоминается (FR-TOOLS-11). */
    ALLOW_ONCE,

    /** Разрешить до конца сессии. */
    ALLOW_SESSION,

    /** Разрешить постоянно. */
    ALLOW_ALWAYS,

    /** Запретить. */
    DENY,
}

/** Что произошло с файлом. */
@Serializable
enum class FileChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

/** Что произошло с блоком строк. */
@Serializable
enum class HunkKind { ADD, REMOVE, REPLACE }

/** Роль строки внутри блока. */
@Serializable
enum class LineKind { CONTEXT, ADDED, REMOVED }

/** Итог прогона тестов и линтера. */
@Serializable
enum class TestState {
    /** Тесты не запускались. */
    NOT_RUN,

    /** Всё зелёное. */
    GREEN,

    /** Есть упавшие тесты. */
    RED,

    /** Прогон не уложился в лимит времени. */
    TIMEOUT,

    /** Инфраструктурная ошибка: не нашёлся раннер, не поднялась БД тестов. */
    INFRA_ERROR,

    /** Тестов в проекте нет. */
    SKIPPED,
}

/** Состояние пакета в очереди ревью. */
@Serializable
enum class PacketStatus { AWAITING_REVIEW, IN_PROGRESS, ACCEPTED, REJECTED }

/** Уровень, к которому относится решение ревью. */
@Serializable
enum class DecisionScope { PACKET, HUNK }

/** Что решил пользователь. */
@Serializable
enum class DecisionValue { ACCEPTED, REJECTED, CHANGES_REQUESTED }

/** Что стало поводом для снапшота; половина «после» нужна для инварианта 3 § 4.1. */
@Serializable
enum class SnapshotTrigger {
    BEFORE_AGENT_STEP,
    AFTER_AGENT_STEP,
    BEFORE_MANUAL_EDIT,
    AFTER_MANUAL_EDIT,
}

/** Платформа клиента, принявшего решение ревью; нужна для продуктовых метрик (§ 2.3). */
@Serializable
enum class ClientPlatform { ANDROID, DESKTOP_LINUX, DESKTOP_WINDOWS, DESKTOP_MACOS, IOS, UNKNOWN }
