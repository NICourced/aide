package dev.aide.client.state

import dev.aide.domain.RunId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ToolCallSummary
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * Состояние журнала вызовов и переходы между ним (T-1.3).
 *
 * Отдельный объект, а не методы [AppStateStore]: у журнала своя пара «данные и переходы»
 * (страницы, курсор, «есть ещё»), и складывать её в общий держатель экранов значило бы
 * держать его на пороге сложности линтера (класс дефектов `TooManyFunctions`). [AppStateStore]
 * владеет журналом рядом с остальными состояниями, а правила их изменения живут здесь.
 *
 * Догрузка идёт страницами: [page] приклеивает старые записи в конец, [recorded] вставляет
 * живую в начало и отсекает дубли по идентификатору — событие и страница описывают один
 * вызов с двух сторон.
 */
class ToolCallLogStore {

    private val _log = MutableStateFlow(ToolCallLog())

    /** Состояние журнала: страницы, курсор, живые записи. */
    val log: StateFlow<ToolCallLog> = _log.asStateFlow()

    /**
     * Открывает журнал прогона: сбрасывает прежние страницы и показывает загрузку.
     *
     * Сброс обязателен: журнал другого прогона — другие записи, и доклеивать их к прежним
     * значило бы показать вызовы двух прогонов одним списком.
     */
    fun open(runId: RunId) {
        _log.value = ToolCallLog(runId = runId, loading = true)
    }

    /** Показывает загрузку страницы (первой при повторе или следующей), не теряя показанные записи. */
    fun loading() {
        _log.update { it.copy(loading = true, failed = false) }
    }

    /**
     * Принимает страницу журнала.
     *
     * Записи страницы старше уже показанных и приклеиваются в конец. Дубли отсекаются по
     * идентификатору: живое событие могло успеть добавить вызов раньше, чем доехала страница.
     */
    fun page(page: HostMessage.ToolCallPage) {
        _log.update { log ->
            val base = if (log.runId == page.runId) log.calls else emptyList()
            log.copy(
                runId = page.runId,
                calls = (base + page.calls).distinctBy { it.id },
                cursor = page.nextCursor,
                hasMore = page.hasMore,
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
     * Событие другого прогона игнорируется: пока открыт журнал одного прогона, чужой вызов
     * в нём — посторонняя запись. Дубли отсекаются по идентификатору: событие может прийти
     * для вызова, который уже попал в подгруженную страницу.
     */
    fun recorded(call: ToolCall) {
        _log.update { log ->
            if (log.runId != call.runId) return@update log
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

    /** Принимает полное содержимое раскрытой записи; ответ на неё же игнорируется после сворачивания. */
    fun detailLoaded(call: ToolCall) {
        _log.update { log ->
            if (log.expanded == call.id) log.copy(detail = call, detailLoading = false, detailFailed = false) else log
        }
    }

    /** Ошибка загрузки полного содержимого: видна только у раскрытой записи. */
    fun detailFailed() {
        _log.update { log ->
            if (log.expanded == null) log else log.copy(detailLoading = false, detailFailed = true)
        }
    }

    /** Ошибка запроса журнала: показываются ранее загруженные записи, если они есть. */
    fun failed() {
        _log.update { it.copy(loading = false, failed = true) }
    }

    /** Потеря связи: журнал помечается, но показанные записи не стираются (§ 3.5). */
    fun offline() {
        _log.update { it.copy(offline = true) }
    }

    /** Связь восстановлена: пометка «нет связи» снимается. */
    fun online() {
        _log.update { it.copy(offline = false) }
    }
}
