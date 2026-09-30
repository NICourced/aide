package dev.aide.host.agent

import dev.aide.domain.ToolCall
import dev.aide.host.store.ToolCallStore
import dev.aide.tools.ports.ToolCallRecorder

/**
 * Журнал вызовов инструментов: порт `host-tools` поверх хранилища хоста (T-1.7).
 *
 * Реализация в `host-core`, потому что только здесь живёт база (О-1): модуль
 * инструментов описывает порт, а хост решает, куда писать. Полноценный журнал
 * с фильтрами и страницами — T-1.3; здесь записывается ровно то, что требуется
 * для «результат вызова попадает в лог».
 */
class StoreToolCallRecorder(private val store: ToolCallStore) : ToolCallRecorder {

    /** Записывает вызов целиком, включая исход и длительность. */
    override fun record(call: ToolCall) {
        store.save(call)
    }
}
