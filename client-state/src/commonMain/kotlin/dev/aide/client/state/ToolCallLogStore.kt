package dev.aide.client.state

import dev.aide.domain.RunId
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ToolCallScope
import dev.aide.protocol.ToolCallSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние журнала вызовов и переходы между ним (T-1.3, T-1.14).
 *
 * Отдельный объект, а не методы [AppStateStore]: у журнала своя пара «данные и переходы"
 * (страницы, курсор, «есть ещё", фильтры), и складывать её в общий держатель экранов значило бы
 * держать его на пороге сложности линтера (класс дефектов `TooManyFunctions`). [AppStateStore]
 * владеет журналом рядом с остальными состояниями, а правила их изменения живут здесь.
 *
 * Догрузка идёт страницами: [page] приклеивает старые записи в конец, [recorded] вставляет
 * живую в начало и отсекает дубли по идентификатору — событие и страница описывают один
 * вызов с двух сторон.
 */
class ToolCallLogStore {

    private val _log = MutableStateFlow(ToolCallLog())

    /** Состояние журнала: страницы, курсор, живые записи, фильтры. */
    val log: StateFlow<ToolCallLog> = _log.asStateFlow()

    /**
     * Открывает журнал в выбранной области: сбрасывает прежние страницы и показывает загрузку.
     *
     * Сброс обязателен: журнал другой области — другие записи, и доклеивать их к прежним
     * значило бы показать вызовы двух задач одним списком.
     */
    fun open(scope: ToolCallScope, tool: String? = null) {
        _log.value = ToolCallLog(scope = scope, tool = tool, loading = true)
    }

    /** Переключает задачу: сбрасывает записи, курсор, раскрытие и фильтр по инструменту. */
    fun selectTask(taskId: TaskId) {
        open(ToolCallScope.Task(taskId), tool = null)
    }

    /** Меняет фильтр по инструменту: записи сбрасываются, чтобы не смешивать с прежним фильтром. */
    fun filter(tool: String?) {
        _log.update { log ->
            val scope = log.scope ?: return@update log
            ToolCallLog(scope = scope, tool = tool, loading = true)
        }
    }

    /** Показывает загрузку страницы (первой при повторе или следующей), не теряя показанные записи. */
    fun loading() {
        _log.update { it.copy(loading = true, failed = false) }
    }

    /**
     * Принимает страницу журнала.
     *
     * Страница применяется только если её scope и tool совпадают с текущими: при смене фильтра
     * поздний ответ старого запроса не должен доклеиваться к новому списку. Записи страницы
     * старше уже показанных и приклеиваются в конец. Дубли отсекаются по идентификатору:
     * живое событие могло успеть добавить вызов раньше, чем доехала страница.
     */
    fun page(page: HostMessage.ToolCallPage) {
        _log.update { log ->
            if (log.scope != page.scope || log.tool != page.tool) return@update log
            log.copy(
                calls = (log.calls + page.calls).distinctBy { it.id },
                cursor = page.nextCursor,
                hasMore = page.hasMore,
                availableTools = page.tools,
                loading = false,
                loaded = true,
                failed = false,
                offline = false,
            )
        }
    }

    /**
     * Вставляет живую запись в начало журнала.
     *
     * Событие применяется только если область и фильтр совпадают с текущими. Для [ToolCallScope.Task]
     * клиент разрешает `runId → taskId` через [runToTask]; неизвестный run не добавляется, чтобы
     * событие чужой задачи не появилось в журнале. Дубли отсекаются по идентификатору.
     */
    fun recorded(call: ToolCall, runToTask: (RunId) -> TaskId?) {
        _log.update { log ->
            val scope = log.scope ?: return@update log
            val matchesScope = when (scope) {
                is ToolCallScope.Run -> scope.runId == call.runId
                is ToolCallScope.Task -> runToTask(call.runId) == scope.taskId
            }
            if (!matchesScope) return@update log
            if (log.tool != null && log.tool != call.tool) return@update log
            val summary = ToolCallSummary.of(call)
            if (log.calls.any { it.id == summary.id }) log else log.copy(calls = listOf(summary) + log.calls)
        }
    }

    /**
     * Раскрывает запись или сворачивает уже раскрытую; возвращает true, если запись раскрыта.
     *
     * Раскрыта не более одной записи: полное содержимое — это большие тексты, и несколько
     * раскрытых строк превратили бы список в нечитаемую простыню. Раскрытие сбрасывает
     * прежнее содержимое и ставит загрузку — вызывающий по возвращённому true запрашивает
     * полную запись (`ToolCallDetail`): страница несёт только превью.
     */
    fun toggle(callId: ToolCallId): Boolean {
        val expanding = _log.value.expanded != callId
        _log.update { log ->
            if (log.expanded == callId) {
                log.copy(expanded = null, detail = null, detailLoading = false, detailFailed = false)
            } else {
                log.copy(expanded = callId, detail = null, detailLoading = true, detailFailed = false)
            }
        }
        return expanding
    }

    /**
     * Принимает результат загрузки полного содержимого раскрытой записи.
     *
     * Успех кладёт запись в деталь только если она всё ещё раскрыта; ошибка видна только
     * у раскрытой записи. Ответ на свёрнутую запись игнорируется.
     */
    fun detailResult(result: Result<ToolCall>) {
        _log.update { log ->
            val call = result.getOrNull()
            when {
                call != null && log.expanded == call.id ->
                    log.copy(detail = call, detailLoading = false, detailFailed = false)

                result.isFailure && log.expanded != null ->
                    log.copy(detailLoading = false, detailFailed = true)

                else -> log
            }
        }
    }

    /** Ошибка запроса журнала: показываются ранее загруженные записи, если они есть. */
    fun failed() {
        _log.update { it.copy(loading = false, failed = true) }
    }

    /**
     * Пометка «нет связи» (§ 3.5).
     *
     * true — связь потеряна, показанные записи не стираются; false — связь восстановлена,
     * пометка снимается. Одна функция вместо пары, чтобы не превышать порог
     * `TooManyFunctions` линтера.
     */
    fun setOffline(offline: Boolean) {
        _log.update { it.copy(offline = offline) }
    }
}
