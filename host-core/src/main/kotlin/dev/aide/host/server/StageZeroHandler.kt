package dev.aide.host.server

import dev.aide.host.git.GitAccessException
import dev.aide.host.git.GitRepository
import dev.aide.host.git.JGitRepository
import dev.aide.host.workspace.FileTreeBuilder
import dev.aide.host.workspace.OpenWorkspaces
import dev.aide.host.workspace.OpenedWorkspace
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceAccessException
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import dev.aide.protocol.requestIdOrNull
import java.nio.file.Path

/**
 * Обработчик сообщений этапа 0: открытие репозитория, дерево, содержимое файла, состояние.
 *
 * Открытые воркспейсы держит [OpenWorkspaces], а не этот класс: с T-1.7 диск читают
 * ещё и инструменты агента, которым воркспейс нужен без запроса клиента. Владельцев
 * двух не бывает — иначе агент и клиент работали бы каждый в своём представлении
 * о том, что открыто.
 *
 * @param openGit как открыть git-репозиторий воркспейса; подменяется в тестах.
 * @param mode режим, который хост объявляет клиенту; на поведение обработчика не влияет.
 * @param startedAtMillis момент запуска — от него считается время работы хоста.
 * @param workspaces открытые воркспейсы хоста; тесты могут передать свой набор.
 * @param onWorkspaceOpened что сделать, когда воркспейс открыт (T-1.59): репозиторий появляется
 *   только здесь, поэтому возврат отложенных правок после падения хоста привязан к открытию,
 *   а не к старту процесса. По умолчанию — ничего: обработчику не важно, кто ещё слушает.
 */
class StageZeroHandler(
    private val openGit: (Path) -> GitRepository = { path -> JGitRepository.open(path) },
    private val mode: HostMode = HostMode.LOCAL,
    private val startedAtMillis: Long = System.currentTimeMillis(),
    private val workspaces: OpenWorkspaces = OpenWorkspaces(),
    private val onWorkspaceOpened: suspend () -> Unit = { },
) : ClientMessageHandler, AutoCloseable {

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

        // Сообщения остальных обработчиков (агент, модели, ключи, журнал, приветствие)
        // маршрутизирует ClientMessageRouter, и сюда они не доходят. Перечислять их
        // здесь ради исчерпывающего `when` значило бы держать функцию на пороге сложности
        // линтера; роль страховки исполняет маршрутизатор — он называет каждую группу явно,
        // и новый тип сообщения попадёт в свою группу, а не сюда.
        else -> HostMessage.Failure(
            requestId = message.requestIdOrNull ?: RequestId("stage-zero"),
            error = ProtocolError.Internal("StageZeroHandler получил сообщение другого вида"),
        )
    }

    /**
     * Открывает воркспейс. Любая неудача — типизированный ответ, а не исключение
     * наружу: одно плохое сообщение не должно ронять сессию.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun openWorkspace(message: ClientMessage.OpenWorkspace): HostMessage = try {
        val workspace = Workspace.open(Path.of(message.path))
        val fileSystem = WorkspaceFileSystem(workspace)
        workspaces.add(
            OpenedWorkspace(
                workspace = workspace,
                fileSystem = fileSystem,
                treeBuilder = FileTreeBuilder(fileSystem, workspace),
                // Граница строится здесь же: инструменты агента получают её у воркспейса,
                // а не собирают свой доступ к файлам (T-1.7).
                boundary = WorkspaceBoundaryAdapter(fileSystem),
                git = openGitFor(workspace),
            ),
        )
        // До ответа клиенту: репозиторий уже открыт, и возврат отложенных правок (T-1.59)
        // успевает лечь в состояние, которое клиент запросит сразу после открытия.
        onWorkspaceOpened()
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
        block: (OpenedWorkspace) -> HostMessage,
    ): HostMessage {
        val open = workspaces.find(workspaceId)
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
        workspaces.close()
    }
}
