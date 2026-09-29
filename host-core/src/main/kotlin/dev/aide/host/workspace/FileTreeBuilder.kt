package dev.aide.host.workspace

import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Обход дерева воркспейса для показа в UI.
 *
 * Обходятся только каталоги, прошедшие проверку изоляции: символические ссылки
 * за пределы корня пропускаются, а не разворачиваются. Каждый каталог обходится
 * не более одного раза: канонические пути уже посещённых каталогов запоминаются,
 * поэтому симлинк на предка (например `link -> .`) не разворачивается бесконечно.
 * Игнорируемые каталоги (служебный каталог git и записи `.gitignore`) не показываются.
 * Число показываемых записей ограничено [maxEntries]. Чтобы пометка «дерево неполное»
 * несла точное число пропущенных записей, обход после достижения предела продолжается,
 * но записи сверх предела только считаются: память под дерево остаётся ограниченной.
 */
class FileTreeBuilder(
    /** Доступ к файловой системе воркспейса; используется для проверки путей. */
    val fileSystem: WorkspaceFileSystem,
    /** Воркспейс, дерево которого строится. */
    val workspace: Workspace,
    /** Предел числа записей в дереве. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** Строит дерево воркспейса. */
    fun build(): FileTreePayload {
        val collected = mutableListOf<FileTreeEntry>()
        val pending = ArrayDeque<Pair<Path, String>>()
        pending += workspace.root to ""
        val visited = mutableSetOf(workspace.root)
        var skipped = 0

        while (pending.isNotEmpty()) {
            val (directory, relative) = pending.removeFirst()
            for (child in childrenOf(directory)) {
                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (isIgnored(childRelative, child) || isEscapingSymlink(child, childRelative)) continue
                val directoryChild = child.isDirectory()
                if (collected.size < maxEntries) {
                    collected += FileTreeEntry(
                        path = childRelative,
                        isDirectory = directoryChild,
                        sizeBytes = if (directoryChild) null else sizeOf(child),
                    )
                } else {
                    skipped += 1
                }
                // Каталог обходим один раз: повторный канонический путь — это либо цикл,
                // либо уже показанное содержимое.
                if (directoryChild && canonicalOf(child)?.let(visited::add) == true) {
                    pending += child to childRelative
                }
            }
        }
        return payload(collected, truncated = skipped > 0, skippedEntries = skipped)
    }

    private fun payload(entries: List<FileTreeEntry>, truncated: Boolean, skippedEntries: Int) = FileTreePayload(
        workspaceId = workspace.id,
        rootPath = workspace.root.toString(),
        entries = entries.sortedBy { it.path },
        truncated = truncated,
        skippedEntries = skippedEntries,
    )

    /** Канонический путь каталога; null, если его не удалось определить (битый симлинк). */
    private fun canonicalOf(directory: Path): Path? = runCatching { directory.toRealPath() }.getOrNull()

    /** Дети каталога, отсортированные по имени; недоступный каталог даёт пустой список, а не срыв обхода. */
    private fun childrenOf(directory: Path): List<Path> = runCatching {
        Files.list(directory).use { it.sorted(Comparator.comparing(Path::name)).toList() }
    }.getOrDefault(emptyList())

    private fun sizeOf(file: Path): Long? = runCatching { Files.size(file) }.getOrNull()

    /** Симлинк показываем только если он остаётся внутри воркспейса. */
    private fun isEscapingSymlink(child: Path, childRelative: String): Boolean =
        Files.isSymbolicLink(child) && runCatching { fileSystem.resolveInside(childRelative) }.isFailure

    private fun isIgnored(relativePath: String, path: Path): Boolean {
        val name = path.name
        return name == GIT_DIRECTORY ||
            relativePath.split('/').any { it in alwaysIgnoredDirectories } ||
            ignorePatterns.any { pattern ->
                relativePath == pattern || relativePath.startsWith("$pattern/") || name == pattern
            }
    }

    /** Записи `.gitignore` трактуются упрощённо: имя каталога или путь в начале строки, без шаблонов. */
    private val ignorePatterns: Set<String> by lazy {
        val gitignore = workspace.root.resolve(GITIGNORE_FILE)
        runCatching { Files.readAllLines(gitignore) }.getOrDefault(emptyList())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removePrefix("/").removeSuffix("/") }
            .filter { it.isNotEmpty() && !it.contains('*') && !it.contains('?') }
            .toSet()
    }

    companion object {
        /** Предел записей по умолчанию: 20 000 — размер репозитория из NFR-4. */
        const val DEFAULT_MAX_ENTRIES: Int = 20_000

        private const val GIT_DIRECTORY = ".git"
        private const val GITIGNORE_FILE = ".gitignore"

        private val alwaysIgnoredDirectories = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "target", ".kotlin", "__pycache__",
        )
    }
}
