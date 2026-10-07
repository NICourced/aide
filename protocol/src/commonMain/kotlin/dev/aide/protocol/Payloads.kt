package dev.aide.protocol

import dev.aide.domain.AgentRun
import dev.aide.domain.ApprovalDecision
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import kotlinx.datetime.Instant
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Дерево файлов воркспейса. Плоский список, а не вложенные узлы: на 20 000 файлах
 *  вложенность упирается в глубину стека и в лимиты CBOR, а отступы UI считает по пути. */
@Serializable
data class FileTreePayload(
    /** Воркспейс, к которому относится дерево. */
    val workspaceId: WorkspaceId,
    /** Абсолютный путь корня; показывается в шапке. */
    val rootPath: String,
    /** Записи, отсортированные по пути; директория отличима по [FileTreeEntry.isDirectory]. */
    val entries: List<FileTreeEntry>,
    /** true, если обход остановлен лимитом и дерево неполное. */
    val truncated: Boolean,
    /** Сколько записей пропущено; 0, если [truncated] = false. */
    val skippedEntries: Int = 0,
)

/** Одна запись дерева. */
@Serializable
data class FileTreeEntry(
    /** Путь относительно корня воркспейса, разделитель — `/`. */
    val path: String,
    /** Директория это или файл. */
    val isDirectory: Boolean,
    /** Размер файла в байтах; null для директорий. */
    val sizeBytes: Long? = null,
)

/** Содержимое файла для просмотра. */
@Serializable
data class FileContentPayload(
    /** Воркспейс, из которого прочитан файл. */
    val workspaceId: WorkspaceId,
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Содержимое в UTF-8. */
    val text: String,
    /** Размер файла в байтах. */
    val sizeBytes: Long,
    /** true, если файл обрезан по лимиту показа. */
    val truncated: Boolean,
    /** Язык для подсветки, определённый по расширению; null, если неизвестен. */
    val language: String? = null,
)

/** Состояние хоста: то, что клиент показывает в шапке (FR-LAYOUT-1). */
@Serializable
data class HostStatePayload(
    /** Воркспейс, состояние которого отдаётся. */
    val workspaceId: WorkspaceId,
    /** Корень воркспейса. */
    val rootPath: String,
    /** Текущая ветка репозитория. */
    val branch: String,
    /** Короткий хеш HEAD; пустая строка, если коммитов нет. */
    val headCommit: String,
    /** Время работы хоста в миллисекундах. */
    val uptimeMillis: Long,
    /** Режим работы хоста — только для диагностических надписей, поведение клиента от него не зависит. */
    val mode: HostMode,
)

/** Режим работы хоста. */
@Serializable
enum class HostMode {
    /** Хост в том же процессе или рядом на той же машине. */
    LOCAL,

    /** Хост на другом устройстве; протокол и поведение клиента те же (§ 3.3). */
    REMOTE,
}

/**
 * Превью аргументов и результата в сводке журнала (T-1.3).
 *
 * Аргументы и результаты вызовов доходят до 64 КиБ каждый (`read_file`, `run_command`),
 * и страница из полусотни полных записей — это мегабайты в кадре, чего мобильному
 * клиенту показывать не нужно. Полное содержимое отдаётся отдельным запросом, когда
 * пользователь раскрывает вызов.
 */
const val TOOL_CALL_PREVIEW_CHARS: Int = 4_096

/**
 * Сводка записи журнала для страницы (T-1.3, FR-AGENT-9).
 *
 * Несёт поля записи и обрезанные превью аргументов и результата вместе с признаком
 * обрезки — по нему видно, что показано не всё, и стоит запросить полное содержимое.
 * Поля совпадают с [ToolCall], поэтому признак «требовал подтверждения» (FR-AGENT-9)
 * виден уже в списке, без раскрытия записи.
 */
@Serializable
data class ToolCallSummary(
    /** Идентификатор вызова — по нему отсекаются дубли, пришедшие и событием, и страницей. */
    val id: ToolCallId,
    /** Прогон, которому принадлежит вызов. */
    val runId: RunId,
    /** Имя инструмента. */
    val tool: String,
    /** Чем закончился вызов. */
    val outcome: ToolOutcome,
    /** Длительность вызова в миллисекундах. */
    val durationMillis: Long,
    /** Стоимость вызова. */
    val cost: Cost,
    /** Требовал ли вызов подтверждения пользователя. */
    val requiredApproval: Boolean,
    /** Что ответил пользователь; null, если подтверждение не требовалось. */
    val approval: ApprovalDecision? = null,
    /** Момент завершения вызова; вместе с [id] задаёт курсор страницы. */
    val at: Instant,
    /** Начало аргументов вызова. */
    val argumentsPreview: String,
    /** true, если аргументы показаны не целиком. */
    val argumentsTruncated: Boolean,
    /** Начало результата; null, если результата нет. */
    val resultPreview: String? = null,
    /** true, если результат показан не целиком. */
    val resultTruncated: Boolean = false,
) {
    companion object {
        /**
         * Строит сводку по полной записи — один раз для страницы и для живого события.
         *
         * Обрезка живёт здесь, а не на хосте и не на клиенте по отдельности: страницу
         * собирает хост, а живое событие приходит полной записью и превращается в сводку
         * уже на клиенте. Два независимых правила обрезки рано или поздно разошлись бы.
         */
        fun of(call: ToolCall): ToolCallSummary = ToolCallSummary(
            id = call.id,
            runId = call.runId,
            tool = call.tool,
            outcome = call.outcome,
            durationMillis = call.durationMillis,
            cost = call.cost,
            requiredApproval = call.requiredApproval,
            approval = call.approval,
            at = call.at,
            argumentsPreview = call.arguments.take(TOOL_CALL_PREVIEW_CHARS),
            argumentsTruncated = call.arguments.length > TOOL_CALL_PREVIEW_CHARS,
            resultPreview = call.result?.take(TOOL_CALL_PREVIEW_CHARS),
            resultTruncated = (call.result?.length ?: 0) > TOOL_CALL_PREVIEW_CHARS,
        )
    }
}

/**
 * Курсор страницы журнала: пара «время, идентификатор» последней отданной записи (T-1.3).
 *
 * Пара, а не одно время: два вызова могут завершиться в одну миллисекунду, и курсор по
 * времени пропустил бы второй или зациклил страницу. `ORDER BY at DESC, id DESC` вместе
 * с условием «строго старше пары» делает страницу стабильной и без `OFFSET`, который
 * сдвигался бы при появлении новых записей сверху.
 */
@Serializable
data class ToolCallCursor(
    /** Время последней отданной записи. */
    val at: Instant,
    /** Идентификатор последней отданной записи. */
    val id: ToolCallId,
)

/** Событие хоста, приходящее клиенту без запроса. */
@Serializable
sealed interface HostEvent {

    /** Состояние воркспейса изменилось, данные нужно перезапросить. */
    @Serializable
    @SerialName("workspaceChanged")
    data class WorkspaceChanged(
        /** Какой воркспейс изменился. */
        val workspaceId: WorkspaceId,
    ) : HostEvent

    /**
     * Состояние прогона изменилось (T-1.1).
     *
     * Событие — единственный способ доставить смену состояния клиенту, который о ней
     * не спрашивал (§ 8.4); подключившийся позже клиент узнаёт состояние запросом
     * [ClientMessage.AgentStatus], а не из истории событий.
     */
    @Serializable
    @SerialName("runStateChanged")
    data class RunStateChanged(
        /** Прогон в новом состоянии. */
        val run: AgentRun,
    ) : HostEvent

    /**
     * Статус задачи изменился (T-1.1).
     *
     * Отдельное событие, а не только смена прогона: задача меняет статус и до появления
     * прогона (постановка в очередь, отказ планирования), и без него подключённый клиент
     * не увидел бы ни «в очереди», ни причины отказа.
     */
    @Serializable
    @SerialName("taskStateChanged")
    data class TaskStateChanged(
        /** Задача в новом статусе. */
        val task: Task,
    ) : HostEvent

    /**
     * Вызов инструмента записан в журнал (T-1.3, FR-AGENT-8).
     *
     * Живое появление записи в логе доказывается событием, а не опросом: опрос чаще 500 мс
     * расходовал бы батарею и трафик на пустых ответах. История для подключившегося позже
     * клиента приходит страницами ([ClientMessage.ToolCalls]), а не воспроизведением событий.
     *
     * Несёт полную запись: превью для списка строит клиент тем же правилом, что и хост
     * для страницы ([ToolCallSummary.of]). Потеря события ничего не значит — запись уже
     * в базе, и следующий запрос страницы её вернёт.
     */
    @Serializable
    @SerialName("toolCallRecorded")
    data class ToolCallRecorded(
        /** Записанный вызов целиком. */
        val call: ToolCall,
    ) : HostEvent

    /** Хост завершает работу; клиенту нужно показать состояние «нет связи» (§ 6.1). */
    @Serializable
    @SerialName("hostShuttingDown")
    data object HostShuttingDown : HostEvent
}
