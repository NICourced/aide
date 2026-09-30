package dev.aide.host

import dev.aide.agent.AgentRunEngine
import dev.aide.agent.InterruptedRuns
import dev.aide.agent.LlmRunPlanner
import dev.aide.agent.RunPlanner
import dev.aide.agent.provider.AgentModels
import dev.aide.agent.provider.ProviderCatalog
import dev.aide.agent.provider.ProviderClients
import dev.aide.agent.provider.ProviderRegistry
import dev.aide.agent.ports.RunPorts
import dev.aide.agent.ports.TaskBranches
import dev.aide.agent.provider.SecretStore
import dev.aide.agent.tools.StepTools
import dev.aide.host.agent.AgentConfigHandler
import dev.aide.host.agent.AgentRunHandler
import dev.aide.host.agent.ClientMessageRouter
import dev.aide.host.agent.ModelSecretHandler
import dev.aide.host.agent.RunWorker
import dev.aide.host.agent.ServerRunEventSink
import dev.aide.host.agent.StoreRunRepository
import dev.aide.host.agent.StoreTaskRepository
import dev.aide.host.agent.StoreToolCallRecorder
import dev.aide.host.agent.TaskBranchGuard
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.server.ClientMessageHandler
import dev.aide.host.server.ClientSessions
import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.host.store.DatabaseFactory
import dev.aide.host.store.HostStore
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.WorkspaceToolContext
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.file.FindFilesTool
import dev.aide.tools.file.ReadFileTool
import dev.aide.tools.file.SearchTextTool
import dev.aide.tools.permission.PermissionResolver
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
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
 * удалённого, разделяемым состоянием они не являются. Путь к базе и к файлу настройки
 * моделей, планировщик и источник моделей — тоже параметры: тесты подставляют временные
 * файлы и скриптованную модель, а на чистой установке работает реестр по файлу, который
 * ещё пуст, — то есть «провайдер не настроен».
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
     *
     * Режим хоста объявляется константой [HostMode.LOCAL]: параметром его никто не
     * подменял (режим — диагностическая надпись для клиента, поведение от него не
     * зависит), а привязка к адресу для реального удалённого хоста появляется в T-1.51.
     *
     * @param storage состояние хоста на диске: база и настройки моделей. Идёт сюда готовым,
     *   потому что экземпляр хранилища настроек должен быть один на хост: он же держит
     *   замок записи, и второй экземпляр на том же пути означал бы его отсутствие.
     */
    fun module(
        port: Int,
        storage: HostStorage,
        models: AgentModels,
        planner: RunPlanner,
    ): Module = module {
        single(modeQualifier) { HostMode.LOCAL }
        single(portQualifier) { port }
        // Версия протокола у хоста одна — текущая сборка: параметром её никто не
        // подменяет, а несовместимость версий проверяется на уровне сессии (§ 8.4).
        single(versionQualifier) { ProtocolVersion.CURRENT }
        single { storage.databasePath?.let(DatabaseFactory::open) ?: DatabaseFactory.open() }
        single { ClientSessions() }
        // Открытые воркспейсы — одна точка правды и для обработчика клиента, и для
        // инструментов агента: воркспейс открывает клиент, а читает его агент (T-1.7).
        single { OpenWorkspaces() }
        single { StageZeroHandler(mode = get(modeQualifier), workspaces = get()) }
        // Ветка задачи: реализация порта объявлена по типу интерфейса, иначе Koin
        // разрешал бы её по точному имени класса и не нашёл бы движку (T-1.18).
        single<TaskBranches> { TaskBranchGuard(workspaces = get(), sessions = get()) }
        single {
            // Реестр и точка вызова собираются из одного экземпляра реестра: определения
            // инструментов у модели и их выполнение обязаны быть про один и тот же набор,
            // иначе модель позвала бы инструмент, которого точка вызова не знает.
            val registry = ToolRegistry(listOf(ReadFileTool, FindFilesTool, SearchTextTool))
            StepTools.of(
                registry = registry,
                invoker = ToolInvoker(
                    registry = registry,
                    // Настройки прав — из хранилища хоста (§ 9): модуль инструментов их не знает.
                    permissions = PermissionResolver(get<HostStore>().permissions::load),
                    recorder = StoreToolCallRecorder(get<HostStore>().toolCalls),
                ),
                context = WorkspaceToolContext(get()),
            )
        }
        single {
            AgentRunEngine(
                ports = RunPorts(
                    runs = StoreRunRepository(get<HostStore>().runs),
                    tasks = StoreTaskRepository(get<HostStore>().tasks),
                    events = ServerRunEventSink(get()),
                ),
                models = models,
                planner = planner,
                tools = get(),
                branches = get(),
            )
        }
        single {
            AgentRunHandler(
                engine = get(),
                runs = get<HostStore>().runs,
                tasks = get<HostStore>().tasks,
            )
        }
        single {
            AgentConfigHandler(
                store = storage.config,
                checkModel = models::check,
                catalog = { ProviderCatalog.entries },
            )
        }
        single {
            ModelSecretHandler(
                store = storage.secrets,
                config = storage.config::load,
            )
        }
        single<ClientMessageHandler> {
            ClientMessageRouter(
                stageZero = get<StageZeroHandler>(),
                agent = get<AgentRunHandler>(),
                modelConfig = get<AgentConfigHandler>(),
                modelSecrets = get<ModelSecretHandler>(),
            )
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
     * корутины, закрывает хранилище, HTTP-клиент провайдеров и сам граф.
     *
     * @param databasePath путь к базе хоста; null — база приложения по умолчанию.
     *   Файл настроек моделей лежит рядом с базой: это часть состояния того же хоста,
     *   и уносить её в отдельный каталог значило бы однажды разойтись с базой.
     * @param models модели хоста; null — реестр по файлу настроек, то есть настоящее
     *   поведение приложения. Параметр существует для тестов, подставляющих
     *   скриптованную модель (О-11).
     * @param planner планировщик; по умолчанию спрашивает выбранную модель.
     * @param secrets защищённое хранилище ключей; null — выбор по платформе хоста.
     *   Параметр существует для тестов: настоящее хранилище (libsecret, DPAPI) есть не
     *   на всякой машине и не в CI, а путь «ключ из приложения» проверяться обязан всюду.
     */
    fun open(
        port: Int = freeLoopbackPort(),
        databasePath: Path? = null,
        models: AgentModels? = null,
        planner: RunPlanner = LlmRunPlanner(),
        secrets: SecretStore? = null,
    ): EmbeddedHost {
        val storage = HostStorage.of(databasePath, secrets)
        // Реестр и HTTP-клиент собираются только тогда, когда модели не подставлены:
        // подставленному источнику транспорта не нужно, а поднятый «на всякий случай»
        // реестр читал бы файл, которого в его ветке исполнения никто не спрашивает.
        val httpClient: HttpClient?
        val provider: AgentModels
        if (models == null) {
            httpClient = providerHttpClient()
            provider = ProviderRegistry(
                config = storage.config::load,
                clients = ProviderClients(httpClient),
                secrets = storage.secrets,
            )
        } else {
            httpClient = null
            provider = models
        }
        val graph = KoinApplication.init()
            .modules(module(port = port, storage = storage, models = provider, planner = planner))
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
            httpClient = httpClient,
        )
    }
}
