package dev.aide.protocol

import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.TaskId
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

    /** Поставить задачу в очередь на выполнение агентом (T-1.1). */
    @Serializable
    @SerialName("postTask")
    data class PostTask(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Постановка задачи: текст или расшифровка голоса. */
        val prompt: String,
        /** Режим автономности, с которым задача принимается в работу. */
        val mode: AutonomyMode,
    ) : ClientMessage

    /**
     * Запросить состояние агента: прогоны и задачи (T-1.1).
     *
     * Им же подключившийся позже клиент узнаёт текущее состояние — состояние приходит
     * событиями, а не из истории, поэтому один снимок закрывает оба списка.
     */
    @Serializable
    @SerialName("agentStatus")
    data class AgentStatus(
        /** Идентификатор запроса. */
        val requestId: RequestId,
    ) : ClientMessage

    /** Пауза, продолжение или остановка прогона. */
    @Serializable
    @SerialName("runControl")
    data class RunControl(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Прогон, к которому относится команда. */
        val runId: RunId,
        /** Что сделать с прогоном. */
        val command: RunCommand,
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

    /** Задача поставлена в очередь. */
    @Serializable
    @SerialName("taskPosted")
    data class TaskPosted(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Идентификатор созданной задачи. */
        val taskId: TaskId,
    ) : HostMessage

    /** Состояние агента: ответ на [ClientMessage.AgentStatus]. */
    @Serializable
    @SerialName("agentSnapshot")
    data class AgentSnapshot(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Прогоны в порядке запуска. */
        val runs: List<AgentRun>,
        /** Задачи в порядке постановки. */
        val tasks: List<Task>,
    ) : HostMessage

    /** Команда управления прогоном принята. */
    @Serializable
    @SerialName("runControlled")
    data class RunControlled(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Прогон, к которому отнесена команда. */
        val runId: RunId,
    ) : HostMessage

    /** Событие без запроса. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Что произошло на хосте. */
        val event: HostEvent,
    ) : HostMessage
}
