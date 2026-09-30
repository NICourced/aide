package dev.aide.agent.config

import dev.aide.domain.AgentConfig
import kotlinx.serialization.json.Json

/**
 * JSON-форма конфигурации моделей (T-1.56, решение 2).
 *
 * Конфигурация — файл, а не таблица базы: это настройка, которую правит человек, а не
 * доменные данные (§ 9). Формат стабилен и читаем: имена полей — часть договора с тем,
 * кто открыл файл редактором.
 *
 * `encodeDefaults` пишет поля с умолчаниями (`apiKeyEnv: null`, пустые таблицы): файл
 * должен показывать все поля профиля, а не только заполненные — иначе непонятно,
 * что вообще можно задать. Ключа здесь нет и быть не может: только имя переменной (NFR-8).
 */
object AgentConfigCodec {

    private val json = Json {
        prettyPrint = true
        encodeDefaults = true
        // Незнакомое поле не роняет чтение: файл правят руками, и лишняя строка в нём
        // (в том числе оставшаяся от более новой версии) не должна отбирать настройки.
        ignoreUnknownKeys = true
    }

    /** Сериализует конфигурацию в текст файла. */
    fun encode(config: AgentConfig): String = json.encodeToString(AgentConfig.serializer(), config)

    /**
     * Разбирает конфигурацию из текста.
     *
     * Пустой текст — пустая конфигурация, а не ошибка: файл создаётся пустым при первом
     * запуске, и это законное состояние чистой установки.
     */
    fun decode(text: String): AgentConfig {
        if (text.isBlank()) return AgentConfig()
        return json.decodeFromString(AgentConfig.serializer(), text)
    }
}
