package dev.aide.tools

import dev.aide.domain.ToolCall
import dev.aide.domain.ToolPermission
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.ports.ToolCallRecorder
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeBytes
import kotlin.io.path.writeText
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Временный воркспейс для тестов инструментов.
 *
 * Каталог удаляется в [close]: инструменты обходят дерево целиком, и мусор от прогонов
 * копился бы в системном временном каталоге.
 */
class ToolsWorkspace : AutoCloseable {

    /** Корень воркспейса; канонический, как у настоящего (`Workspace.open`). */
    val root: Path = Files.createTempDirectory("aide-tools-").toRealPath()

    /** Контекст инструментов: корень и граница воркспейса. */
    val context: ToolContext = TestToolContext(root)

    /** Создаёт текстовый файл по пути относительно корня. */
    fun write(relativePath: String, text: String): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText(text)
        return file
    }

    /** Создаёт файл из байт: нужен там, где важен размер или содержимое. */
    fun writeBytes(relativePath: String, bytes: ByteArray): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeBytes(bytes)
        return file
    }

    /** Создаёт каталог, чтобы дерево выглядело как проект. */
    fun directory(relativePath: String): Path = root.resolve(relativePath).createDirectories()

    override fun close() {
        root.toFile().deleteRecursively()
    }
}

/**
 * Граница воркспейса для тестов инструментов.
 *
 * Отклоняет путь вне корня — иначе проверка отказа за пределами воркспейса проходила бы
 * и без границы. Канонизация симлинков у настоящей реализации сложнее (`toRealPath`,
 * см. `WorkspaceBoundaryAdapterTest` в host-core); здесь проверяется, что инструмент
 * ходит через порт и получает отказ, а не то, как устроена канонизация.
 */
class TestToolContext(override val root: Path) : ToolContext {

    override fun resolveInside(path: String): Path {
        if (path.isBlank()) throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "пустой путь")
        val raw = Path.of(path)
        val resolved = (if (raw.isAbsolute) raw else root.resolve(path)).normalize()
        if (!resolved.startsWith(root)) {
            throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "вне корня воркспейса: $path")
        }
        return resolved
    }
}

/** Записанные вызовы: по ним проверяется, что вызов и его исход попали в журнал. */
class RecordingToolCalls : ToolCallRecorder {

    val calls: MutableList<ToolCall> = mutableListOf()

    override fun record(call: ToolCall) {
        calls += call
    }
}

/** Аргументы вызова строкой: так их собирает тест, не изображая модель. */
fun argumentsOf(vararg pairs: Pair<String, String>): JsonObject = JsonObject(
    pairs.associate { (name, value) -> name to JsonPrimitive(value) },
)

/**
 * Точка вызова на настоящих правах и настоящем журнале.
 *
 * [stored] — настройки прав пользователя: null означает «настроек нет», и инструмент
 * работает по объявленному умолчанию (у чтения это `ALLOW`, см. О-4).
 */
fun testInvoker(
    registry: ToolRegistry,
    recorder: ToolCallRecorder = RecordingToolCalls(),
    stored: (String) -> ToolPermission? = { null },
): ToolInvoker = ToolInvoker(
    registry = registry,
    permissions = PermissionResolver(stored),
    recorder = recorder,
)
