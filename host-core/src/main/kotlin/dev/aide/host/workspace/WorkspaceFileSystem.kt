package dev.aide.host.workspace

import dev.aide.host.workspace.WorkspaceFileSystem.Companion.MAX_DISPLAY_BYTES
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.ProtocolError
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.notExists
import kotlin.io.path.readBytes

/** Прочитанный файл вместе с тем, что о нём нужно знать UI. */
data class FileContent(
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Текст файла. */
    val text: String,
    /** Длина отданного текста в байтах. */
    val sizeBytes: Long,
    /** true, если файл обрезан по лимиту показа. */
    val truncated: Boolean,
    /** Язык для подсветки или null. */
    val language: String?,
)

/**
 * Чтение файлов внутри воркспейса с проверкой изоляции (§ 10.1, § 3.2).
 *
 * Все публичные методы принимают путь **относительно корня** и не бросают
 * необработанных исключений ввода-вывода: любая проблема превращается в
 * [WorkspaceAccessException] с типизированной причиной, которую клиент покажет
 * как одно из состояний экрана (§ 6.1).
 */
class WorkspaceFileSystem(private val workspace: Workspace) {

    /** Читает файл по пути относительно корня. */
    fun readFile(relativePath: String): FileContent {
        val resolved = resolveInside(relativePath)
        ensureRegularFile(relativePath, resolved)
        if (!Files.isReadable(resolved)) {
            denied(relativePath, "нет прав на чтение файла")
        }

        val size = sizeOf(relativePath, resolved)
        val bytes = readBytes(relativePath, resolved, size)
        if (looksBinary(bytes)) {
            notImplemented("показ бинарных файлов появится в следующем этапе")
        }
        val truncated = size > MAX_DISPLAY_BYTES

        return FileContent(
            path = relativePath,
            text = decode(bytes, truncated),
            sizeBytes = bytes.size.toLong(),
            truncated = truncated,
            language = LanguageDetector.detect(relativePath),
        )
    }

    /** Превращает прочитанный файл в сообщение протокола. */
    fun toPayload(file: FileContent): FileContentPayload = FileContentPayload(
        workspaceId = workspace.id,
        path = file.path,
        text = file.text,
        sizeBytes = file.sizeBytes,
        truncated = file.truncated,
        language = file.language,
    )

    /**
     * Приводит путь к каноническому виду и убеждается, что он внутри корня воркспейса.
     *
     * Проверка идёт по реальному пути на диске ([Path.toRealPath]), поэтому оба
     * обхода — `..` в строке и симлинк наружу — отсекаются одинаково.
     *
     * Для несуществующего пути канонизируется ближайший существующий родитель, а хвост
     * приклеивается к нему. Лексически приклеить хвост можно только тогда, когда в нём
     * нет симлинков: симлинк, чья цель отсутствует (висячий), остаётся «внутри» по строке,
     * но открытие файла по нему создало бы цель **за** корнем. Поэтому симлинк в хвосте —
     * отказ ([canonicalizeMissing]). Симлинк с существующей целью при этом разрешён, если
     * цель внутри корня: такой путь уже покрыт [Path.toRealPath], и он остаётся рабочим.
     */
    internal fun resolveInside(relativePath: String): Path {
        if (relativePath.isBlank()) {
            denied(relativePath, "пустой путь")
        }

        val raw = if (Path.of(relativePath).isAbsolute) {
            // Абсолютный путь допустим только если он уже внутри корня.
            Path.of(relativePath)
        } else {
            workspace.root.resolve(relativePath)
        }

        val isSymlink = Files.isSymbolicLink(raw)
        val canonical = if (raw.notExists()) {
            canonicalizeMissing(relativePath, raw)
        } else {
            realPathOf(relativePath, raw, isSymlink)
        }

        if (!canonical.startsWith(workspace.root)) {
            val reason = if (isSymlink) "симлинк ведёт за пределы воркспейса" else "вне корня воркспейса"
            denied(relativePath, reason)
        }
        return canonical
    }

    private fun ensureRegularFile(relativePath: String, resolved: Path) {
        if (resolved.notExists()) {
            notFound(relativePath)
        }
        if (resolved.isDirectory()) {
            denied(relativePath, "по пути находится каталог, а не файл")
        }
        if (!resolved.isRegularFile()) {
            denied(relativePath, "по пути не обычный файл")
        }
    }

    private fun sizeOf(relativePath: String, resolved: Path): Long = try {
        Files.size(resolved)
    } catch (error: IOException) {
        denied(relativePath, "не удалось определить размер файла: ${error.message}")
    }

    private fun readBytes(relativePath: String, resolved: Path, size: Long): ByteArray = try {
        if (size > MAX_DISPLAY_BYTES) {
            Files.newInputStream(resolved).use { it.readNBytes(MAX_DISPLAY_BYTES) }
        } else {
            resolved.readBytes()
        }
    } catch (error: IOException) {
        denied(relativePath, "ошибка чтения: ${error.message}")
    }

    /**
     * Декодирует байты в UTF-8. Обрезанный файл декодируется мягко: лимит мог разрезать
     * многобайтовый символ, и строгий декодер отверг бы валидный UTF-8 из-за хвоста.
     */
    private fun decode(bytes: ByteArray, truncated: Boolean): String {
        if (truncated) {
            return String(bytes, Charsets.UTF_8)
        }
        return try {
            Charsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes)).toString()
        } catch (ignored: CharacterCodingException) {
            notImplemented("файл не в UTF-8 и пока не показывается")
        }
    }

    private fun realPathOf(relativePath: String, raw: Path, isSymlink: Boolean): Path = try {
        raw.toRealPath()
    } catch (error: IOException) {
        val reason = if (isSymlink) {
            "битый симлинк: ${error.message}"
        } else {
            "не удалось определить путь: ${error.message}"
        }
        denied(relativePath, reason)
    }

    /**
     * Канонизирует несуществующий путь по ближайшему существующему родителю.
     *
     * Хвост от родителя до пути приклеивается лексически — это законно, пока в нём нет
     * симлинков. Симлинк с существующей целью сам был бы ближайшим существующим
     * родителем (или его частью), поэтому в хвосте остаются только висячие: их и
     * отклоняет [denySymlinkInTail].
     */
    private fun canonicalizeMissing(relativePath: String, raw: Path): Path {
        val absolute = raw.toAbsolutePath().normalize()
        var parent = absolute.parent
        while (parent != null && parent.notExists()) parent = parent.parent
        val existing = parent ?: return absolute
        val canonicalParent = try {
            existing.toRealPath()
        } catch (error: IOException) {
            denied(relativePath, "не удалось определить путь: ${error.message}")
        }
        val tail = existing.relativize(absolute)
        denySymlinkInTail(relativePath, existing, tail)
        return canonicalParent.resolve(tail)
    }

    /**
     * Отклоняет несуществующий хвост, проходящий через симлинк.
     *
     * Такой симлинк висячий (цель отсутствует), и лексическая канонизация вернула бы путь
     * «внутри корня», хотя ядро при открытии пошло бы по ссылке и создало файл за корнем.
     * Существующая цель сюда не попадает: она сделала бы свой компонент ближайшим
     * существующим родителем.
     */
    private fun denySymlinkInTail(relativePath: String, existing: Path, tail: Path) {
        var current = existing
        for (component in tail) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                denied(relativePath, "симлинк «$current» ведёт в никуда: цель отсутствует")
            }
        }
    }

    companion object {
        /** Сколько байт файла отдаётся в UI; больше — обрезается с пометкой. */
        const val MAX_DISPLAY_BYTES: Int = 512 * 1024

        /** Сколько первых байт проверяется на бинарность. */
        const val BINARY_SNIFF_BYTES: Int = 8 * 1024
    }
}

/** Бинарность определяется по NUL в первых килобайтах: текстовые UTF-8 файлы NUL не содержат. */
private fun looksBinary(bytes: ByteArray): Boolean {
    val sample = minOf(bytes.size, WorkspaceFileSystem.BINARY_SNIFF_BYTES)
    for (index in 0 until sample) {
        if (bytes[index] == 0.toByte()) return true
    }
    return false
}

private fun denied(path: String, reason: String): Nothing =
    throw WorkspaceAccessException(ProtocolError.AccessDenied(path = path, reason = reason))

private fun notFound(what: String): Nothing =
    throw WorkspaceAccessException(ProtocolError.NotFound(what))

private fun notImplemented(what: String): Nothing =
    throw WorkspaceAccessException(ProtocolError.NotImplemented(what))
