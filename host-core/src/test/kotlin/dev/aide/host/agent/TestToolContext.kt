package dev.aide.host.agent

import dev.aide.agent.tools.StepTools
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceBoundaryAdapter
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.file.FindFilesTool
import dev.aide.tools.file.ReadFileTool
import dev.aide.tools.file.SearchTextTool
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.ports.ToolCallRecorder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Контекст инструментов для тестов хоста: граница — настоящая.
 *
 * `WorkspaceBoundaryAdapter` поверх `WorkspaceFileSystem`, а не подделка: проверка
 * «путь внутри корня» — это то, что инструменты обязаны выполнять, и заглушка,
 * которая её не делает, оставляла бы поведения непроверенными.
 */
class TestToolContext(override val root: Path) : ToolContext {

    private val boundary = WorkspaceBoundaryAdapter(WorkspaceFileSystem(Workspace.open(root)))

    override fun resolveInside(path: String): Path = boundary.resolveInside(path)
}

/**
 * Инструменты шага на временном каталоге.
 *
 * Нужны тестам, которые инструменты не зовут: движок собирается так же, как в хосте,
 * — с настоящим реестром и настоящей точкой вызова, — иначе тест проверял бы сборку,
 * которой в хосте не бывает.
 */
internal fun testStepTools(
    workspace: Path = Files.createTempDirectory("aide-worker-tools"),
): StepTools {
    val registry = ToolRegistry(listOf(ReadFileTool, FindFilesTool, SearchTextTool))
    return StepTools.of(
        registry = registry,
        invoker = ToolInvoker(
            registry = registry,
            permissions = PermissionResolver { null },
            // Журнал не проверяется этим тестом: вызовов инструментов здесь не бывает.
            recorder = ToolCallRecorder { },
        ),
        context = TestToolContext(workspace),
    )
}
