package dev.aide.host.store

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.cbor.Cbor

/**
 * Кодек колонки `payload`: полный доменный объект в CBOR.
 *
 * Домен остаётся единственным описанием объекта — в колонках лежат только поля,
 * по которым ищут. Формат тот же, что у протокола (задача 8): один способ
 * сериализации на весь проект вместо двух несовместимых.
 *
 * CBOR и бинарные `encodeToByteArray`/`decodeFromByteArray` помечены в
 * kotlinx.serialization экспериментальными — отсюда opt-in на весь объект.
 */
@OptIn(ExperimentalSerializationApi::class)
internal object StoreCodec {

    private val cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    }

    fun <T> encode(serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(serializer, value)

    fun <T> decode(deserializer: DeserializationStrategy<T>, bytes: ByteArray): T =
        cbor.decodeFromByteArray(deserializer, bytes)
}
