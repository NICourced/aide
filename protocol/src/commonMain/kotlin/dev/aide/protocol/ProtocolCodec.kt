package dev.aide.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Транспортный конверт: имя типа плюс полезная нагрузка в CBOR. */
@Serializable
internal data class WireEnvelope(
    /** Имя типа сообщения. */
    val type: String,
    /** Полезная нагрузка. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is WireEnvelope && type == other.type && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

/** Результат разбора сообщения. */
sealed interface DecodeResult<out T> {

    /** Сообщение разобрано. */
    data class Message<T>(val message: T) : DecodeResult<T>

    /** Сообщение пропущено: неизвестный тип или неразобранная нагрузка. Клиент продолжает работу. */
    data class Ignored(val rawType: String?, val reason: String) : DecodeResult<Nothing>
}

/** Имена типов сообщений клиента. */
object ClientMessageType {
    const val HELLO = "hello"
    const val OPEN_WORKSPACE = "openWorkspace"
    const val FILE_TREE = "fileTree"
    const val FILE_CONTENT = "fileContent"
    const val HOST_STATE = "hostState"
}

/** Имена типов сообщений хоста. */
object HostMessageType {
    const val HELLO = "hello"
    const val WORKSPACE_OPENED = "workspaceOpened"
    const val TREE = "tree"
    const val CONTENT = "content"
    const val STATE = "state"
    const val FAILURE = "failure"
    const val INCOMPATIBLE = "incompatible"
    const val EVENT = "event"
}

/**
 * Бинарный кодек протокола (§ 8.4). Формат — CBOR.
 *
 * Неизвестный тип сообщения не бросает исключение наружу: он возвращается как
 * [DecodeResult.Ignored], чтобы вызывающая сторона записала его в лог и продолжила работу.
 *
 * CBOR и бинарные `encodeToByteArray`/`decodeFromByteArray` помечены в kotlinx.serialization
 * экспериментальными — отсюда opt-in на весь объект, а не только на построитель [Cbor].
 */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolCodec {

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ClientMessage): ByteArray = when (message) {
        is ClientMessage.Hello ->
            envelope(ClientMessageType.HELLO, ClientMessage.Hello.serializer(), message)
        is ClientMessage.OpenWorkspace ->
            envelope(ClientMessageType.OPEN_WORKSPACE, ClientMessage.OpenWorkspace.serializer(), message)
        is ClientMessage.FileTree ->
            envelope(ClientMessageType.FILE_TREE, ClientMessage.FileTree.serializer(), message)
        is ClientMessage.FileContent ->
            envelope(ClientMessageType.FILE_CONTENT, ClientMessage.FileContent.serializer(), message)
        is ClientMessage.HostState ->
            envelope(ClientMessageType.HOST_STATE, ClientMessage.HostState.serializer(), message)
    }

    fun encode(message: HostMessage): ByteArray = when (message) {
        is HostMessage.Hello -> envelope(HostMessageType.HELLO, HostMessage.Hello.serializer(), message)
        is HostMessage.WorkspaceOpened ->
            envelope(HostMessageType.WORKSPACE_OPENED, HostMessage.WorkspaceOpened.serializer(), message)
        is HostMessage.Tree -> envelope(HostMessageType.TREE, HostMessage.Tree.serializer(), message)
        is HostMessage.Content -> envelope(HostMessageType.CONTENT, HostMessage.Content.serializer(), message)
        is HostMessage.State -> envelope(HostMessageType.STATE, HostMessage.State.serializer(), message)
        is HostMessage.Failure -> envelope(HostMessageType.FAILURE, HostMessage.Failure.serializer(), message)
        is HostMessage.Incompatible ->
            envelope(HostMessageType.INCOMPATIBLE, HostMessage.Incompatible.serializer(), message)
        is HostMessage.Event -> envelope(HostMessageType.EVENT, HostMessage.Event.serializer(), message)
    }

    fun decodeClientMessage(bytes: ByteArray): DecodeResult<ClientMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return when (env.type) {
            ClientMessageType.HELLO -> decode(env, ClientMessage.Hello.serializer())
            ClientMessageType.OPEN_WORKSPACE -> decode(env, ClientMessage.OpenWorkspace.serializer())
            ClientMessageType.FILE_TREE -> decode(env, ClientMessage.FileTree.serializer())
            ClientMessageType.FILE_CONTENT -> decode(env, ClientMessage.FileContent.serializer())
            ClientMessageType.HOST_STATE -> decode(env, ClientMessage.HostState.serializer())
            else -> DecodeResult.Ignored(env.type, "неизвестный тип сообщения клиента")
        }
    }

    fun decodeHostMessage(bytes: ByteArray): DecodeResult<HostMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return when (env.type) {
            HostMessageType.HELLO -> decode(env, HostMessage.Hello.serializer())
            HostMessageType.WORKSPACE_OPENED -> decode(env, HostMessage.WorkspaceOpened.serializer())
            HostMessageType.TREE -> decode(env, HostMessage.Tree.serializer())
            HostMessageType.CONTENT -> decode(env, HostMessage.Content.serializer())
            HostMessageType.STATE -> decode(env, HostMessage.State.serializer())
            HostMessageType.FAILURE -> decode(env, HostMessage.Failure.serializer())
            HostMessageType.INCOMPATIBLE -> decode(env, HostMessage.Incompatible.serializer())
            HostMessageType.EVENT -> decode(env, HostMessage.Event.serializer())
            else -> DecodeResult.Ignored(env.type, "неизвестный тип сообщения хоста")
        }
    }

    /** Собирает конверт, который клиент или хост может прочитать, даже не зная тип сообщения. */
    fun <T> envelope(type: String, serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(
            WireEnvelope.serializer(),
            WireEnvelope(type, cbor.encodeToByteArray(serializer, value)),
        )

    /** Возвращает имя типа из сырых байтов, не разбирая нагрузку; null, если конверт нечитаем. */
    fun peekType(bytes: ByteArray): String? = readEnvelope(bytes)?.type

    private fun readEnvelope(bytes: ByteArray): WireEnvelope? =
        runCatching { cbor.decodeFromByteArray(WireEnvelope.serializer(), bytes) }.getOrNull()

    private fun <T> decode(env: WireEnvelope, serializer: DeserializationStrategy<T>): DecodeResult<T> =
        runCatching { cbor.decodeFromByteArray(serializer, env.payload) }.fold(
            onSuccess = { DecodeResult.Message(it) },
            onFailure = { DecodeResult.Ignored(env.type, "полезная нагрузка не разобрана: ${it.message}") },
        )
}
