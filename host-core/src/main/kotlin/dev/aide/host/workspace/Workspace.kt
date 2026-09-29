package dev.aide.host.workspace

import dev.aide.protocol.ProtocolError
import dev.aide.protocol.WorkspaceId
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Открытый воркспейс: канонический корень и его идентификатор.
 *
 * Канонический путь вычисляется один раз при открытии: все последующие проверки
 * «внутри ли файл» сравниваются с ним, поэтому симлинк на корень воркспейса
 * не открывает доступ наружу.
 */
class Workspace private constructor(
    /** Идентификатор, под которым воркспейс известен клиенту. */
    val id: WorkspaceId,
    /** Канонический путь корня; все проверки идут относительно него. */
    val root: Path,
) {

    companion object {
        /** Открывает каталог как воркспейс. */
        fun open(path: Path): Workspace {
            if (!path.exists()) {
                notFound("путь не существует: $path")
            }
            if (!path.isDirectory()) {
                notFound("путь не является каталогом: $path")
            }
            val canonical = try {
                path.toRealPath()
            } catch (error: IOException) {
                internalError("не удалось определить путь: $path", error.message)
            }
            return Workspace(id = WorkspaceId(UUID.randomUUID().toString()), root = canonical)
        }
    }
}

/** Ошибка доступа к воркспейсу, несущая типизированную причину из протокола. */
class WorkspaceAccessException(val error: ProtocolError) : Exception(error.toString())

private fun notFound(what: String): Nothing =
    throw WorkspaceAccessException(ProtocolError.NotFound(what))

private fun internalError(message: String, detail: String?): Nothing =
    throw WorkspaceAccessException(ProtocolError.Internal(message, detail))
