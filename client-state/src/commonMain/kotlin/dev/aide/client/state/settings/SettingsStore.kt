package dev.aide.client.state.settings

import dev.aide.domain.AutonomyMode
import dev.aide.domain.risk.RiskEvaluator

private const val MIN_VOLUME_THRESHOLD: Int = 1

/** Как приложение выбирает тему. */
enum class ThemePreference {
    /** Следовать системной теме — значение по умолчанию (FR-EDITOR-11). */
    SYSTEM,
    LIGHT,
    DARK,
}

/** Режим управления (FR-CTRL-1..3). На этом этапе только хранится; поведение — задача T-1.43. */
enum class ControlMode {
    /** Только жесты, кроме критичных кнопок. */
    GESTURES,

    /** Все действия кнопками, жесты-действия отключены. */
    BUTTONS,

    /** Навигация жестами, важные действия кнопками. */
    HYBRID,
}

/**
 * Настройки клиента, переживающие перезапуск (§ 9).
 *
 * Чтение устойчиво к повреждённым значениям: неизвестная строка в хранилище даёт
 * значение по умолчанию, а не исключение, иначе один испорченный ключ ломал бы запуск.
 */
class SettingsStore(private val backend: KeyValueStore) {

    /** Путь к последнему открытому репозиторию; null, если репозиторий ещё не открывали. */
    var repositoryPath: String?
        get() = backend.getString(KEY_REPOSITORY_PATH)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) backend.remove(KEY_REPOSITORY_PATH) else backend.putString(KEY_REPOSITORY_PATH, value)
        }

    /** Предпочтение темы. */
    var theme: ThemePreference
        get() = backend.getString(KEY_THEME)
            ?.let { raw -> ThemePreference.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: ThemePreference.SYSTEM
        set(value) = backend.putString(KEY_THEME, value.name)

    /** Режим управления. */
    var controlMode: ControlMode
        get() = backend.getString(KEY_CONTROL_MODE)
            ?.let { raw -> ControlMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: ControlMode.GESTURES
        set(value) = backend.putString(KEY_CONTROL_MODE, value.name)

    /**
     * Режим автономности новых прогонов (T-1.15).
     *
     * Неизвестное или повреждённое значение даёт самый безопасный режим
     * «спрашивать перед изменениями».
     */
    var autonomyMode: AutonomyMode
        get() = backend.getString(KEY_AUTONOMY_MODE)
            ?.let { raw -> AutonomyMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: AutonomyMode.ASK_BEFORE_CHANGES
        set(value) = backend.putString(KEY_AUTONOMY_MODE, value.name)

    /**
     * Порог объёма для авто-применения безопасных изменений (T-1.16).
     *
     * Сохраняется только положительное значение; повреждённое, ноль или отрицательное
     * даёт 50 — значение по умолчанию из § 5.3.2.
     */
    var safeVolumeThresholdLines: Int
        get() = backend.getString(KEY_SAFE_VOLUME_THRESHOLD)
            ?.toIntOrNull()
            ?.takeIf { it >= MIN_VOLUME_THRESHOLD }
            ?: RiskEvaluator.DEFAULT_VOLUME_THRESHOLD_LINES
        set(value) {
            if (value >= MIN_VOLUME_THRESHOLD) backend.putString(KEY_SAFE_VOLUME_THRESHOLD, value.toString())
        }

    /** Адрес хоста вида `ws://host:port/ws`; null, если адрес не задан. */
    var hostEndpoint: String?
        get() = backend.getString(KEY_HOST_ENDPOINT)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) backend.remove(KEY_HOST_ENDPOINT) else backend.putString(KEY_HOST_ENDPOINT, value)
        }

    companion object {
        /** Ключ пути к репозиторию. */
        const val KEY_REPOSITORY_PATH: String = "repositoryPath"

        /** Ключ темы. */
        const val KEY_THEME: String = "theme"

        /** Ключ режима управления. */
        const val KEY_CONTROL_MODE: String = "controlMode"

        /** Ключ режима автономности новых прогонов (T-1.15). */
        const val KEY_AUTONOMY_MODE: String = "autonomyMode"

        /** Ключ порога объёма для авто-применения безопасного (T-1.16). */
        const val KEY_SAFE_VOLUME_THRESHOLD: String = "safeVolumeThresholdLines"

        /** Ключ адреса хоста. */
        const val KEY_HOST_ENDPOINT: String = "hostEndpoint"
    }
}
