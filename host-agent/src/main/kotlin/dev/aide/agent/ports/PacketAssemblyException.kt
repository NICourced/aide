package dev.aide.agent.ports

/**
 * Сборка пакета изменений невозможна; приводит к [dev.aide.agent.RunInterruptReason.PACKET_ASSEMBLY_FAILED].
 *
 * Живёт в порте, а не в реализации хоста: движок знает только порт и не должен
 * зависеть от деталей git/SQLite, где собирается пакет.
 */
class PacketAssemblyException(message: String, cause: Throwable? = null) : Exception(message, cause)
