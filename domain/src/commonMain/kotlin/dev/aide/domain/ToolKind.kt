package dev.aide.domain

import kotlinx.serialization.Serializable

/**
 * Ось разрешения инструмента: FR-TOOLS-8 требует отдельно чтение и отдельно запись.
 *
 * Живёт в домене, потому что уходит в протокол вместе с запросом подтверждения
 * (T-1.13): клиенту нужно знать ось, чтобы сказать, что произойдёт.
 */
@Serializable
enum class ToolKind { READ, WRITE }
