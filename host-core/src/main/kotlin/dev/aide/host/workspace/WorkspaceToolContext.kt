package dev.aide.host.workspace

import dev.aide.tools.ToolContext
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.DenyReason
import java.nio.file.Path

/**
 * Контекст инструментов поверх открытого воркспейса (T-1.7).
 *
 * Корень и граница берутся у **текущего** воркспейса при каждом вызове, а не один раз
 * при сборке хоста: агент стартует раньше, чем клиент откроет воркспейс, и запомненный
 * при сборке путь указывал бы в никуда. Тем же свойством закрывается и обратный случай:
 * при перезапуске приложения хост создаётся заново, а воркспейс открывается заново
 * клиентом, — и оба получают согласованную пару «корень и граница».
 *
 * Воркспейс не открыт — отказ жёсткого предела, а не ошибка: инструменту нечего читать,
 * и «пустой файл» вместо отказа стал бы ложью, о которой агент не узнал бы (О-4).
 */
class WorkspaceToolContext(private val workspaces: OpenWorkspaces) : ToolContext {

    override val root: Path get() = opened().workspace.root

    override fun resolveInside(path: String): Path = opened().boundary.resolveInside(path)

    private fun opened(): OpenedWorkspace = workspaces.current() ?: throw HardLimitViolation(
        reason = DenyReason.PATH_NOT_ALLOWED,
        detail = "воркспейс не открыт: агенту нечего читать",
    )
}
