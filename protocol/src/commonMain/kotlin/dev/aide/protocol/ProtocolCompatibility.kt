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

    /** Версии несовместимы; [userMessage] показывается пользователю как есть. */
    data class Incompatible(
        /** Машинночитаемая причина. */
        val reason: IncompatibilityReason,
        /** Текст для пользователя: что обновить и до чего. */
        val userMessage: String,
    ) : ProtocolCompatibilityResult

    /** Сравнивает версии клиента и хоста. */
    fun check(client: ProtocolVersion, host: ProtocolVersion): ProtocolCompatibilityResult = when {
        client.major != host.major && client.major < host.major -> Incompatible(
            reason = IncompatibilityReason.CLIENT_OUTDATED,
            userMessage = "Версия протокола не поддерживается: обновите приложение " +
                "(клиент $client, хост $host).",
        )

        client.major != host.major -> Incompatible(
            reason = IncompatibilityReason.HOST_OUTDATED,
            userMessage = "Версия протокола не поддерживается: обновите хост " +
                "(клиент $client, хост $host).",
        )

        client.minor > host.minor -> Incompatible(
            reason = IncompatibilityReason.HOST_OUTDATED,
            userMessage = "Клиент новее хоста: обновите хост (клиент $client, хост $host).",
        )

        else -> Compatible
    }

    /** Превращает результат проверки в сообщение хоста для ответа клиенту. */
    fun toHostMessage(result: Incompatible, hostVersion: ProtocolVersion): HostMessage.Incompatible =
        HostMessage.Incompatible(reason = result.reason, hostVersion = hostVersion)
}

/** Результат проверки совместимости: либо [ProtocolCompatibility.Compatible], либо причина несовместимости. */
sealed interface ProtocolCompatibilityResult
