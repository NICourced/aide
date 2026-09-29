package dev.aide.protocol

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
