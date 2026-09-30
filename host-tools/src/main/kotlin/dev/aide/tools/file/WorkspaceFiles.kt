package dev.aide.tools.file

import java.nio.file.FileVisitResult
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.SimpleFileVisitor
import java.nio.file.attribute.BasicFileAttributes

/** Имя каталога git: агент не читает его — там нет ничего нужного, зато много мусора. */
private const val GIT_DIR: String = ".git"

/**
 * Файлы воркспейса в один обход (T-1.7, решение 5).
 *
 * Обход идёт от корня и не выходит за него: каталог `.git` пропускается целым поддеревом
 * (не «фильтруется после» — незачем обходить то, что не нужно), а симлинки не
 * разворачиваются вовсе. Симлинк — единственный способ, которым обход мог бы оказаться
 * за корнем (`Files.walkFileTree` без `FOLLOW_LINKS` внутрь него не заходит), и вместо
 * проверки каждого пути на «внутри ли» он просто не обходится.
 *
 * Список, а не поток, потому что читают его три инструмента по-разному — один
 * фильтрует по маске, другой ищет строки, — и держать открытым дескриптор дерева
 * до конца их работы значило бы связывать время жизни обхода с чужой логикой.
 * Плата — память на пути; на этапе 1 она несущественна (поиск без индекса и так
 * ограничен, индексация — этап 6).
 */
internal fun workspaceFiles(root: Path): List<Path> {
    val files = mutableListOf<Path>()
    Files.walkFileTree(
        root,
        object : SimpleFileVisitor<Path>() {

            override fun preVisitDirectory(dir: Path, attrs: BasicFileAttributes): FileVisitResult {
                val skip = dir != root && (dir.fileName.toString() == GIT_DIR || attrs.isSymbolicLink)
                return if (skip) FileVisitResult.SKIP_SUBTREE else FileVisitResult.CONTINUE
            }

            override fun visitFile(file: Path, attrs: BasicFileAttributes): FileVisitResult {
                if (attrs.isRegularFile && !attrs.isSymbolicLink) files.add(file)
                return FileVisitResult.CONTINUE
            }
        },
    )
    return files
}

/**
 * Путь относительно корня с разделителями `/`.
 *
 * Разделитель задан явно: на Windows `Path.toString()` даёт обратные косые, и строка
 * выдачи отличалась бы от того, что агент и пользователь видят в редакторе.
 */
internal fun relativePath(root: Path, path: Path): String = root.relativize(path).joinToString("/") { it.toString() }
