package dev.aide.host.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Временный каталог-репозиторий для тестов хоста.
 * Создаётся в системном временном каталоге и удаляется после теста.
 */
class TempRepoFixture : AutoCloseable {

    val root: Path = Files.createTempDirectory("aide-ws-")

    init {
        root.resolve("src/auth").createDirectories()
        root.resolve("src/net").createDirectories()
        root.resolve("docs").createDirectories()
        root.resolve("src/auth/Login.kt").writeText("fun login() = Unit\n")
        root.resolve("src/net/Api.kt").writeText("class Api\n")
        root.resolve("docs/readme.md").writeText("# Проект\n")
        root.resolve(".gitignore").writeText("build/\n")
    }

    /** Создаёт файл, недоступный для чтения (права 000). */
    fun createUnreadableFile(relativePath: String): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText("secret\n")
        file.toFile().setReadable(false, false)
        return file
    }

    /** Создаёт симлинк, ведущий за пределы воркспейса. */
    fun createEscapingSymlink(linkName: String, target: Path): Path {
        val link = root.resolve(linkName)
        Files.createSymbolicLink(link, target)
        return link
    }

    /** Создаёт файл заданного размера для проверки лимитов. */
    fun createLargeFile(relativePath: String, bytes: Int): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        Files.write(file, ByteArray(bytes) { 'a'.code.toByte() })
        return file
    }

    /** Создаёт бинарный файл: NUL-байт в первых килобайтах. */
    fun createBinaryFile(relativePath: String): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        Files.write(file, byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01, 0x02, 0x03))
        return file
    }

    override fun close() {
        // Возвращаем права, иначе удаление не пройдёт.
        root.toFile().walkTopDown().forEach { it.setReadable(true, true) }
        root.toFile().deleteRecursively()
    }
}
