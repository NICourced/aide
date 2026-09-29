package dev.aide.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Сообщение клиента хосту. В этом этапе все сообщения клиента — запросы:
 * изменяющих операций нет до этапа 1, но механизм идемпотентности по [RequestId]
 * уже введён и проверяется в задаче 10.
 */
@Serializable
sealed interface ClientMessage {

    /** Приветствие: версия клиента и последняя увиденная последовательность событий. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Версия протокола клиента. */
        val clientVersion: ProtocolVersion,
        /** Последний полученный номер события; 0 при первом подключении. */
        val lastEventSeq: Long = 0,
    ) : ClientMessage

    /** Открыть репозиторий по пути. */
    @Serializable
    @SerialName("openWorkspace")
    data class OpenWorkspace(
        /** Идентификатор запроса для идемпотентности и сопоставления с ответом. */
        val requestId: RequestId,
        /** Абсолютный или относительный путь к каталогу репозитория. */
        val path: String,
    ) : ClientMessage

    /** Запросить дерево файлов. */
    @Serializable
    @SerialName("fileTree")
    data class FileTree(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage

    /** Запросить содержимое файла. */
    @Serializable
    @SerialName("fileContent")
    data class FileContent(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
        /** Путь относительно корня воркспейса. */
        val path: String,
    ) : ClientMessage

    /** Запросить состояние хоста. */
    @Serializable
    @SerialName("hostState")
    data class HostState(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage
}

/** Сообщение хоста клиенту. */
@Serializable
sealed interface HostMessage {

    /** Ответ на приветствие: версия хоста и выданный идентификатор сессии. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Версия протокола хоста. */
        val hostVersion: ProtocolVersion,
        /** Идентификатор сессии; меняется при переподключении. */
        val sessionId: SessionId,
    ) : HostMessage

    /** Открытый воркспейс. */
    @Serializable
    @SerialName("workspaceOpened")
    data class WorkspaceOpened(
        /** Идентификатор запроса, на который это ответ. */
        val requestId: RequestId,
        /** Идентификатор открытого воркспейса. */
        val workspaceId: WorkspaceId,
    ) : HostMessage

    /** Дерево файлов. */
    @Serializable
    @SerialName("tree")
    data class Tree(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с деревом. */
        val tree: FileTreePayload,
    ) : HostMessage

    /** Содержимое файла. */
    @Serializable
    @SerialName("content")
    data class Content(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с содержимым. */
        val content: FileContentPayload,
    ) : HostMessage

    /** Состояние хоста. */
    @Serializable
    @SerialName("state")
    data class State(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с состоянием. */
        val state: HostStatePayload,
    ) : HostMessage

    /** Ошибка обработки запроса. */
    @Serializable
    @SerialName("failure")
    data class Failure(
        /** Идентификатор запроса, который не удалось выполнить. */
        val requestId: RequestId,
        /** Типизированная ошибка. */
        val error: ProtocolError,
    ) : HostMessage

    /** Версии несовместимы; соединение закрывается, UI показывает требование обновления (T-0.9). */
    @Serializable
    @SerialName("incompatible")
    data class Incompatible(
        /** Почему несовместимы. */
        val reason: IncompatibilityReason,
        /** Версия хоста, чтобы клиент мог показать её пользователю. */
        val hostVersion: ProtocolVersion,
    ) : HostMessage

    /** Событие без запроса. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Что произошло на хосте. */
        val event: HostEvent,
    ) : HostMessage
}
