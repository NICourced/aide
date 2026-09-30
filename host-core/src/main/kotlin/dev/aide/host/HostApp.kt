package dev.aide.host

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.InterruptedRuns
import dev.aide.agent.LlmRunPlanner
import dev.aide.agent.RunPlanner
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.NotConfiguredLlmClient
import dev.aide.host.agent.AgentRunHandler
import dev.aide.host.agent.ClientMessageRouter
import dev.aide.host.agent.RunWorker
import dev.aide.host.agent.ServerRunEventSink
import dev.aide.host.agent.StoreRunRepository
import dev.aide.host.agent.StoreTaskRepository
import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.ClientSessions
import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.store.HostStore
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Composition root хоста: единственное место, где известно, из чего состоит хост.
 *
 * Модуль параметризован режимом и портом: эти значения отличают локальный хост от
 * удалённого, разделяемым состоянием они не являются. Путь к базе, модель и планировщик —
 * тоже параметры: тесты подставляют временную базу и скриптованного клиента, а на чистой
 * установке работает модель «провайдер не настроен».
 *
 * Граф изолированный ([KoinApplication.init], а не глобальный контекст): хост поднимается
 * и в приложении, и в тестах — `EmbeddedHostTest` открывает его несколько раз за прогон,
 * а глобальный контекст Koin допускает только один запуск на процесс.
 */
object HostApp {

    /** Квалификатор режима: значение нужно и обработчику, и серверу. */
    val modeQualifier: Qualifier = named("hostMode")

    /** Квалификатор порта: сервер слушает именно его. */
    val portQualifier: Qualifier = named("hostPort")

    /** Квалификатор версии протокола, которую хост объявляет клиенту. */
    val versionQualifier: Qualifier = named("hostVersion")

    /**
     * Собирает части хоста: хранилище, обработчики, движок прогона и сервер.
     *
     * `ClientMessageRouter` регистрируется как [ClientMessageHandler]: сервер зависит
     * от интерфейса, а Koin сопоставляет определение по объявленному типу. Реестр
     * сессий ([ClientSessions]) — общий для сервера и рассылки событий прогона;
     * это и разрывает цикл «сервер → обработчик → движок → сервер».
     */
    fun module(
        mode: HostMode,
        port: Int,
        databasePath: Path?,
        llmClient: LlmClient,
        planner: RunPlanner,
    ): Module = module {
        single(modeQualifier) { mode }
        single(portQualifier) { port }
        // Версия протокола у хоста одна — текущая сборка: параметром её никто не
        // подменяет, а несовместимость версий проверяется на уровне сессии (§ 8.4).
        single(versionQualifier) { ProtocolVersion.CURRENT }
        single { if (databasePath == null) DatabaseFactory.open() else DatabaseFactory.open(databasePath) }
        single { ClientSessions() }
        single { StageZeroHandler(mode = get(modeQualifier)) }
        single {
            AgentRunEngine(
                runs = StoreRunRepository(get<HostStore>().runs),
                tasks = StoreTaskRepository(get<HostStore>().tasks),
                planner = planner,
                llm = llmClient,
                events = ServerRunEventSink(get()),
            )
        }
        single {
            AgentRunHandler(
                engine = get(),
                runs = get<HostStore>().runs,
                tasks = get<HostStore>().tasks,
            )
        }
        single<ClientMessageHandler> {
            ClientMessageRouter(stageZero = get<StageZeroHandler>(), agent = get<AgentRunHandler>())
        }
        single {
            ProtocolServer(
                handler = get(),
                hostVersion = get(versionQualifier),
                mode = get(modeQualifier),
                port = get(portQualifier),
                sessions = get(),
            )
        }
    }

    /**
     * Поднимает хост на свободном порту loopback, если порт не задан явно.
     *
     * Перед запуском сервера незавершённые прогоны помечаются прерванными, а воркер
     * очереди начинает ждать задач: порядок важен — до первого клиента состояние
     * восстановлено, и очередь пуста (решение 8).
     *
     * Возвращает [EmbeddedHost] вместе с графом: `close()` освобождает порт, отменяет
     * корутины, закрывает хранилище и сам граф.
     */
    fun open(
        mode: HostMode = HostMode.LOCAL,
        port: Int = freeLoopbackPort(),
        databasePath: Path? = null,
        llmClient: LlmClient = NotConfiguredLlmClient(),
        planner: RunPlanner = LlmRunPlanner(llmClient),
    ): EmbeddedHost {
        val graph = KoinApplication.init()
            .modules(
                module(
                    mode = mode,
                    port = port,
                    databasePath = databasePath,
                    llmClient = llmClient,
                    planner = planner,
                ),
            )
        val store = graph.koin.get<HostStore>()
        InterruptedRuns(StoreRunRepository(store.runs), StoreTaskRepository(store.tasks)).markInterrupted()

        val engine = graph.koin.get<AgentRunEngine>()
        val workerJob = SupervisorJob()
        RunWorker(engine).start(CoroutineScope(workerJob + Dispatchers.Default))

        val server = graph.koin.get<ProtocolServer>()
        server.start()
        return EmbeddedHost(
            server = server,
            workerJob = workerJob,
            store = store,
            handler = graph.koin.get(),
            graph = graph,
        )
    }
}
