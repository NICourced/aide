package dev.aide.tools.permission

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission

/** Ось разрешения: FR-TOOLS-8 требует отдельно чтение и отдельно запись. */
enum class ToolKind { READ, WRITE }

/**
 * Почему отказано. Код, а не текст: строку для экрана «нет прав» строит UI из ресурсов (NFR-13).
 *
 * [PATH_NOT_ALLOWED] означает, что путь не разрешён, и им покрыты все причины сразу: вне корня,
 * пустая строка, битый симлинк, недоступный каталог, неразбираемая строка. Единый код потому,
 * что наружу это один и тот же отказ жёсткого предела, а точная причина — в `HardLimitViolation.detail`.
 */
enum class DenyReason { DENIED_BY_SETTINGS, PATH_NOT_ALLOWED, NETWORK_FORBIDDEN }

/** Решение о вызове инструмента до его выполнения. */
sealed interface PermissionDecision {
    /** Вызов выполняется без подтверждения. */
    data object Allow : PermissionDecision

    /** Нужен диалог подтверждения; сам диалог — T-1.13. */
    data object Ask : PermissionDecision

    /** Вызов запрещён; причина — код [DenyReason]. */
    data class Deny(val reason: DenyReason) : PermissionDecision
}

/**
 * Эффективное разрешение инструмента: объявленное умолчание, перекрытое настройкой пользователя.
 *
 * Умолчание по умолчанию — «новый инструмент»: обе оси [Permission.ASK] (FR-TOOLS-9).
 * Встроенный инструмент чтения объявляет себе `read = ALLOW` явно (О-4), поэтому
 * вызывающий передаёт [ToolPermission] целиком: оси независимы, и одной осью
 * каталог инструментов выразить нельзя.
 *
 * [stored] возвращает настройку инструмента или null, если её нет: источник настройки
 * (хранилище хоста) остаётся снаружи, чтобы таблица решений проверялась без базы.
 */
class PermissionResolver(private val stored: (String) -> ToolPermission?) {

    /** Разрешение обеих осей: настройка перекрывает умолчание по каждой оси отдельно (FR-TOOLS-8). */
    fun effective(tool: String, declared: ToolPermission = newToolDefaults(tool)): ToolPermission {
        val saved = stored(tool)
        return ToolPermission(
            tool = tool,
            read = saved?.read ?: declared.read,
            write = saved?.write ?: declared.write,
        )
    }

    /**
     * Решение по одной оси. Настройка `DENY` перекрывает объявленное умолчание: ослаблять
     * запрет пользователя инструменту нечем, иначе «deny в настройках» был бы пожеланием.
     */
    fun resolve(
        tool: String,
        kind: ToolKind,
        declared: ToolPermission = newToolDefaults(tool),
    ): PermissionDecision {
        val permission = effective(tool, declared)
        val axis = when (kind) {
            ToolKind.READ -> permission.read
            ToolKind.WRITE -> permission.write
        }
        return when (axis) {
            Permission.ALLOW -> PermissionDecision.Allow
            Permission.ASK -> PermissionDecision.Ask
            Permission.DENY -> PermissionDecision.Deny(DenyReason.DENIED_BY_SETTINGS)
        }
    }
}

/**
 * «Новый инструмент»: обе оси `ASK`, потому что FR-TOOLS-9 требует подтверждения по умолчанию
 * для всего необъявленного. Встроенное чтение — не исключение из правила, а явное решение
 * каталога инструментов (`read = ALLOW`), и разрешать его здесь значило бы прятать это
 * решение в платформе.
 */
private fun newToolDefaults(tool: String): ToolPermission = ToolPermission(
    tool = tool,
    read = Permission.ASK,
    write = Permission.ASK,
)
