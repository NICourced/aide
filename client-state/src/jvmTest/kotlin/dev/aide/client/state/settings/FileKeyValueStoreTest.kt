package dev.aide.client.state.settings

import java.nio.file.Path
import kotlin.io.path.createTempDirectory
import kotlin.io.path.name
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Десктопная реализация пишет настройки в файл, а не только в память.
 *
 * Проверяется на настоящем файле: хранилище в памяти (`FakeKeyValueStore`) этого
 * не показывает, а критерий T-0.14 требует, чтобы настройки переживали перезапуск.
 */
class FileKeyValueStoreTest {

    private fun tempSettingsFile(): Path =
        createTempDirectory("aide-settings").resolve("settings.properties")

    @Test
    fun `настройки переживают перезапуск через файл`() {
        val file = tempSettingsFile()
        SettingsStore(createKeyValueStoreAt(file)).apply {
            repositoryPath = "/projects/aide"
            theme = ThemePreference.DARK
            controlMode = ControlMode.BUTTONS
        }
        assertTrue(file.toFile().isFile, "После записи настройки файл должен появиться на диске: $file")

        // Перезапуск: новое хранилище читает тот же файл заново.
        val afterRestart = SettingsStore(createKeyValueStoreAt(file))
        assertEquals("/projects/aide", afterRestart.repositoryPath)
        assertEquals(ThemePreference.DARK, afterRestart.theme)
        assertEquals(ControlMode.BUTTONS, afterRestart.controlMode)
    }

    @Test
    fun `удаление ключа убирает его из файла`() {
        val file = tempSettingsFile()
        val store = createKeyValueStoreAt(file)
        store.putString(SettingsStore.KEY_HOST_ENDPOINT, "ws://127.0.0.1:8080/ws")
        store.remove(SettingsStore.KEY_HOST_ENDPOINT)

        assertNull(createKeyValueStoreAt(file).getString(SettingsStore.KEY_HOST_ENDPOINT))
    }

    @Test
    fun `файл настроек по умолчанию лежит в каталоге конфигурации`() {
        assertEquals("settings.properties", defaultSettingsFile().name)
        assertEquals("aide", defaultSettingsFile().parent.name)
    }
}
