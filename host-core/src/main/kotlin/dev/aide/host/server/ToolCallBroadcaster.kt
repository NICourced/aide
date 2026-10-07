package dev.aide.host.server

import dev.aide.domain.ToolCall
import dev.aide.protocol.HostEvent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Публикация записи журнала клиентам (T-1.3).
 *
 * Узкий порт, а не прямая ссылка на рассылку: `StoreToolCallRecorder` обязан публиковать
 * событие, не приостанавливаясь (иначе прогон ждал бы медленного клиента на каждом вызове),
 * а очередь и рассылка — самостоятельная забота. Порт позволяет подменить публикацию в тесте
 * и проверить порядок «сначала база, потом событие» без потоков.
 */
fun interface ToolCallEvents {

    /** Публикует запись журнала; вызывается после успешной записи в базу. */
    fun publish(call: ToolCall)
}

/**
 * Очередь и рассылка событий о записанных вызовах (T-1.3).
 *
 * [publish] вызывается из корутины прогона и **не ждёт** рассылку: `ClientSessions.broadcast`
 * последователен и держит до 500 мс на сессию, а на сотне вызовов это минуты ожидания.
 * Событие кладётся в очередь, а рассылает его отдельная корутина хоста ([start]).
 *
 * Переполнение очереди — не потеря данных: запись уже в базе (её сохранил [StoreToolCallRecorder]
 * до публикации), и клиент всегда может перечитать страницу. Теряется только живое появление
 * записи, и это единственная плата за неблокирующий вызов. Поэтому при переполнении пишется
 * предупреждение, а не бросается исключение.
 */
class ToolCallBroadcaster(
    private val sessions: ClientSessions,
    private val capacity: Int = DEFAULT_CAPACITY,
) : ToolCallEvents {

    private val logger = LoggerFactory.getLogger(ToolCallBroadcaster::class.java)

    private val queue = Channel<HostEvent.ToolCallRecorded>(capacity = capacity)

    /** Неблокирующая публикация: при полной очереди событие отбрасывается (данные — в базе). */
    override fun publish(call: ToolCall) {
        val result = queue.trySend(HostEvent.ToolCallRecorded(call))
        if (result.isFailure) {
            logger.warn(
                "Очередь рассылки журнала полна (${capacity}): событие о вызове ${call.id.value} " +
                    "не разослано. Запись в базе — клиент перечитает страницу.",
            )
        }
    }

    /**
     * Запускает рассылку; возвращает job, отменив который хост останавливает её.
     *
     * Очередь закрывается вместе с отменой: неразосланные события при остановке хоста
     * теряются так же, как при переполнении, — записи остаются в базе.
     */
    fun start(scope: CoroutineScope): Job = scope.launch {
        for (event in queue) {
            sessions.broadcast(event)
        }
    }

    private companion object {
        /** Буфер рассылки: с запасом на всплеск вызовов без блокировки прогона. */
        const val DEFAULT_CAPACITY: Int = 1_024
    }
}
