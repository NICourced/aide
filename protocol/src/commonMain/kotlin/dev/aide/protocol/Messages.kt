package dev.aide.protocol

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ProviderCatalogEntry
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

    /**
     * Запросить конфигурацию моделей и каталог заготовок (T-1.56).
     *
     * Имя с `Request` — не стилистика: класс `AgentConfig` живёт в домене, и вложенный
     * класс с тем же именем внутри `ClientMessage` перекрыл бы его в собственной области
     * видимости, а сохранение конфигурации передаёт именно доменный тип.
     */
    @Serializable
    @SerialName("agentConfigRequest")
    data class AgentConfigRequest(
        /** Идентификатор запроса. */
        val requestId: RequestId,
    ) : ClientMessage

    /**
     * Сохранить конфигурацию моделей (T-1.56).
     *
     * Уезжает целиком, а не изменениями: конфигурация — маленький документ, который
     * пользователь правит руками, и «поле №3 в провайдере №2» по проводу читалось бы
     * хуже, чем целиком. Ключа в ней нет и быть не может — только имена переменных.
     */
    @Serializable
    @SerialName("saveAgentConfig")
    data class SaveAgentConfig(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Новая конфигурация целиком. */
        val config: AgentConfig,
    ) : ClientMessage

    /** Проверить доступ к модели: один запрос списка моделей провайдера (T-1.56). */
    @Serializable
    @SerialName("checkModel")
    data class CheckModel(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Алиас модели, доступ к которой проверяется. */
        val alias: String,
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

    /**
     * Конфигурация моделей и каталог заготовок (T-1.56).
     *
     * Заготовки едут вместе с конфигурацией: пользователю нужен список того, что можно
     * добавить, а каталог живёт на хосте (данные, а не код). Каталог — не конфигурация,
     * и в файл настроек он не попадает.
     */
    @Serializable
    @SerialName("agentConfigSnapshot")
    data class AgentConfigSnapshot(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Текущая конфигурация моделей хоста. */
        val config: AgentConfig,
        /** Заготовки популярных сервисов. */
        val catalog: List<ProviderCatalogEntry>,
    ) : HostMessage

    /** Конфигурация моделей сохранена; возвращается то, что записано. */
    @Serializable
    @SerialName("agentConfigSaved")
    data class AgentConfigSaved(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Сохранённая конфигурация. */
        val config: AgentConfig,
    ) : HostMessage

    /** Результат проверки доступа к модели; `ok` = true только при подтверждённом доступе. */
    @Serializable
    @SerialName("modelCheckResult")
    data class ModelCheckResult(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Доступ подтверждён. */
        val ok: Boolean,
        /** Почему доступ не подтверждён; null при [ok] = true. */
        val failure: ModelCheckFailure? = null,
    ) : HostMessage

    /** Событие без запроса. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Что произошло на хосте. */
        val event: HostEvent,
    ) : HostMessage
}
