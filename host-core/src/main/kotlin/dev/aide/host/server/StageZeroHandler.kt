package dev.aide.host.server

import dev.aide.host.git.GitAccessException
import dev.aide.host.git.GitRepository
import dev.aide.host.git.JGitRepository
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceAccessException
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import java.nio.file.Path
import java.util.concurrent.ConcurrentHashMap

/**
 * Обработчик сообщений этапа 0: открытие репозитория, дерево, содержимое файла, состояние.
 *
 * Держит открытые воркспейсы и их ресурсы. Изменяющих операций нет: агент, коммиты
 * и снапшоты появляются в этапе 1, поэтому этот класс — единственное место, где
 * хост читает диск по запросу клиента.
 *
 * @param openGit как открыть git-репозиторий воркспейса; подменяется в тестах.
 * @param mode режим, который хост объявляет клиенту; на поведение обработчика не влияет.
 * @param startedAtMillis момент запуска — от него считается время работы хоста.
 */
class StageZeroHandler(
    private val openGit: (Path) -> GitRepository = { path -> JGitRepository.open(path) },
    private val mode: HostMode = HostMode.LOCAL,
    private val startedAtMillis: Long = System.currentTimeMillis(),
) : ClientMessageHandler, AutoCloseable {

    private class OpenWorkspace(
        val workspace: Workspace,
        val fileSystem: WorkspaceFileSystem,
        val treeBuilder: FileTreeBuilder,
        val git: GitRepository,
    )

    private val opened = ConcurrentHashMap<WorkspaceId, OpenWorkspace>()

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.OpenWorkspace -> openWorkspace(message)

        is ClientMessage.FileTree -> withWorkspace(message.workspaceId, message.requestId) { open ->
            HostMessage.Tree(message.requestId, open.treeBuilder.build())
        }

        is ClientMessage.FileContent -> withWorkspace(message.workspaceId, message.requestId) { open ->
            val file = open.fileSystem.readFile(message.path)
            HostMessage.Content(message.requestId, open.fileSystem.toPayload(file))
        }

        is ClientMessage.HostState -> withWorkspace(message.workspaceId, message.requestId) { open ->
            HostMessage.State(
                requestId = message.requestId,
                state = HostStatePayload(
                    workspaceId = open.workspace.id,
                    rootPath = open.workspace.root.toString(),
                    branch = open.git.currentBranch(),
                    headCommit = open.git.headCommit(),
                    uptimeMillis = System.currentTimeMillis() - startedAtMillis,
                    mode = mode,
                ),
            )
        }

        // Сообщения агента маршрутизируются в AgentRunHandler; сюда они не доходят,
        // но `when` по запечатанному типу обязан их назвать.
        is ClientMessage.PostTask -> HostMessage.Failure(
            requestId = message.requestId,
            error = ProtocolError.Internal("постановку задачи обрабатывает AgentRunHandler"),
        )

        is ClientMessage.AgentStatus -> HostMessage.Failure(
            requestId = message.requestId,
            error = ProtocolError.Internal("состояние агента обрабатывает AgentRunHandler"),
        )

        is ClientMessage.RunControl -> HostMessage.Failure(
            requestId = message.requestId,
            error = ProtocolError.Internal("управление прогоном обрабатывает AgentRunHandler"),
        )

        is ClientMessage.Hello -> HostMessage.Failure(
            requestId = RequestId("unexpected"),
            error = ProtocolError.Internal("приветствие обрабатывает сессия, а не обработчик"),
        )
    }

    /**
     * Открывает воркспейс. Любая неудача — типизированный ответ, а не исключение
     * наружу: одно плохое сообщение не должно ронять сессию.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun openWorkspace(message: ClientMessage.OpenWorkspace): HostMessage = try {
        val workspace = Workspace.open(Path.of(message.path))
        val fileSystem = WorkspaceFileSystem(workspace)
        opened[workspace.id] = OpenWorkspace(
            workspace = workspace,
            fileSystem = fileSystem,
            treeBuilder = FileTreeBuilder(fileSystem, workspace),
            git = openGitFor(workspace),
        )
        HostMessage.WorkspaceOpened(message.requestId, workspace.id)
    } catch (error: WorkspaceAccessException) {
        HostMessage.Failure(message.requestId, error.error)
    } catch (error: Exception) {
        HostMessage.Failure(
            message.requestId,
            ProtocolError.Internal("не удалось открыть репозиторий", error.message),
        )
    }

    /** Отсутствие `.git` — это отказ в доступе к воркспейсу, а не внутренняя ошибка хоста. */
    private fun openGitFor(workspace: Workspace): GitRepository = try {
        openGit(workspace.root)
    } catch (error: GitAccessException) {
        throw WorkspaceAccessException(error.error)
    }

    @Suppress("TooGenericExceptionCaught")
    private inline fun withWorkspace(
        workspaceId: WorkspaceId,
        requestId: RequestId,
        block: (OpenWorkspace) -> HostMessage,
    ): HostMessage {
        val open = opened[workspaceId]
            ?: return HostMessage.Failure(requestId, ProtocolError.WorkspaceClosed(workspaceId))
        return try {
            block(open)
        } catch (error: WorkspaceAccessException) {
            HostMessage.Failure(requestId, error.error)
        } catch (error: GitAccessException) {
            HostMessage.Failure(requestId, error.error)
        } catch (error: Exception) {
            HostMessage.Failure(requestId, ProtocolError.Internal("ошибка обработки запроса", error.message))
        }
    }

    override fun close() {
        opened.values.forEach { runCatching { it.git.close() } }
        opened.clear()
    }
}
