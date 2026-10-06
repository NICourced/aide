package dev.aide.tools

import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolPermission
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.ports.ChangeSnapshot
import dev.aide.tools.ports.ChangeSnapshots
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
 * Повторяет поведение настоящей границы (`WorkspaceFileSystem` в host-core): существующий
 * путь канонизируется ([java.nio.file.Path.toRealPath]), несуществующий — по ближайшему
 * существующему родителю, а висячий симлинк в хвосте отклоняется. Иначе проверки «отказ за
 * пределами воркспейса» и «запись через висячий симлинк» проходили бы и без границы вовсе.
 */
class TestToolContext(override val root: Path) : ToolContext {

    override fun resolveInside(path: String): Path {
        if (path.isBlank()) throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "пустой путь")
        val raw = Path.of(path)
        val resolved = if (raw.isAbsolute) raw else root.resolve(path)
        val canonical = canonical(resolved)
        if (!canonical.startsWith(root)) {
            throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "вне корня воркспейса: $path")
        }
        return canonical
    }

    /**
     * Канонический путь: ближайший существующий предок приводится к реальному, хвост
     * приклеивается к нему. Хвост, проходящий через висячий симлинк, — отказ: лексически
     * он выглядит внутри корня, а ядро по ссылке создало бы файл за корнем.
     */
    private fun canonical(resolved: Path): Path {
        val absolute = resolved.toAbsolutePath().normalize()
        val existing = generateSequence(absolute) { it.parent }.firstOrNull { Files.exists(it) }
            ?: return absolute
        val real = runCatching { existing.toRealPath() }.getOrElse { existing.normalize() }
        val tail = existing.relativize(absolute)
        var current = existing
        for (component in tail) {
            current = current.resolve(component)
            if (Files.isSymbolicLink(current)) {
                throw HardLimitViolation(DenyReason.PATH_NOT_ALLOWED, "симлинк ведёт в никуда: $current")
            }
        }
        return real.resolve(tail)
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

/** Ссылка точки отката в тестах: по ней видно, что снапшот из результата дошёл до вызова. */
const val TEST_SNAPSHOT_REF: String = "refs/ai/snap/1758535200000-before-agent-step"

/**
 * Порт точки отката в тестах: считает вопросы и отвечает заданным исходом.
 *
 * По умолчанию точка есть: читающие вызовы её не спрашивают вовсе, а изменяющим она нужна,
 * чтобы дойти до инструмента. Исход меняет тест — так проверяются пустой репозиторий и сбой.
 */
class FakeChangeSnapshots(
    private var outcome: ChangeSnapshot = ChangeSnapshot.Taken(SnapshotRef(TEST_SNAPSHOT_REF)),
) : ChangeSnapshots {

    /** Сколько раз точка вызова спрашивала точку отката. */
    var asked: Int = 0
        private set

    /** Прогоны, у которых спрашивали точку отката; по ним видно, что прогон доезжает до порта. */
    val runs: MutableList<RunId> = mutableListOf()

    /** Меняет ответ порта: так проверяются отказ и отсутствие коммита. */
    fun answer(outcome: ChangeSnapshot) {
        this.outcome = outcome
    }

    override suspend fun beforeChange(runId: RunId): ChangeSnapshot {
        asked += 1
        runs += runId
        return outcome
    }
}

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
    snapshots: ChangeSnapshots = FakeChangeSnapshots(),
): ToolInvoker = ToolInvoker(
    registry = registry,
    permissions = PermissionResolver(stored),
    recorder = recorder,
    snapshots = snapshots,
)
