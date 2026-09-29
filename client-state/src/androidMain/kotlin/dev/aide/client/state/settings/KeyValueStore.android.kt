package dev.aide.client.state.settings

import android.content.Context
import android.content.SharedPreferences

/** Реализация на SharedPreferences; контекст приложения задаётся при старте. */
private class SharedPreferencesKeyValueStore(
    private val preferences: SharedPreferences,
) : KeyValueStore {

    override fun getString(key: String): String? = preferences.getString(key, null)

    override fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    override fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

private var applicationContext: Context? = null

/**
 * Задаёт контекст для хранилища настроек. Вызывается до первого [createKeyValueStore];
 * иначе фабрика падает, а не подставляет пустое хранилище: молчаливая потеря настроек
 * хуже явной ошибки при старте.
 */
fun initKeyValueStore(context: Context) {
    applicationContext = context.applicationContext
}

actual fun createKeyValueStore(): KeyValueStore {
    val context = requireNotNull(applicationContext) {
        "Перед созданием настроек вызовите initKeyValueStore(context) в Application или Activity"
    }
    return SharedPreferencesKeyValueStore(context.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE))
}

/** Имя файла SharedPreferences с настройками клиента. */
private const val STORE_NAME: String = "aide-settings"
