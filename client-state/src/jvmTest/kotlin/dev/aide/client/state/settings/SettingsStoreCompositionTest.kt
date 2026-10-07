package dev.aide.client.state.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * FR-AGENT-13: лог вызовов нельзя отключить — это требование прозрачности, а не настройка.
 *
 * Инвариант закрепляется составом настроек: пока ключей ровно четыре (путь, тема, режим
 * управления, адрес хоста), переключателя журнала в приложении нет. Появившийся `KEY_*`
 * с журналом в имени уронит этот тест и потребует осознанного решения, а не тихой настройки.
 *
 * Проверка живёт в `jvmTest`, потому что читает объявленные ключи через рефлексию: в общем
 * коде `client-state` размышлений над полями нет.
 */
class SettingsStoreCompositionTest {

    @Test
    fun `в наборе настроек нет ключа журнала вызовов`() {
        val keys = declaredKeys()
        assertTrue(keys.isNotEmpty(), "ни одного ключа не найдено — проверка состава настроек недействительна")

        assertEquals(
            setOf(
                SettingsStore.KEY_REPOSITORY_PATH,
                SettingsStore.KEY_THEME,
                SettingsStore.KEY_CONTROL_MODE,
                SettingsStore.KEY_HOST_ENDPOINT,
            ),
            keys,
            "Настройки клиента — это и есть состав отключаемого: добавление ключа журнала " +
                "означало бы, что лог можно выключить, а это запрещает FR-AGENT-13",
        )
        assertTrue(
            keys.none { key -> key.contains("log", ignoreCase = true) || key.contains("journal", ignoreCase = true) },
            "В настройках не должно быть ключа журнала: $keys",
        )
    }

    /** Объявленные ключи настроек: статические поля `KEY_*` спутника [SettingsStore]. */
    private fun declaredKeys(): Set<String> = SettingsStore::class.java.declaredFields
        .filter { it.name.startsWith("KEY_") }
        .onEach { it.isAccessible = true }
        .map { it.get(null) as String }
        .toSet()
}
