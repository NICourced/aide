package dev.aide.protocol

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentRun
import dev.aide.domain.AutonomyMode
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Сообщение, несущее идентификатор запроса (T-1.58).
 *
 * Идентификатор есть почти у каждого сообщения: по нему хост сопоставляет ответ с запросом,
 * а кэш ответов делает повтор идемпотентным (§ 8.4). Отдельный интерфейс, а не поле
 * в каждом сообщении, потому что разбор «несёт ли это сообщение идентификатор» был
 * скопирован в трёх местах (`ClientSession`, `KtorHostConnection`, обработчики) и рос
 * вместе с протоколом: каждое новое сообщение требовало правки трёх таблиц, и одна из
 * них рано или поздно разошлась бы с остальными.
 *
 * Исключения — сообщения без запроса: приветствие клиента и хоста, несовместимость
 * версий (соединение закрывается) и событие хоста (приходит без запроса).
 */
interface RequestIdCarrier {

    /** Идентификатор запроса, на который отвечает это сообщение. */
    val requestId: RequestId
}

/** Идентификатор запроса клиента; null у сообщений без запроса (приветствие). */
val ClientMessage.requestIdOrNull: RequestId? get() = (this as? RequestIdCarrier)?.requestId

/** Идентификатор запроса, на который отвечает хост; null у событий и несовместимости версий. */
val HostMessage.requestIdOrNull: RequestId? get() = (this as? RequestIdCarrier)?.requestId

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
        override val requestId: RequestId,
        /** Абсолютный или относительный путь к каталогу репозитория. */
        val path: String,
    ) : ClientMessage, RequestIdCarrier

    /** Запросить дерево файлов. */
    @Serializable
    @SerialName("fileTree")
    data class FileTree(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage, RequestIdCarrier

    /** Запросить содержимое файла. */
    @Serializable
    @SerialName("fileContent")
    data class FileContent(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
        /** Путь относительно корня воркспейса. */
        val path: String,
    ) : ClientMessage, RequestIdCarrier

    /** Запросить состояние хоста. */
    @Serializable
    @SerialName("hostState")
    data class HostState(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage, RequestIdCarrier

    /** Поставить задачу в очередь на выполнение агентом (T-1.1). */
    @Serializable
    @SerialName("postTask")
    data class PostTask(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Постановка задачи: текст или расшифровка голоса. */
        val prompt: String,
        /** Режим автономности, с которым задача принимается в работу. */
        val mode: AutonomyMode,
    ) : ClientMessage, RequestIdCarrier

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
        override val requestId: RequestId,
    ) : ClientMessage, RequestIdCarrier

    /** Пауза, продолжение или остановка прогона. */
    @Serializable
    @SerialName("runControl")
    data class RunControl(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Прогон, к которому относится команда. */
        val runId: RunId,
        /** Что сделать с прогоном. */
        val command: RunCommand,
    ) : ClientMessage, RequestIdCarrier

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
        override val requestId: RequestId,
    ) : ClientMessage, RequestIdCarrier

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
        override val requestId: RequestId,
        /** Новая конфигурация целиком. */
        val config: AgentConfig,
    ) : ClientMessage, RequestIdCarrier

    /** Проверить доступ к модели: один запрос списка моделей провайдера (T-1.56). */
    @Serializable
    @SerialName("checkModel")
    data class CheckModel(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Алиас модели, доступ к которой проверяется. */
        val alias: String,
    ) : ClientMessage, RequestIdCarrier

    /**
     * Сохранить ключ провайдера в защищённом хранилище хоста (T-1.58).
     *
     * Значение уходит **только** от клиента к хосту: обратного пути нет ни в этом
     * сообщении, ни в ответе, ни в отдельном запросе чтения. Ответ несёт код состояния
     * ([ModelSecretStatus]), и по нему приложение знает «ключ задан» / «не задан» / «взят
     * из окружения» / «хранилище недоступно».
     *
     * Ключ идёт по уже принятому транспорту, и сквозного шифрования канала в этапе 1 нет:
     * поэтому передавать ключ допустимо только по локальной сети (шифрование — этап 5,
     * T-5.8..T-5.11). Это ограничение этапа, а не свойство хранилища.
     *
     * [toString] переопределён намеренно: сообщение, напечатанное в журнал, не должно
     * раскрывать ключ, а `data class` напечатал бы значение поля.
     */
    @Serializable
    @SerialName("setModelSecret")
    data class SetModelSecret(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Провайдер, чей ключ сохраняется. */
        val providerId: String,
        /** Значение ключа; в ответах и журналах не появляется. */
        val value: String,
    ) : ClientMessage, RequestIdCarrier {
        override fun toString(): String =
            "SetModelSecret(requestId=$requestId, providerId=$providerId, value=***)"
    }

    /**
     * Удалить ключ провайдера из защищённого хранилища хоста (T-1.58).
     *
     * Ответ — то же [HostMessage.ModelSecretChanged]: после удаления следующий прогон
     * падает с явной ошибкой «ключ не задан», а не с ошибкой авторизации провайдера.
     */
    @Serializable
    @SerialName("deleteModelSecret")
    data class DeleteModelSecret(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Провайдер, чей ключ удаляется. */
        val providerId: String,
    ) : ClientMessage, RequestIdCarrier

    /** Запросить состояние ключей всех провайдеров конфигурации (T-1.58). */
    @Serializable
    @SerialName("modelSecrets")
    data class ModelSecrets(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
    ) : ClientMessage, RequestIdCarrier

    /**
     * Запросить страницу журнала вызовов прогона (T-1.3, FR-TOOLS-15).
     *
     * Страницы, а не весь журнал: при 500 вызовах полный список не влез бы в кадр и
     * заставил бы клиент строить полтысячи строк. [cursor] = null означает первую
     * (самую новую) страницу; следующая запрашивается по курсору предыдущего ответа.
     * [limit] = null берёт размер по умолчанию, больше максимума хост не отдаёт.
     */
    @Serializable
    @SerialName("toolCalls")
    data class ToolCalls(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Прогон, журнал которого листается. */
        val runId: RunId,
        /** Курсор предыдущей страницы; null — первая страница. */
        val cursor: ToolCallCursor? = null,
        /** Сколько записей просить; null — размер по умолчанию. */
        val limit: Int? = null,
    ) : ClientMessage, RequestIdCarrier

    /**
     * Запросить полное содержимое одного вызова (T-1.3).
     *
     * Отдельный запрос, потому что страница несёт только превью (см. [ToolCallSummary]):
     * показывать мегабайты на каждую строку списка незачем, а раскрывают записи по одной.
     */
    @Serializable
    @SerialName("toolCallDetail")
    data class ToolCallDetail(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Вызов, полное содержимое которого нужно. */
        val callId: ToolCallId,
    ) : ClientMessage, RequestIdCarrier
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
        override val requestId: RequestId,
        /** Идентификатор открытого воркспейса. */
        val workspaceId: WorkspaceId,
    ) : HostMessage, RequestIdCarrier

    /** Дерево файлов. */
    @Serializable
    @SerialName("tree")
    data class Tree(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Полезная нагрузка с деревом. */
        val tree: FileTreePayload,
    ) : HostMessage, RequestIdCarrier

    /** Содержимое файла. */
    @Serializable
    @SerialName("content")
    data class Content(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Полезная нагрузка с содержимым. */
        val content: FileContentPayload,
    ) : HostMessage, RequestIdCarrier

    /** Состояние хоста. */
    @Serializable
    @SerialName("state")
    data class State(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Полезная нагрузка с состоянием. */
        val state: HostStatePayload,
    ) : HostMessage, RequestIdCarrier

    /** Ошибка обработки запроса. */
    @Serializable
    @SerialName("failure")
    data class Failure(
        /** Идентификатор запроса, который не удалось выполнить. */
        override val requestId: RequestId,
        /** Типизированная ошибка. */
        val error: ProtocolError,
    ) : HostMessage, RequestIdCarrier

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
        override val requestId: RequestId,
        /** Идентификатор созданной задачи. */
        val taskId: TaskId,
    ) : HostMessage, RequestIdCarrier

    /** Состояние агента: ответ на [ClientMessage.AgentStatus]. */
    @Serializable
    @SerialName("agentSnapshot")
    data class AgentSnapshot(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Прогоны в порядке запуска. */
        val runs: List<AgentRun>,
        /** Задачи в порядке постановки. */
        val tasks: List<Task>,
    ) : HostMessage, RequestIdCarrier

    /** Команда управления прогоном принята. */
    @Serializable
    @SerialName("runControlled")
    data class RunControlled(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Прогон, к которому отнесена команда. */
        val runId: RunId,
    ) : HostMessage, RequestIdCarrier

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
        override val requestId: RequestId,
        /** Текущая конфигурация моделей хоста. */
        val config: AgentConfig,
        /** Заготовки популярных сервисов. */
        val catalog: List<ProviderCatalogEntry>,
    ) : HostMessage, RequestIdCarrier

    /** Конфигурация моделей сохранена; возвращается то, что записано. */
    @Serializable
    @SerialName("agentConfigSaved")
    data class AgentConfigSaved(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Сохранённая конфигурация. */
        val config: AgentConfig,
    ) : HostMessage, RequestIdCarrier

    /** Результат проверки доступа к модели; `ok` = true только при подтверждённом доступе. */
    @Serializable
    @SerialName("modelCheckResult")
    data class ModelCheckResult(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Доступ подтверждён. */
        val ok: Boolean,
        /** Почему доступ не подтверждён; null при [ok] = true. */
        val failure: ModelCheckFailure? = null,
    ) : HostMessage, RequestIdCarrier

    /**
     * Состояние ключа провайдера после записи или удаления (T-1.58).
     *
     * Несёт код состояния, а не значение: ключ не читается обратно ни протоколом, ни
     * приложением. Отказ хранилища — тоже состояние ([ModelSecretStatus.StoreUnavailable]
     * с причиной), а не ошибка запроса: хост ответил, просто сохранить не смог.
     */
    @Serializable
    @SerialName("modelSecretChanged")
    data class ModelSecretChanged(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Провайдер, к которому относится состояние. */
        val providerId: String,
        /** Ключ задан, взят из окружения, не задан или хранилище недоступно. */
        val status: ModelSecretStatus,
    ) : HostMessage, RequestIdCarrier

    /** Состояние ключей всех провайдеров конфигурации (T-1.58); значений в карте нет. */
    @Serializable
    @SerialName("modelSecretsSnapshot")
    data class ModelSecretsSnapshot(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Состояние по идентификатору провайдера. */
        val statuses: Map<String, ModelSecretStatus>,
    ) : HostMessage, RequestIdCarrier

    /**
     * Страница журнала вызовов прогона: ответ на [ClientMessage.ToolCalls] (T-1.3).
     *
     * [hasMore] приходит от хоста, а не выводится клиентом из размера страницы: на границе
     * («всего ровно лимит») размер ничего не говорит, и клиент показал бы лишнюю кнопку
     * «показать ещё» либо, наоборот, спрятал бы непустой хвост.
     */
    @Serializable
    @SerialName("toolCallPage")
    data class ToolCallPage(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Прогон, к которому относится страница. */
        val runId: RunId,
        /** Записи от новых к старым, не больше запрошенного лимита. */
        val calls: List<ToolCallSummary>,
        /** Курсор для следующей страницы; null, если это конец журнала. */
        val nextCursor: ToolCallCursor? = null,
        /** Есть ли записи старше отданных. */
        val hasMore: Boolean,
    ) : HostMessage, RequestIdCarrier

    /** Полное содержимое одного вызова: ответ на [ClientMessage.ToolCallDetail] (T-1.3). */
    @Serializable
    @SerialName("toolCallContent")
    data class ToolCallContent(
        /** Идентификатор запроса. */
        override val requestId: RequestId,
        /** Запись журнала целиком. */
        val call: ToolCall,
    ) : HostMessage, RequestIdCarrier

    /** Событие без запроса. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Что произошло на хосте. */
        val event: HostEvent,
    ) : HostMessage
}
