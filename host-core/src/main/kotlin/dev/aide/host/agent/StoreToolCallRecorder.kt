package dev.aide.host.agent

import dev.aide.domain.ToolCall
import dev.aide.host.server.ToolCallEvents
import dev.aide.host.store.ToolCallStore
import dev.aide.tools.ports.ToolCallRecorder

/**
 * Журнал вызовов инструментов: порт `host-tools` поверх хранилища хоста (T-1.7, T-1.3).
 *
 * Реализация в `host-core`, потому что только здесь живёт база (О-1): модуль
 * инструментов описывает порт, а хост решает, куда писать.
 *
 * Порядок обязателен и повторяет О-8: **сначала база, потом событие**. Клиент по событию
 * показывает вызов — показать то, чего нет в истории, значит соврать: после реконнекта
 * запись исчезла бы. Запись синхронна (после возврата [record] вызов уже доступен запросом —
 * это строже требуемых 500 мс), а публикация не ждёт рассылки: её ведёт [ToolCallEvents]
 * отдельной корутиной.
 */
class StoreToolCallRecorder(
    private val store: ToolCallStore,
    private val events: ToolCallEvents,
) : ToolCallRecorder {

    /** Записывает вызов целиком — и с любым исходом, — а затем публикует событие о нём. */
    override fun record(call: ToolCall) {
        store.save(call)
        events.publish(call)
    }
}
