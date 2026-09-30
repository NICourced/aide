package dev.aide.agent

import dev.aide.agent.tools.StepTools
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolPermission
import dev.aide.tools.AgentTool
import dev.aide.tools.ToolContext
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.file.FindFilesTool
import dev.aide.tools.file.ReadFileTool
import dev.aide.tools.file.SearchTextTool
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.ports.ToolCallRecorder
import java.nio.file.Files
import java.nio.file.Path

/**
 * Контекст инструментов в тестах: корень — каталог фикстуры, граница ведёт себя
 * как настоящая (`WorkspaceBoundaryAdapter`): путь за корнем отклоняется.
 *
 * Заглушка, которая ничего не отклоняет, сделала бы проверку «вызов за пределами
 * воркспейса отклоняется» пустой — она проходила бы и без границы вовсе.
 */
class TestToolContext(override val root: Path) : ToolContext {

    override fun resolveInside(path: String): Path {
        if (path.isBlank()) throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "пустой путь")
        val raw = Path.of(path)
        val resolved = (if (raw.isAbsolute) raw else root.resolve(path)).normalize()
        if (!resolved.startsWith(root)) {
            throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "вне корня воркспейса: $path")
        }
        return resolved
    }
}

/** Записанные вызовы: по ним проверяется, что вызов и его исход попали в журнал. */
class RecordingToolCalls : ToolCallRecorder {

    val calls: MutableList<ToolCall> = mutableListOf()

    override fun record(call: ToolCall) {
        calls += call
    }
}

/** Инструменты шага в новом временном каталоге: тесты, которым файлы не нужны, их не готовят. */
fun stepTools(
    recorder: ToolCallRecorder = RecordingToolCalls(),
    stored: (String) -> ToolPermission? = { null },
): StepTools = stepToolsIn(Files.createTempDirectory("aide-agent-tools"), recorder, stored)

/** Инструменты шага в заданном каталоге: файлы для чтения готовит сам тест. */
fun stepToolsIn(
    workspace: Path,
    recorder: ToolCallRecorder = RecordingToolCalls(),
    stored: (String) -> ToolPermission? = { null },
    tools: List<AgentTool> = listOf(ReadFileTool, FindFilesTool, SearchTextTool),
): StepTools {
    val registry = ToolRegistry(tools)
    return StepTools.of(
        registry = registry,
        invoker = ToolInvoker(
            registry = registry,
            permissions = PermissionResolver(stored),
            recorder = recorder,
        ),
        context = TestToolContext(workspace),
    )
}
