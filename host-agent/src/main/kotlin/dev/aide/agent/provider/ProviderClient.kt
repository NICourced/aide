package dev.aide.agent.provider

import dev.aide.agent.llm.LlmClient
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile

/**
 * Клиент провайдера: вызов модели и проверка доступа.
 *
 * Проверка доступа — отдельная операция, а не «попробуем вызвать модель»: у моделей
 * разная цена, а неудачный вызов неотличим от неудачной настройки. Один запрос списка
 * моделей дешевле и честнее — он и отвечает на вопрос «ключ принят?».
 */
interface ProviderClient : LlmClient {

    /** Проверяет доступ к провайдеру: null — доступ есть, иначе типизированный отказ. */
    suspend fun checkAccess(): ModelCheckFailure?
}

/** Протокол провайдера ещё не реализован: заготовка есть, адаптера нет (T-1.57). */
class UnsupportedProviderProtocolException(val provider: ProviderProfile) :
    IllegalArgumentException(
        "Для протокола ${provider.type} провайдера ${provider.id} адаптера ещё нет",
    )

/**
 * Как построить клиента по профилям провайдера и модели.
 *
 * Отдельная абстракция, а не `when` внутри реестра: тесты подставляют фабрику на
 * `MockEngine` и проверяют разбор ответа, не поднимая сеть, а появление второго
 * протокола (T-1.57) не трогает ни реестр, ни движок.
 */
fun interface ProviderClientFactory {

    /**
     * Создаёт клиента или возвращает отказ.
     *
     * Отказ — здесь, а не исключением: неподдерживаемый протокол это состояние
     * конфигурации, о котором пользователю надо сказать словами, а не падение хоста.
     */
    fun create(provider: ProviderProfile, model: ModelProfile, apiKey: String?): Result<ProviderClient>
}
