package dev.aide.client.state.settings

/** Хранилище в памяти: позволяет тестировать настройки без платформы. */
class FakeKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {

    private val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    /** Снимок содержимого — эмулирует перезапуск приложения. */
    fun snapshot(): Map<String, String> = values.toMap()
}
