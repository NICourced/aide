package dev.aide.host

import dev.aide.agent.LlmRunPlanner
import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.NotConfiguredLlmClient
import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.host.store.HostStore
import dev.aide.protocol.HostMode
import java.nio.file.Path
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.koin.core.KoinApplication

/**
 * Хост, поднятый в том же процессе, что и приложение (§ 3.3).
 *
 * Смысл этого класса в том, что локальный режим не имеет собственного кода:
 * он поднимает тот же [ProtocolServer], что и удалённый, и отличается только
 * адресом. Поэтому клиент не может «заметить», что хост локальный, — ему это
 * и не нужно.
 *
 * Остановка приложения должна вызывать [close]: иначе порт остаётся занятым,
 * воркер очереди продолжает жить, открытые git-репозитории и база — не закрыты.
 */
class EmbeddedHost internal constructor(
    private val server: ProtocolServer,
    private val workerJob: Job,
    private val store: HostStore,
    private val handler: StageZeroHandler,
    private val graph: KoinApplication,
) : AutoCloseable {

    /** Порт, на котором слушает хост. */
    val port: Int get() = server.boundPort

    /** Адрес для клиента. */
    val endpoint: String get() = server.endpoint

    /** Режим, который хост объявляет клиенту; на клиентское поведение не влияет. */
    val mode: HostMode get() = server.hostMode

    /**
     * Останавливает хост.
     *
     * Отмена воркера **дожидается** его завершения: финализация остановленного прогона
     * пишется под `NonCancellable`, и если закрыть хранилище раньше, запись уйдёт в базу
     * после `close`. `HostStore.close()` только просит драйвер закрыть соединения —
     * у `ThreadedConnectionManager` из sqlite-driver это no-op, поэтому «после закрытия»
     * здесь означает именно порядок вызовов, а не освобождение файла.
     */
    override fun close() {
        runBlocking { workerJob.cancelAndJoin() }
        server.stop()
        handler.close()
        store.close()
        graph.close()
    }

    companion object {
        /**
         * Поднимает хост на свободном порту loopback.
         *
         * Путь к репозиторию в подписи отсутствует намеренно: воркспейс открывает клиент
         * сообщением `OpenWorkspace`, и хост не решает за него, что открывать, — ровно так
         * же ведёт себя удалённый хост. Сборка зависимостей остаётся в [HostApp].
         *
         * @param port конкретный порт; по умолчанию берётся свободный, чтобы два запуска
         *   приложения на одной машине не конфликтовали.
         * @param databasePath путь к базе хоста; null — база приложения по умолчанию.
         *   Тесты и перезапуск на той же базе задают путь явно.
         * @param llmClient модель для движка прогона; по умолчанию «провайдер не настроен».
         * @param planner планировщик; по умолчанию спрашивает модель.
         */
        fun open(
            port: Int = freeLoopbackPort(),
            databasePath: Path? = null,
            llmClient: LlmClient = NotConfiguredLlmClient(),
            planner: RunPlanner = LlmRunPlanner(llmClient),
        ): EmbeddedHost = HostApp.open(
            port = port,
            databasePath = databasePath,
            llmClient = llmClient,
            planner = planner,
        )
    }
}
