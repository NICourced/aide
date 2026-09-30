package dev.aide.agent.provider

import dev.aide.domain.ProviderCatalogEntry
import kotlinx.serialization.json.Json

/**
 * Заготовки популярных провайдеров и их моделей (T-1.56, О-2).
 *
 * Каталог — **данные, а не код**: он лежит JSON-ресурсом рядом с модулем, потому что
 * стареет (модели и цены меняются), а живой каталог и импорт из внешнего реестра —
 * это сеть, которую в этапе 1 не заводим. Заготовка — отправная точка: любое поле
 * после добавления правится вручную, и «свой провайдер» равноправен заготовке.
 */
object ProviderCatalog {

    /** Заготовки, прочитанные один раз при первом обращении. */
    val entries: List<ProviderCatalogEntry> by lazy { parse(readResource()) }

    /** Разбирает каталог из текста; отдельно от чтения, чтобы тест мог задать свой вход. */
    internal fun parse(text: String): List<ProviderCatalogEntry> =
        Json { ignoreUnknownKeys = true }.decodeFromString(text)

    /**
     * Читает ресурс каталога.
     *
     * Лениво, а не в `init`: `HostApp.open` поднимается в тестах десятки раз, и разбор
     * каталога нужен только тому, кто его показывает — экрану настроек. Отсутствие
     * ресурса — дефект сборки, а не состояние конфигурации, поэтому это исключение,
     * а не пустой список: пустой каталог выглядел бы как «заготовок нет».
     */
    private fun readResource(): String {
        val stream = ProviderCatalog::class.java.getResourceAsStream(RESOURCE)
            ?: error("Ресурс каталога провайдеров не найден: $RESOURCE")
        return stream.use { it.readBytes().decodeToString() }
    }

    /** Путь к ресурсу внутри артефакта модуля. */
    private const val RESOURCE = "/dev/aide/agent/provider-catalog.json"
}
