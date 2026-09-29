package dev.aide.client.state

import dev.aide.protocol.IncompatibilityReason
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.SessionId

/**
 * Состояние связи с хостом. UI обязан различать эти состояния, а не показывать
 * пустой экран: cached-режим помечается явно (§ 3.5, § 6.1).
 */
sealed interface ConnectionState {

    /** Соединения ещё не было. */
    data object Idle : ConnectionState

    /** Идёт первая попытка подключения. */
    data object Connecting : ConnectionState

    /** Соединение установлено и приветствие принято. */
    data class Connected(
        /** Идентификатор сессии, выданный хостом. */
        val sessionId: SessionId,
        /** true, если это восстановление после обрыва, а не первое подключение. */
        val reconnected: Boolean,
    ) : ConnectionState

    /** Связь потеряна, идёт ожидание перед следующей попыткой. */
    data class Reconnecting(
        /** Номер попытки, начиная с 1. */
        val attempt: Int,
        /** Сколько миллисекунд ждать до следующей попытки. */
        val nextRetryMillis: Long,
    ) : ConnectionState

    /** Версии протокола несовместимы; UI строит [ConnectionState.Incompatible] текст из ресурсов. */
    data class Incompatible(
        /** Машинночитаемая причина: какую сторону и почему нужно обновить. */
        val reason: IncompatibilityReason,
        /** Версия клиента этой сборки. */
        val clientVersion: ProtocolVersion,
        /** Версия хоста, полученная при подключении. */
        val hostVersion: ProtocolVersion,
    ) : ConnectionState

    /** Соединение закрыто окончательно: остановлено пользователем или хост отверг сессию. */
    data class Closed(
        /** Причина для лога и для показа по запросу. */
        val reason: String,
    ) : ConnectionState
}
