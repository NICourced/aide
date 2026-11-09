package dev.aide.client.ui.screens

import dev.aide.domain.TaskId
import dev.aide.domain.ToolCallId

/**
 * Колбэки экрана журнала вызовов (T-1.14).
 *
 * Отдельный объект, а не пять параметров [CallLogScreen]: линтер считает параметры функции
 * и ругается на длинный список (`LongParameterList`). Группировка по смыслу не меняет
 * поведения, но позволяет добавить выбор задачи и инструмента без превышения порога.
 */
data class CallLogActions(
    /** Загрузить страницу по текущему курсору. */
    val onLoadPage: () -> Unit,
    /** Вернуться назад. */
    val onBack: () -> Unit,
    /** Раскрыть или свернуть строку. */
    val onToggleRow: (ToolCallId) -> Unit,
    /** Выбрать другую задачу. */
    val onSelectTask: (TaskId) -> Unit,
    /** Выбрать фильтр по инструменту; null — все инструменты. */
    val onSelectTool: (String?) -> Unit,
)
