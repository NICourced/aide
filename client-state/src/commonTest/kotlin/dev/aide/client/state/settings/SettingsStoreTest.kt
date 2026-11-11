package dev.aide.client.state.settings

import dev.aide.domain.AutonomyMode
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
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
        assertEquals(AutonomyMode.ASK_BEFORE_CHANGES, store.autonomyMode)
    }

    @Test
    fun `путь к репозиторию переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).repositoryPath = repositoryPath

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
    fun `все режимы автономности переживают перезапуск`() {
        for (mode in AutonomyMode.entries) {
            val backend = FakeKeyValueStore()
            SettingsStore(backend).autonomyMode = mode

            val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
            assertEquals(mode, afterRestart.autonomyMode)
        }
    }

    @Test
    fun `режим автономности читается без учёта регистра`() {
        val backend = FakeKeyValueStore(mapOf(SettingsStore.KEY_AUTONOMY_MODE to "full_auto_with_checkpoints"))
        assertEquals(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, SettingsStore(backend).autonomyMode)
    }

    @Test
    fun `неизвестный режим автономности даёт безопасное умолчание`() {
        val backend = FakeKeyValueStore(mapOf(SettingsStore.KEY_AUTONOMY_MODE to "unrestricted"))
        assertEquals(AutonomyMode.ASK_BEFORE_CHANGES, SettingsStore(backend).autonomyMode)
        assertTrue(backend.snapshot().containsKey(SettingsStore.KEY_AUTONOMY_MODE))
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

    @Test
    fun `порог объёма по умолчанию 50`() {
        val store = SettingsStore(FakeKeyValueStore())
        assertEquals(50, store.safeVolumeThresholdLines)
    }

    @Test
    fun `порог объёма переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).safeVolumeThresholdLines = 100

        assertEquals(100, SettingsStore(FakeKeyValueStore(backend.snapshot())).safeVolumeThresholdLines)
    }

    @Test
    fun `повреждённый порог объёма читается как 50`() {
        val backend = FakeKeyValueStore(mapOf(SettingsStore.KEY_SAFE_VOLUME_THRESHOLD to "abc"))
        assertEquals(50, SettingsStore(backend).safeVolumeThresholdLines)
    }

    @Test
    fun `неположительный порог объёма читается как 50`() {
        val zero = FakeKeyValueStore(mapOf(SettingsStore.KEY_SAFE_VOLUME_THRESHOLD to "0"))
        assertEquals(50, SettingsStore(zero).safeVolumeThresholdLines)
        val negative = FakeKeyValueStore(mapOf(SettingsStore.KEY_SAFE_VOLUME_THRESHOLD to "-5"))
        assertEquals(50, SettingsStore(negative).safeVolumeThresholdLines)
    }

    @Test
    fun `сохраняется только положительный порог объёма`() {
        val backend = FakeKeyValueStore()
        val store = SettingsStore(backend)
        store.safeVolumeThresholdLines = 0
        assertEquals(50, store.safeVolumeThresholdLines)
        assertFalse(backend.snapshot().containsKey(SettingsStore.KEY_SAFE_VOLUME_THRESHOLD))

        store.safeVolumeThresholdLines = -1
        assertEquals(50, store.safeVolumeThresholdLines)
        assertFalse(backend.snapshot().containsKey(SettingsStore.KEY_SAFE_VOLUME_THRESHOLD))
    }
}
