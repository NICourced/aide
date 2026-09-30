package dev.aide.tools.permission

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import kotlin.test.Test
import kotlin.test.assertEquals

class PermissionResolverTest {

    @Test
    fun `инструмент без настройки и объявленного умолчания получает ask`() {
        val resolver = resolver()
        assertEquals(PermissionDecision.Ask, resolver.resolve("new_tool", ToolKind.READ))
        assertEquals(PermissionDecision.Ask, resolver.resolve("new_tool", ToolKind.WRITE))
        assertEquals(
            tool("new_tool", read = Permission.ASK, write = Permission.ASK),
            resolver.effective("new_tool"),
        )
    }

    @Test
    fun `встроенный инструмент чтения объявляет оси раздельно`() {
        val resolver = resolver()
        val readTool = tool("read_file", read = Permission.ALLOW, write = Permission.ASK)
        assertEquals(PermissionDecision.Allow, resolver.resolve("read_file", ToolKind.READ, declared = readTool))
        assertEquals(PermissionDecision.Ask, resolver.resolve("read_file", ToolKind.WRITE, declared = readTool))
        assertEquals(readTool, resolver.effective("read_file", declared = readTool))
    }

    @Test
    fun `настройка перекрывает объявленное умолчание по обеим осям`() {
        val declared = tool("task", read = Permission.ALLOW, write = Permission.ALLOW)
        Permission.entries.forEach { saved ->
            val resolver = resolver(tool("task", read = saved, write = saved))
            assertEquals(
                decisionOf(saved),
                resolver.resolve("task", ToolKind.READ, declared = declared),
                "ось чтения при настройке $saved",
            )
            assertEquals(
                decisionOf(saved),
                resolver.resolve("task", ToolKind.WRITE, declared = declared),
                "ось записи при настройке $saved",
            )
        }
    }

    @Test
    fun `настройка одной оси не трогает вторую`() {
        val resolver = resolver(tool("download", read = Permission.DENY, write = Permission.ALLOW))
        val declared = tool("download", read = Permission.ALLOW, write = Permission.ASK)
        assertEquals(
            PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS),
            resolver.resolve("download", ToolKind.READ, declared),
        )
        assertEquals(PermissionDecision.Allow, resolver.resolve("download", ToolKind.WRITE, declared))
    }

    @Test
    fun `deny в настройках не обходится объявленным умолчанием`() {
        val resolver = resolver(tool("rm_rf", read = Permission.DENY, write = Permission.DENY))
        val declared = tool("rm_rf", read = Permission.ALLOW, write = Permission.ALLOW)
        assertEquals(
            PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS),
            resolver.resolve("rm_rf", ToolKind.WRITE, declared = declared),
        )
        assertEquals(
            PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS),
            resolver.resolve("rm_rf", ToolKind.READ, declared = declared),
        )
    }

    @Test
    fun `настройка действует и без переданного объявленного умолчания`() {
        val resolver = resolver(tool("run_command", read = Permission.ALLOW, write = Permission.DENY))
        assertEquals(PermissionDecision.Allow, resolver.resolve("run_command", ToolKind.READ))
        assertEquals(
            PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS),
            resolver.resolve("run_command", ToolKind.WRITE),
        )
    }

    @Test
    fun `настройка другого инструмента на решение не влияет`() {
        val resolver = resolver(tool("read_file", read = Permission.ALLOW, write = Permission.ALLOW))
        assertEquals(PermissionDecision.Ask, resolver.resolve("write_file", ToolKind.READ))
        assertEquals(PermissionDecision.Ask, resolver.resolve("write_file", ToolKind.WRITE))
    }

    private fun resolver(vararg saved: ToolPermission): PermissionResolver {
        val byTool = saved.associateBy { it.tool }
        return PermissionResolver { byTool[it] }
    }

    private fun tool(name: String, read: Permission, write: Permission) =
        ToolPermission(tool = name, read = read, write = write)

    private fun decisionOf(permission: Permission): PermissionDecision = when (permission) {
        Permission.ALLOW -> PermissionDecision.Allow
        Permission.ASK -> PermissionDecision.Ask
        Permission.DENY -> PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS)
    }
}
