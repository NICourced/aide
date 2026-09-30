package dev.aide.host.workspace

import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.limits.WorkspaceBoundary
import dev.aide.tools.permission.DenyReason
import java.nio.file.InvalidPathException
import java.nio.file.Path

/**
 * Граница воркспейса для инструментов поверх [WorkspaceFileSystem].
 *
 * Канонизация пути и отказ за пределами корня уже написаны на этапе 0 и покрыты
 * [WorkspaceFileSystemTest]; адаптер их не повторяет, а переводит отказ доступа
 * в [HardLimitViolation] — инструмент получает код причины, а не протокольную
 * ошибку, и отличить настраиваемый запрет от жёсткого предела ему нечем.
 *
 * Порт — единственный **санкционированный** доступ к файлам воркспейса, но сам тип
 * ничего не принуждает: набрать `java.nio.file.Files` в обход порта компилятор не
 * запретит. Принуждение появится вместе с инструментами (T-1.7…T-1.9), когда они
 * будут получать файлы только через этот порт.
 */
class WorkspaceBoundaryAdapter(private val fileSystem: WorkspaceFileSystem) : WorkspaceBoundary {

    override fun resolveInside(path: String): Path =
        try {
            fileSystem.resolveInside(path)
        } catch (error: WorkspaceAccessException) {
            violate(error, path)
        } catch (error: InvalidPathException) {
            // Строка с NUL — тоже выход за предел, а не сбой хоста: контракт порта
            // обещает HardLimitViolation на любой путь, который не удалось разрешить.
            violate(error, path)
        }

    private fun violate(cause: Exception, path: String): Nothing =
        throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, cause.message ?: path, cause)
}
