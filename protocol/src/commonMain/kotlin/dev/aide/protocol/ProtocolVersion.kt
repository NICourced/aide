package dev.aide.protocol

import kotlinx.serialization.Serializable

/** Версия протокола. */
@Serializable
data class ProtocolVersion(
    /** Несовпадение major означает несовместимость: частично работающий UI запрещён (§ 8.4). */
    val major: Int,
    /** В пределах одного major младшая версия хоста обслуживает более старые клиенты. */
    val minor: Int,
) {
    override fun toString(): String = "$major.$minor"

    companion object {
        /**
         * Версия, которую объявляют и хост, и клиент этой сборки.
         *
         * 1.1 добавляет сообщения агента (T-1.1). Правило совместимости не менялось:
         * хост обслуживает клиента не новее себя, поэтому старый клиент 1.0 продолжает
         * работать с новым хостом.
         */
        val CURRENT: ProtocolVersion = ProtocolVersion(major = 1, minor = 1)
    }
}
