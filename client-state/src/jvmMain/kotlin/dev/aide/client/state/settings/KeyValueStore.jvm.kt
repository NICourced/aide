package dev.aide.client.state.settings

import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Реализация на файле `settings.properties` в каталоге конфигурации пользователя.
 * Чтение и запись — под замком: настройки могут менять несколько окон приложения.
 */
private class FileKeyValueStore(private val file: Path) : KeyValueStore {

    private val lock = Any()

    override fun getString(key: String): String? = synchronized(lock) { read().getProperty(key) }

    override fun putString(key: String, value: String) {
        synchronized(lock) {
            file.parent?.createDirectories()
            val properties = read()
            properties.setProperty(key, value)
            file.outputStream().use { properties.store(it, "AI Studio settings") }
        }
    }

    override fun remove(key: String) {
        synchronized(lock) {
            val properties = read()
            properties.remove(key)
            file.outputStream().use { properties.store(it, "AI Studio settings") }
        }
    }

    private fun read(): Properties = Properties().apply {
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }
}

/**
 * Файл настроек по умолчанию: `$XDG_CONFIG_HOME/aide/settings.properties`,
 * а если переменная не задана — `~/.config/aide/settings.properties`.
 */
fun defaultSettingsFile(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".config")
    return base.resolve("aide").resolve("settings.properties")
}

actual fun createKeyValueStore(): KeyValueStore = FileKeyValueStore(defaultSettingsFile())

/** Переопределение пути: нужно тестам, которые проверяют запись и чтение файла. */
fun createKeyValueStoreAt(file: Path): KeyValueStore = FileKeyValueStore(file)
