package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Соединение с хостом. Клиентский код знает только этот интерфейс,
 * поэтому локальный хост и удалённый неотличимы (§ 3.3).
 */
interface HostConnection {

    /** Текущее состояние связи; UI подписывается на него для показа «нет связи». */
    val state: StateFlow<ConnectionState>

    /** Сообщения хоста, не являющиеся ответами на запросы: события. */
    val events: SharedFlow<HostMessage>

    /** Запускает цикл соединения с автопереподключением. Возвращается сразу. */
    fun start()

    /** Останавливает соединение и отменяет переподключения. */
    suspend fun stop()

    /**
     * Отправляет запрос и ждёт ответ с тем же `requestId`.
     *
     * @return ответ хоста либо null, если ответ не пришёл за [timeoutMillis].
     */
    suspend fun request(message: ClientMessage, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): HostMessage?

    companion object {
        /** Таймаут ответа по умолчанию: открытие репозитория на большом дереве укладывается в него с запасом. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
    }
}
