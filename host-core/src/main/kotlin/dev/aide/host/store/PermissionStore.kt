package dev.aide.host.store

import dev.aide.domain.ToolPermission
import dev.aide.host.store.db.HostDatabase

/**
 * Права на инструменты в хранилище хоста (§ 10.1, FR-TOOLS-8).
 *
 * Права читаются и пишутся по инструменту целиком: раздельные колонки для чтения
 * и записи нужны, чтобы показать матрицу прав без разбора `payload`.
 */
class PermissionStore internal constructor(private val database: HostDatabase) {

    /** Сохраняет права на инструмент; повторная запись по тому же имени заменяет прежнюю. */
    fun save(permission: ToolPermission) {
        database.toolPermissionQueries.insert(
            tool = permission.tool,
            read_permission = permission.read.name,
            write_permission = permission.write.name,
            payload = StoreCodec.encode(ToolPermission.serializer(), permission),
        )
    }

    /** Читает права на инструмент; инструмент без настроенных прав даёт null. */
    fun load(tool: String): ToolPermission? =
        database.toolPermissionQueries.byTool(tool).executeAsOneOrNull()?.let(RowMapper::permission)

    /** Читает все настроенные права в порядке имён инструментов. */
    fun all(): List<ToolPermission> =
        database.toolPermissionQueries.all().executeAsList().map(RowMapper::permission)
}
