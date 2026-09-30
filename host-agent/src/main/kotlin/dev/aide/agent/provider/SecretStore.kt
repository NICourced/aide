package dev.aide.agent.provider

import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.SecretStoreUnavailableReason

/**
 * Защищённое хранилище ключей провайдеров (T-1.58).
 *
 * Порт, а не платформенный класс: выбор реализации — по платформе хоста (Linux —
 * libsecret, Windows — DPAPI, на остальных — явный отказ), и рантайм агента не знает
 * ни `secret-tool`, ни DPAPI, ни файла, в котором лежит шифрованный блоб.
 *
 * Имя записи — идентификатор провайдера: под ним ключ и сохраняется, и находится.
 *
 * **Контракт недоступного хранилища.** [availability] отвечает, работает ли хранилище
 * вообще; чтение и запись при недоступном хранилище бросают [SecretStoreUnavailableException],
 * а не возвращают пустоту и не пишут мимо. Промолчать здесь нельзя: `null` из [get]
 * читался бы как «ключа нет», то есть ровно как разрешение молча перейти к следующему
 * источнику, а отказ записи — как «сохранено». Вызывающий спрашивает [availability]
 * до операции; исключение остаётся сторожем контракта на случай гонки (keyring заперли
 * между проверкой и чтением) и сбоя платформенного шифрования.
 */
interface SecretStore {

    /** Работает ли хранилище на этой платформе прямо сейчас. */
    fun availability(): SecretStoreAvailability

    /**
     * Значение записи или null, если записи нет.
     *
     * Единственное место, где секрет читается: наружу (в приложение, в ответ, в журнал)
     * значение не выходит.
     */
    fun get(name: String): String?

    /** Записывает значение; повторная запись перезаписывает прежнее. */
    fun put(name: String, value: String)

    /** Удаляет запись; отсутствие записи — не ошибка. */
    fun delete(name: String)
}

/** Состояние хранилища: отсутствие поддержки — это отказ, а не тихий переход на файл. */
sealed interface SecretStoreAvailability {

    /** Хранилище есть и отвечает. */
    data object Available : SecretStoreAvailability

    /** Хранилища нет; причина объясняет пользователю, что именно не работает. */
    data class Unavailable(val reason: SecretStoreUnavailableReason) : SecretStoreAvailability
}

/**
 * Операция над недоступным хранилищем: сохранить или прочитать ключ негде.
 *
 * Отдельный тип, а не молчаливый `null` и не общий `IOException`: вызывающий обязан
 * превратить это в состояние с причиной, а не выдать отказ записи за успех.
 */
class SecretStoreUnavailableException(val reason: SecretStoreUnavailableReason) :
    IllegalStateException("Защищённое хранилище недоступно: $reason")

/**
 * Значение ключа не принято по существу: пустое значение (T-1.58).
 *
 * Пустой ключ неотличим от отсутствия ключа, и запись такого значения создавала бы
 * запись, которой провайдер не воспользуется. Другие причины отказа (неизвестный
 * провайдер) проверяются там, где видна конфигурация, — в обработчике хоста.
 */
class ModelSecretRejectedException(val rejection: ModelSecretRejection) :
    IllegalArgumentException(rejection.toString())
