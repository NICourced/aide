package dev.aide.host.server

import dev.aide.protocol.HostEvent
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Активные сессии клиентов: по ним рассылаются события хоста без запроса (§ 8.4).
 *
 * Реестр вынесен из [ProtocolServer] отдельным объектом, потому что рассылать
 * события нужно не только серверу: движок прогона сообщает о смене состояния
 * через этот же реестр. Без выделения получился бы цикл зависимостей
 * «сервер → обработчик → движок → сервер», который не собирается ни в Koin,
 * ни просто глазами.
 */
class ClientSessions {

    private val sessions: MutableSet<ClientSession> = ConcurrentHashMap.newKeySet()

    /** Регистрирует сессию; вызывается при подключении. */
    fun add(session: ClientSession) {
        sessions += session
    }

    /** Убирает сессию; вызывается при закрытии соединения. */
    fun remove(session: ClientSession) {
        sessions -= session
    }

    /**
     * Рассылает событие всем активным сессиям.
     *
     * Ошибка или зависшая отправка отдельной сессии не мешает остальным и не роняет
     * сервер: у каждой сессии свой таймаут, закрывшаяся сессия просто пропускается.
     */
    suspend fun broadcast(event: HostEvent) {
        val encoded = ProtocolCodec.encode(HostMessage.Event(event))
        sessions.forEach { session ->
            withTimeoutOrNull(BROADCAST_TIMEOUT_MILLIS) { runCatching { session.deliver(encoded) } }
        }
    }

    /** Забывает все сессии; вызывается при остановке сервера. */
    fun clear() {
        sessions.clear()
    }

    private companion object {
        /** Сколько ждать отправки события одной сессии, чтобы остановка не зависла. */
        const val BROADCAST_TIMEOUT_MILLIS: Long = 500
    }
}
