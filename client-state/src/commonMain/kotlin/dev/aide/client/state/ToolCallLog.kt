package dev.aide.client.state

import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.ToolCallCursor
import dev.aide.protocol.ToolCallScope
import dev.aide.protocol.ToolCallSummary

/**
 * Состояние журнала вызовов на клиенте (T-1.3, T-1.14).
 *
 * Отдельно от [HostSession], потому что у журнала есть то, чего нет у снимка состояния:
 * страницы, курсор, признак «есть ещё» и фильтры по задаче/инструменту. Снимок отвечает
 * «какие прогоны и задачи есть», а журнал — «что уже подгружено из вызовов выбранной области».
 *
 * @param scope область журнала: прогон или задача; null — журнал ещё не открывали.
 * @param tool выбранный фильтр по инструменту; null — все инструменты.
 * @param calls записи от новых к старым: живые события вставляются в начало, страницы — в конец.
 * @param availableTools distinct-имена инструментов в выбранной области, не зависящие от фильтра.
 * @param cursor курсор последней подгруженной страницы; null означает «старше пока не грузили».
 * @param hasMore хост сообщил, что за последней страницей есть ещё записи.
 * @param loading идёт запрос (первой страницы или следующей).
 * @param loaded первая страница получена: отличает «пусто» от «ещё не загружали».
 * @param failed последний запрос завершился ошибкой.
 * @param offline связь потеряна, показаны ранее загруженные записи.
 * @param expanded запись, раскрытая для показа полного содержимого; null — все свёрнуты.
 * @param detail полное содержимое раскрытой записи; null, пока оно грузится или не запрошено.
 * @param detailLoading идёт запрос полного содержимого раскрытой записи.
 * @param detailFailed запрос полного содержимого раскрытой записи завершился ошибкой.
 */
data class ToolCallLog(
    /** Область журнала: прогон или задача. */
    val scope: ToolCallScope? = null,
    /** Выбранный фильтр по инструменту; null — все инструменты. */
    val tool: String? = null,
    /** Загруженные записи от новых к старым. */
    val calls: List<ToolCallSummary> = emptyList(),
    /** Доступные инструменты в выбранной области. */
    val availableTools: List<String> = emptyList(),
    /** Курсор последней страницы; null — самая новая страница ещё не подгружена. */
    val cursor: ToolCallCursor? = null,
    /** Есть ли записи старше подгруженных. */
    val hasMore: Boolean = false,
    /** Идёт запрос. */
    val loading: Boolean = false,
    /** Первая страница получена. */
    val loaded: Boolean = false,
    /** Последний запрос завершился ошибкой. */
    val failed: Boolean = false,
    /** Связь с хостом потеряна. */
    val offline: Boolean = false,
    /** Раскрытая запись; раскрыта не более одной — иначе список перестал бы читаться. */
    val expanded: ToolCallId? = null,
    /** Полное содержимое раскрытой записи. */
    val detail: ToolCall? = null,
    /** Грузится полное содержимое раскрытой записи. */
    val detailLoading: Boolean = false,
    /** Загрузка полного содержимого завершилась ошибкой. */
    val detailFailed: Boolean = false,
)
