package dev.aide.protocol

/**
 * Проверка совместимости версий протокола (§ 8.4, T-0.9).
 *
 * Решение принимает хост при получении приветствия и, если версии несовместимы,
 * отвечает [HostMessage.Incompatible] и закрывает соединение. Так клиент и хост
 * никогда не работают частично: либо протокол целиком понятен обеим сторонам, либо
 * пользователь видит, что именно обновить.
 */
object ProtocolCompatibility {

    /** Версии совместимы. */
    data object Compatible : ProtocolCompatibilityResult

    /** Версии несовместимы; текста здесь нет — его строит UI по [reason] и версиям (NFR-13). */
    data class Incompatible(
        /** Машинночитаемая причина. */
        val reason: IncompatibilityReason,
        /** Версия клиента на момент проверки. */
        val clientVersion: ProtocolVersion,
        /** Версия хоста на момент проверки. */
        val hostVersion: ProtocolVersion,
    ) : ProtocolCompatibilityResult

    /**
     * Сравнивает версии клиента и хоста.
     *
     * Возвращает только машинночитаемую причину и версии: понятный пользователю текст
     * строит клиентский UI из ресурсов (NFR-13), поэтому по сети он не передаётся —
     * [HostMessage.Incompatible] несёт лишь причину и версию хоста.
     */
    fun check(client: ProtocolVersion, host: ProtocolVersion): ProtocolCompatibilityResult = when {
        client.major < host.major -> Incompatible(IncompatibilityReason.CLIENT_OUTDATED, client, host)

        // После первой ветки major у клиента не меньше, чем у хоста, значит эта ветка — про «клиент новее».
        client.major > host.major -> Incompatible(IncompatibilityReason.HOST_OUTDATED, client, host)

        client.minor > host.minor -> Incompatible(IncompatibilityReason.HOST_OUTDATED, client, host)

        else -> Compatible
    }

    /** Превращает результат проверки в сообщение хоста для ответа клиенту. */
    fun toHostMessage(result: Incompatible, hostVersion: ProtocolVersion): HostMessage.Incompatible =
        HostMessage.Incompatible(reason = result.reason, hostVersion = hostVersion)
}

/** Результат проверки совместимости: либо [ProtocolCompatibility.Compatible], либо причина несовместимости. */
sealed interface ProtocolCompatibilityResult
