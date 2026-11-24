package dev.aide.protocol

import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelSecretRejection
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ошибка обработки запроса. Ошибки типизированы, чтобы клиент показывал осмысленное
 * состояние экрана (§ 6.1), а не общее «что-то пошло не так».
 */
@Serializable
sealed interface ProtocolError {

    /** Попытка выйти за пределы воркспейса или прочитать то, что запрещено (§ 10.1). */
    @Serializable
    @SerialName("accessDenied")
    data class AccessDenied(
        /** Путь, к которому пытались обратиться; в UI показывается как есть. */
        val path: String,
        /** Почему отказано: вне корня воркспейса, симлинк наружу, нет прав на файл. */
        val reason: String,
    ) : ProtocolError

    /** Запрошенного объекта нет. */
    @Serializable
    @SerialName("notFound")
    data class NotFound(
        /** Что именно не найдено: путь, воркспейс, файл. */
        val what: String,
    ) : ProtocolError

    /** Каталог существует, но не является git-репозиторием. */
    @Serializable
    @SerialName("notAGitRepository")
    data class NotAGitRepository(
        /** Путь, который открывали. */
        val path: String,
    ) : ProtocolError

    /** Воркспейс был открыт, но уже закрыт. */
    @Serializable
    @SerialName("workspaceClosed")
    data class WorkspaceClosed(
        /** Идентификатор закрытого воркспейса. */
        val workspaceId: WorkspaceId,
    ) : ProtocolError

    /** Возможность появится в следующих этапах. */
    @Serializable
    @SerialName("notImplemented")
    data class NotImplemented(
        /** Что именно ещё не реализовано. */
        val what: String,
    ) : ProtocolError

    /**
     * Сохраняемую конфигурацию моделей хост не принял (T-1.56).
     *
     * Отдельный вариант, а не `Internal`: это не сбой, а отказ по существу, и причину
     * пользователь обязан увидеть словами — «повтор алиаса», «пустой адрес» (NFR-13).
     */
    @Serializable
    @SerialName("invalidAgentConfig")
    data class InvalidAgentConfig(
        /** Что именно не так с конфигурацией. */
        val rejection: AgentConfigRejection,
    ) : ProtocolError

    /**
     * Ключ провайдера не принят по существу (T-1.58).
     *
     * Отдельный вариант, а не [Internal]: пустое значение и неизвестный провайдер —
     * это не сбой хоста, и пользователь обязан увидеть причину словами (NFR-13).
     * Отказ хранилища сюда не попадает: он едет состоянием `ModelSecretStatus.StoreUnavailable`,
     * потому что ключ не отвергнут — его просто негде сохранить на этой платформе.
     */
    @Serializable
    @SerialName("invalidModelSecret")
    data class InvalidModelSecret(
        /** Что именно не так с записью ключа. */
        val rejection: ModelSecretRejection,
    ) : ProtocolError

    /**
     * Откат к снапшоту отклонён по существу (T-1.6).
     *
     * Отдельный вариант, а не [Internal]: каждая причина требует своего сообщения
     * в интерфейсе, и пользователь обязан видеть, почему откат невозможен (NFR-13).
     */
    @Serializable
    @SerialName("snapshotRollbackRejected")
    data class SnapshotRollbackRejected(
        /** Почему откат невозможен. */
        val rejection: SnapshotRollbackRejection,
    ) : ProtocolError

    /**
     * Запрошенный диапазон строк некорректен (T-1.23).
     *
     * Отдельный вариант, а не [Internal]: клиент сам строит запрос по известному gap,
     * и ошибка в диапазоне — это либо баг клиента, либо попытка выйти за лимит; в обоих
     * случаях нужен типизированный ответ, а не общее сообщение.
     */
    @Serializable
    @SerialName("invalidDiffRange")
    data class InvalidDiffRange(
        /** Первая строка диапазона, 1-based. */
        val startLine: Int,
        /** Число строк в запросе. */
        val lineCount: Int,
    ) : ProtocolError

    /** Внутренняя ошибка хоста. */
    @Serializable
    @SerialName("internal")
    data class Internal(
        /** Краткое описание для пользователя. */
        val message: String,
        /** Техническая деталь для лога; в UI показывается по запросу (§ 6.1). */
        val detail: String? = null,
    ) : ProtocolError
}

/** Почему откат к снапшоту невозможен (T-1.6). */
@Serializable
enum class SnapshotRollbackRejection {
    /** Снапшот с таким именем не найден или не начинается с `refs/ai/snap/`. */
    UNKNOWN_SNAPSHOT,

    /** Снапшот не является предком текущего HEAD. */
    NOT_IN_CURRENT_BRANCH,

    /** В рабочем дереве или индексе есть незафиксированные изменения. */
    DIRTY_WORKTREE,

    /** Есть незавершённый прогон, к которому привязан снапшот. */
    ACTIVE_RUN,

    /** HEAD изменился с момента предпросмотра. */
    HEAD_CHANGED,

    /** Дерево снапшота совпадает с деревом HEAD — откатывать нечего. */
    NOTHING_TO_REVERT,

    /** Git-операция не удалась по технической причине. */
    GIT_FAILED,
}

/** Почему версии протокола несовместимы. */
@Serializable
enum class IncompatibilityReason {
    /** Клиент старее хоста — пользователю нужно обновить приложение. */
    CLIENT_OUTDATED,

    /** Клиент новее хоста — нужно обновить хост. */
    HOST_OUTDATED,

    /** Приветствие не разобрано. */
    MALFORMED_HELLO,
}
