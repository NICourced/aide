package dev.aide.protocol

import dev.aide.domain.AgentRun
import dev.aide.domain.Task
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

    /** Хост завершает работу; клиенту нужно показать состояние «нет связи» (§ 6.1). */
    @Serializable
    @SerialName("hostShuttingDown")
    data object HostShuttingDown : HostEvent
}
