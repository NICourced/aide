package dev.aide.client.state.settings

/**
 * Простое хранилище «ключ — строка» с платформенной реализацией.
 *
 * Интерфейс, а не `expect class`: `expect`-классы финальны и их конструктор не виден
 * общему коду, поэтому подменить хранилище в `commonTest` было бы нельзя — тесты
 * настроек писали бы в настоящий файл или в `SharedPreferences`. Платформенная
 * часть — только фабрика [createKeyValueStore].
 */
interface KeyValueStore {

    /** Возвращает значение или null, если ключ не задан. */
    fun getString(key: String): String?

    /** Записывает значение. */
    fun putString(key: String, value: String)

    /** Удаляет ключ; отсутствующий ключ — не ошибка. */
    fun remove(key: String)
}

/** Создаёт хранилище настроек приложения. */
expect fun createKeyValueStore(): KeyValueStore
