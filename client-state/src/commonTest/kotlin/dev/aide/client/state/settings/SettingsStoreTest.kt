package dev.aide.client.state.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    private val repositoryPath = "/projects/aide"

    @Test
    fun `настройки по умолчанию`() {
        val store = SettingsStore(FakeKeyValueStore())
        assertEquals(ThemePreference.SYSTEM, store.theme)
        assertNull(store.repositoryPath, "Путь к репозиторию по умолчанию не задан")
        assertEquals(ControlMode.GESTURES, store.controlMode)
    }

    @Test
    fun `путь к репозиторию переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).repositoryPath = repositoryPath

        // Перезапуск: новый SettingsStore поверх того же хранилища.
        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals(repositoryPath, afterRestart.repositoryPath)
    }

    @Test
    fun `тема переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).theme = ThemePreference.DARK

        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals(ThemePreference.DARK, afterRestart.theme)
    }

    @Test
    fun `адрес хоста переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).hostEndpoint = "ws://192.168.1.10:8080/ws"

        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals("ws://192.168.1.10:8080/ws", afterRestart.hostEndpoint)
    }

    @Test
    fun `режим управления переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).controlMode = ControlMode.BUTTONS
        assertEquals(ControlMode.BUTTONS, SettingsStore(FakeKeyValueStore(backend.snapshot())).controlMode)
    }

    @Test
    fun `неизвестное значение темы читается как системная, а не роняет чтение`() {
        val backend = FakeKeyValueStore(mapOf("theme" to "rainbow"))
        assertEquals(
            ThemePreference.SYSTEM,
            SettingsStore(backend).theme,
            "Повреждённая настройка не должна ломать запуск",
        )
        assertTrue(backend.snapshot().containsKey("theme"))
    }

    @Test
    fun `сброс пути к репозиторию возвращает null`() {
        val store = SettingsStore(FakeKeyValueStore())
        store.repositoryPath = repositoryPath
        store.repositoryPath = null
        assertNull(store.repositoryPath)
    }

    @Test
    fun `запись настройки не дублирует ключи`() {
        val backend = FakeKeyValueStore()
        val store = SettingsStore(backend)
        store.repositoryPath = "/a"
        store.repositoryPath = "/b"
        assertEquals("/b", store.repositoryPath)
        assertEquals(1, backend.snapshot().keys.count { it == SettingsStore.KEY_REPOSITORY_PATH })
    }
}
