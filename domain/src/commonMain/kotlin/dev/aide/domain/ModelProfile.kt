package dev.aide.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Протокол, на котором говорит провайдер (О-2).
 *
 * Перечисление, а не вендор: `OPENAI_COMPATIBLE` понимают OpenAI, Moonshot/Kimi, DeepSeek,
 * Qwen, OpenRouter, Groq, Mistral, xAI, Gemini через совместимый эндпоинт и локальные
 * Ollama, LM Studio, vLLM; сами вендоры различаются адресом, а не протоколом.
 */
@Serializable
enum class ProviderType {
    /** `/chat/completions` — формат chat completions. */
    OPENAI_COMPATIBLE,

    /** Messages API (Claude): другая форма запроса, другой способ вызова инструментов. */
    ANTHROPIC,
}

/**
 * Провайдер — транспорт: протокол, адрес, откуда брать ключ, свои заголовки.
 *
 * Ключа здесь нет и быть не может: хранится только **имя переменной окружения**,
 * а сам ключ читается из окружения хоста (NFR-8, § 9). Это не стилистическое
 * требование: файл конфигурации — обычный текст, и ключ в нём утёк бы в бэкап,
 * в журнал и в чужие глаза.
 */
@Serializable
data class ProviderProfile(
    /** Идентификатор провайдера, уникальный в таблице. */
    val id: String,
    /** Протокол, на котором говорит провайдер. */
    val type: ProviderType,
    /** Базовый адрес без завершающего `/`; путь протокола дописывает адаптер. */
    val baseUrl: String,
    /** Имя переменной окружения с ключом; null или пусто — ключ не нужен (локальные сервера). */
    val apiKeyEnv: String? = null,
    /** Дополнительные заголовки запроса; нужны шлюзам, требующим свой заголовок. */
    val customHeaders: Map<String, String> = emptyMap(),
)

/**
 * Модель — алиас поверх провайдера: идентификатор для сервера, контекст, предел вывода,
 * возможности и ставки цены (О-2).
 *
 * Один провайдер обслуживает несколько моделей, поэтому смена модели не трогает транспорт.
 * Ставки — в микроединицах за миллион токенов: целые числа, чтобы сумма совпадала с
 * журналом до последней цифры (§ 5.9). Нет ставки — цена неизвестна, а не ноль (FR-COST-5).
 */
@Serializable
data class ModelProfile(
    /** Алиас модели: под ним её выбирают в настройках и записывают в прогон. */
    val alias: String,
    /** Идентификатор провайдера, обслуживающего модель. */
    val provider: String,
    /** Идентификатор модели для сервера (например, `gpt-4o`). */
    val model: String,
    /** Имя для списка в настройках; пусто — показывается [model]. */
    val displayName: String = "",
    /** Размер контекста в токенах. */
    val contextWindow: Int,
    /** Предел вывода в токенах: он же уходит в запрос как `max_tokens`. */
    val maxOutputTokens: Int,
    /** Умеет ли модель вызывать инструменты. */
    val toolUse: Boolean,
    /** Ставка за миллион входных токенов в микроединицах; null — цена неизвестна. */
    val pricePerMillionInMicros: Long? = null,
    /** Ставка за миллион выходных токенов в микроединицах; null — цена неизвестна. */
    val pricePerMillionOutMicros: Long? = null,
) {

    /**
     * Имя модели для показа: [displayName], а если оно пусто — идентификатор [model].
     *
     * Пустое [displayName] означает «зови модель так, как её зовёт сервер» — это осмысленное
     * значение для локальных серверов и для заготовок без красивого имени. Поэтому поле
     * остаётся пустым в данных, а подстановка живёт здесь: так подставлять приходится
     * в одном месте, а не в каждом экране, и одинаково.
     */
    val title: String get() = displayName.ifBlank { model }
}

/**
 * Конфигурация моделей хоста: выбранная по умолчанию модель и две таблицы (О-2).
 *
 * Ключей здесь нет ни в каком виде — только имена переменных окружения в [ProviderProfile].
 * Пустая конфигурация — законное состояние: это чистая установка, и прогон на ней
 * падает с `NOT_CONFIGURED`, а не работает молча.
 */
@Serializable
data class AgentConfig(
    /** Алиас модели по умолчанию; null — модель не выбрана. */
    val defaultModel: String? = null,
    /** Таблица провайдеров. */
    val providers: List<ProviderProfile> = emptyList(),
    /** Таблица моделей. */
    val models: List<ModelProfile> = emptyList(),
)

/**
 * Заготовка каталога: провайдер со своими моделями (О-2).
 *
 * Каталог — данные, а не код: он стареет (модели и цены меняются), поэтому после
 * добавления правится любое поле, а «свой провайдер» равноправен заготовке.
 */
@Serializable
data class ProviderCatalogEntry(
    /** Заготовка провайдера. */
    val provider: ProviderProfile,
    /** Модели этой заготовки. */
    val models: List<ModelProfile>,
)

/**
 * Почему проверка модели не прошла.
 *
 * Коды и имена, а не текст: понятную строку строит UI из ресурсов (NFR-13).
 * [MissingKey] несёт **имя переменной окружения** — без него пользователь не знает,
 * что именно ему задать, и «ключ не найден» превращается в загадку.
 */
@Serializable
sealed interface ModelCheckFailure {

    /** Провайдер не настроен: модель по умолчанию не выбрана. */
    @Serializable
    @SerialName("notConfigured")
    data object NotConfigured : ModelCheckFailure

    /** Алиас не найден в таблице моделей. */
    @Serializable
    @SerialName("unknownModel")
    data class UnknownModel(
        /** Алиас, которого нет в конфигурации. */
        val alias: String,
    ) : ModelCheckFailure

    /**
     * Ключа нет ни в защищённом хранилище, ни в переменной окружения (T-1.58).
     *
     * Несёт **оба имени**, под которыми ключ мог бы существовать: имя переменной окружения
     * и идентификатор провайдера — запись в защищённом хранилище названа именно им. По ним
     * пользователь и находит, что именно задать: переменную окружения процесса хоста или
     * ключ из приложения (то и другое — это одно состояние «ключ не задан», NFR-13).
     */
    @Serializable
    @SerialName("missingKey")
    data class MissingKey(
        /** Имя переменной окружения, которой не хватает. */
        val variable: String,
        /** Провайдер, для которого нет ключа; null — источник ключа только переменная окружения. */
        val provider: String? = null,
    ) : ModelCheckFailure

    /** Провайдер не поддерживает запрос списка моделей (или протокол ещё не реализован). */
    @Serializable
    @SerialName("unsupported")
    data object Unsupported : ModelCheckFailure

    /** Ключ отвергнут провайдером. */
    @Serializable
    @SerialName("unauthorized")
    data object Unauthorized : ModelCheckFailure

    /** Превышен лимит запросов к провайдеру. */
    @Serializable
    @SerialName("rateLimited")
    data object RateLimited : ModelCheckFailure

    /** Сеть недоступна или сервер ответил ошибкой. */
    @Serializable
    @SerialName("requestFailed")
    data class RequestFailed(
        /** Техническая деталь для журнала; в интерфейсе не показывается. */
        val detail: String? = null,
    ) : ModelCheckFailure

    /** Ответ провайдера не разобран. */
    @Serializable
    @SerialName("responseUnreadable")
    data class ResponseUnreadable(
        /** Техническая деталь для журнала; в интерфейсе не показывается. */
        val detail: String? = null,
    ) : ModelCheckFailure
}

/**
 * Почему конфигурация моделей не принята (T-1.56).
 *
 * Проверка при сохранении, а не при чтении: файл правят руками, и его содержимое обязано
 * читаться, даже будучи частично неверным — иначе пользователь не смог бы зайти в настройки
 * и починить то, из-за чего конфигурация отвергнута. А вот записать заведомо нерабочее
 * (пустой адрес, повтор алиаса, размер ≤ 0, модель без провайдера) хост не даёт: молча
 * принятая такая конфигурация проявилась бы позже и непонятно где.
 *
 * Тип едет по проводу как код с данными, понятный текст строит UI из ресурсов (NFR-13).
 */
@Serializable
sealed interface AgentConfigRejection {

    /** У провайдера пустой `baseUrl`: запрос ушёл бы в никуда. */
    @Serializable
    @SerialName("emptyBaseUrl")
    data class EmptyBaseUrl(
        /** Идентификатор провайдера. */
        val provider: String,
    ) : AgentConfigRejection

    /** Два алиаса совпали: реестр взял бы первый, а второй молча не работал бы. */
    @Serializable
    @SerialName("duplicateModelAlias")
    data class DuplicateModelAlias(
        /** Повторяющийся алиас. */
        val alias: String,
    ) : AgentConfigRejection

    /** Модель ссылается на провайдера, которого нет в таблице. */
    @Serializable
    @SerialName("unknownProvider")
    data class UnknownProvider(
        /** Алиас модели. */
        val alias: String,
        /** Идентификатор провайдера, которого нет. */
        val provider: String,
    ) : AgentConfigRejection

    /** Контекст модели не больше нуля. */
    @Serializable
    @SerialName("nonPositiveContext")
    data class NonPositiveContext(
        /** Алиас модели. */
        val alias: String,
    ) : AgentConfigRejection

    /** Предел вывода не больше нуля: он уходит в запрос как `max_tokens`. */
    @Serializable
    @SerialName("nonPositiveMaxOutput")
    data class NonPositiveMaxOutput(
        /** Алиас модели. */
        val alias: String,
    ) : AgentConfigRejection
}

/**
 * Коды причин отказа модели: одна строка на оба конца провода (NFR-13).
 *
 * Словарь общий для хоста и клиента намеренно: код едет по проводу строкой, и два
 * независимых списка строк разошлись бы молча — клиент показывал бы общий текст там,
 * где хост уже умеет сказать точнее.
 */
object ModelFailureCode {

    /** Модель по умолчанию не выбрана или алиас неизвестен: для пользователя это одно состояние. */
    const val NOT_CONFIGURED: String = "NOT_CONFIGURED"

    /** Переменной окружения с ключом нет или она пуста. */
    const val MISSING_KEY: String = "missing_key"

    /** Протокол провайдера ещё не реализован этой сборкой. */
    const val UNSUPPORTED: String = "unsupported"

    /** Ключ отвергнут провайдером. */
    const val UNAUTHORIZED: String = "UNAUTHORIZED"

    /** Провайдер ограничил частоту запросов. */
    const val RATE_LIMITED: String = "RATE_LIMITED"

    /** Сеть недоступна или сервер ответил ошибкой. */
    const val REQUEST_FAILED: String = "REQUEST_FAILED"

    /** Ответ провайдера не разобран. */
    const val RESPONSE_UNREADABLE: String = "RESPONSE_UNREADABLE"
}

/**
 * Код причины отказа для строки состояния (NFR-13).
 *
 * Отсутствие модели и неизвестный алиас дают тот же код, что и раньше
 * (`NOT_CONFIGURED`): для пользователя это одно состояние — «модель не настроена»,
 * и различать их в интерфейсе нечем. Имя отсутствующей переменной окружения
 * показывает экран проверки, где оно и нужно.
 */
val ModelCheckFailure.code: String
    get() = when (this) {
        is ModelCheckFailure.MissingKey -> ModelFailureCode.MISSING_KEY
        is ModelCheckFailure.Unsupported -> ModelFailureCode.UNSUPPORTED
        else -> ModelFailureCode.NOT_CONFIGURED
    }
