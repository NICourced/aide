package dev.aide.tools.permission

import dev.aide.domain.Permission
import dev.aide.domain.ToolKind
import dev.aide.domain.ToolPermission
import java.util.concurrent.ConcurrentHashMap

/**
 * Разрешения, выданные «на сессию» из диалога подтверждения (T-1.13).
 *
 * Живут в памяти хоста и умирают с ним: это не настройка, и она не попадает в SQLite.
 * Явная настройка из базы важнее сессионного разрешения — пользователь мог записать
 * `DENY` после того, как разрешил на сессию.
 */
class SessionPermissions {

    private val permissions = ConcurrentHashMap<String, ToolPermission>()

    /**
     * Запомнить разрешение на сессию для одной оси.
     *
     * Меняется только та ось, по которой спросили: у одного инструмента чтение
     * и запись разрешены независимо (FR-TOOLS-8).
     */
    fun allow(tool: String, kind: ToolKind) {
        permissions.compute(tool) { _, current ->
            val base = current ?: ToolPermission(tool, Permission.ASK, Permission.ASK)
            when (kind) {
                ToolKind.READ -> base.copy(read = Permission.ALLOW)
                ToolKind.WRITE -> base.copy(write = Permission.ALLOW)
            }
        }
    }

    /** Текущее сессионное разрешение инструмента или null, если его нет. */
    fun load(tool: String): ToolPermission? = permissions[tool]
}
