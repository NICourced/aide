// CBOR и бинарные encodeToByteArray/decodeFromByteArray помечены в kotlinx.serialization
// экспериментальными; opt-in нужен и объявлениям уровня файла (cbor, envelope, decode),
// которым не достаётся аннотация объекта.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

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
    const val POST_TASK = "postTask"
    const val AGENT_STATUS = "agentStatus"
    const val RUN_CONTROL = "runControl"
    const val AGENT_CONFIG_REQUEST = "agentConfigRequest"
    const val SAVE_AGENT_CONFIG = "saveAgentConfig"
    const val CHECK_MODEL = "checkModel"
    const val SET_MODEL_SECRET = "setModelSecret"
    const val DELETE_MODEL_SECRET = "deleteModelSecret"
    const val MODEL_SECRETS = "modelSecrets"
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
    const val TASK_POSTED = "taskPosted"
    const val AGENT_SNAPSHOT = "agentSnapshot"
    const val RUN_CONTROLLED = "runControlled"
    const val AGENT_CONFIG_SNAPSHOT = "agentConfigSnapshot"
    const val AGENT_CONFIG_SAVED = "agentConfigSaved"
    const val MODEL_CHECK_RESULT = "modelCheckResult"
    const val MODEL_SECRET_CHANGED = "modelSecretChanged"
    const val MODEL_SECRETS_SNAPSHOT = "modelSecretsSnapshot"
    const val EVENT = "event"
}

/**
 * Бинарный кодек протокола (§ 8.4). Формат — CBOR.
 *
 * Неизвестный тип сообщения не бросает исключение наружу: он возвращается как
 * [DecodeResult.Ignored], чтобы вызывающая сторона записала его в лог и продолжила работу.
 *
 * CBOR и бинарные `encodeToByteArray`/`decodeFromByteArray` помечены в kotlinx.serialization
 * экспериментальными — отсюда opt-in на весь файл.
 *
 * Сборка конверта (`envelope`) намеренно `internal`: это деталь реализации кодека, а не часть
 * контракта протокола. Публично сообщение задаётся парой `encode`/`decode…Message`.
 *
 * Таблицы разбиты по владельцу сообщения — этап 0, агент, настройки моделей, — как и
 * обработчики хоста: одна плоская таблица на все типы упирается в порог сложности, а её
 * разбиение по поводам меняться не отнимает ничего, кроме одной лишней функции. Верхний
 * `when` при кодировании остаётся исчерпывающим: новый тип сообщения не соберётся, пока
 * его не отнесут к группе.
 */
@OptIn(ExperimentalSerializationApi::class)
object ProtocolCodec {

    /** Кодирует сообщение клиента; полнота таблицы гарантируется исчерпывающим `when`. */
    fun encode(message: ClientMessage): ByteArray = when (message) {
        is ClientMessage.Hello, is ClientMessage.OpenWorkspace, is ClientMessage.FileTree,
        is ClientMessage.FileContent, is ClientMessage.HostState -> encodeStageZero(message)

        is ClientMessage.PostTask, is ClientMessage.AgentStatus, is ClientMessage.RunControl -> encodeAgent(message)

        is ClientMessage.AgentConfigRequest, is ClientMessage.SaveAgentConfig, is ClientMessage.CheckModel ->
            encodeModelConfig(message)

        is ClientMessage.SetModelSecret, is ClientMessage.DeleteModelSecret, is ClientMessage.ModelSecrets ->
            encodeModelSecrets(message)
    }

    /** Кодирует сообщение хоста; полнота таблицы гарантируется исчерпывающим `when`. */
    fun encode(message: HostMessage): ByteArray = when (message) {
        is HostMessage.Hello, is HostMessage.WorkspaceOpened, is HostMessage.Tree, is HostMessage.Content,
        is HostMessage.State, is HostMessage.Failure, is HostMessage.Incompatible, is HostMessage.Event ->
            encodeStageZero(message)

        is HostMessage.TaskPosted, is HostMessage.AgentSnapshot, is HostMessage.RunControlled -> encodeAgent(message)

        is HostMessage.AgentConfigSnapshot, is HostMessage.AgentConfigSaved, is HostMessage.ModelCheckResult ->
            encodeModelConfig(message)

        is HostMessage.ModelSecretChanged, is HostMessage.ModelSecretsSnapshot -> encodeModelSecrets(message)
    }

    fun decodeClientMessage(bytes: ByteArray): DecodeResult<ClientMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return decodeStageZeroClient(env)
            ?: decodeAgentClient(env)
            ?: decodeModelConfigClient(env)
            ?: decodeModelSecretsClient(env)
            ?: DecodeResult.Ignored(env.type, "неизвестный тип сообщения клиента")
    }

    fun decodeHostMessage(bytes: ByteArray): DecodeResult<HostMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return decodeStageZeroHost(env)
            ?: decodeAgentHost(env)
            ?: decodeModelConfigHost(env)
            ?: decodeModelSecretsHost(env)
            ?: DecodeResult.Ignored(env.type, "неизвестный тип сообщения хоста")
    }

    /** Возвращает имя типа из сырых байтов, не разбирая нагрузку; null, если конверт нечитаем. */
    fun peekType(bytes: ByteArray): String? = readEnvelope(bytes)?.type
}

private val cbor = Cbor {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

/** Собирает конверт, который клиент или хост может прочитать, даже не зная тип сообщения. */
internal fun <T> envelope(type: String, serializer: SerializationStrategy<T>, value: T): ByteArray =
    cbor.encodeToByteArray(
        WireEnvelope.serializer(),
        WireEnvelope(type, cbor.encodeToByteArray(serializer, value)),
    )

private fun readEnvelope(bytes: ByteArray): WireEnvelope? =
    runCatching { cbor.decodeFromByteArray(WireEnvelope.serializer(), bytes) }.getOrNull()

/** Разбирает нагрузку одного типа; неразобранная нагрузка пропускается, а не роняет разбор. */
private fun <T> decode(env: WireEnvelope, serializer: DeserializationStrategy<T>): DecodeResult<T> =
    runCatching { cbor.decodeFromByteArray(serializer, env.payload) }.fold(
        onSuccess = { DecodeResult.Message(it) },
        onFailure = { DecodeResult.Ignored(env.type, "полезная нагрузка не разобрана: ${it.message}") },
    )

/** Сообщение не отнесено к группе: так выглядит только ошибка в самой таблице. */
private fun wrongGroup(message: Any): Nothing =
    error("Сообщение не отнесено к группе кодировщика: ${message::class.simpleName}")

// ——— Этап 0: репозиторий, ФС, состояние хоста ———

private fun encodeStageZero(message: ClientMessage): ByteArray = when (message) {
    is ClientMessage.Hello -> envelope(ClientMessageType.HELLO, ClientMessage.Hello.serializer(), message)
    is ClientMessage.OpenWorkspace ->
        envelope(ClientMessageType.OPEN_WORKSPACE, ClientMessage.OpenWorkspace.serializer(), message)

    is ClientMessage.FileTree ->
        envelope(ClientMessageType.FILE_TREE, ClientMessage.FileTree.serializer(), message)

    is ClientMessage.FileContent ->
        envelope(ClientMessageType.FILE_CONTENT, ClientMessage.FileContent.serializer(), message)

    is ClientMessage.HostState ->
        envelope(ClientMessageType.HOST_STATE, ClientMessage.HostState.serializer(), message)

    else -> wrongGroup(message)
}

private fun encodeStageZero(message: HostMessage): ByteArray = when (message) {
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
    else -> wrongGroup(message)
}

private fun decodeStageZeroClient(env: WireEnvelope): DecodeResult<ClientMessage>? = when (env.type) {
    ClientMessageType.HELLO -> decode(env, ClientMessage.Hello.serializer())
    ClientMessageType.OPEN_WORKSPACE -> decode(env, ClientMessage.OpenWorkspace.serializer())
    ClientMessageType.FILE_TREE -> decode(env, ClientMessage.FileTree.serializer())
    ClientMessageType.FILE_CONTENT -> decode(env, ClientMessage.FileContent.serializer())
    ClientMessageType.HOST_STATE -> decode(env, ClientMessage.HostState.serializer())
    else -> null
}

private fun decodeStageZeroHost(env: WireEnvelope): DecodeResult<HostMessage>? = when (env.type) {
    HostMessageType.HELLO -> decode(env, HostMessage.Hello.serializer())
    HostMessageType.WORKSPACE_OPENED -> decode(env, HostMessage.WorkspaceOpened.serializer())
    HostMessageType.TREE -> decode(env, HostMessage.Tree.serializer())
    HostMessageType.CONTENT -> decode(env, HostMessage.Content.serializer())
    HostMessageType.STATE -> decode(env, HostMessage.State.serializer())
    HostMessageType.FAILURE -> decode(env, HostMessage.Failure.serializer())
    HostMessageType.INCOMPATIBLE -> decode(env, HostMessage.Incompatible.serializer())
    HostMessageType.EVENT -> decode(env, HostMessage.Event.serializer())
    else -> null
}

// ——— Агент: постановка задачи, состояния, управление прогоном ———

private fun encodeAgent(message: ClientMessage): ByteArray = when (message) {
    is ClientMessage.PostTask ->
        envelope(ClientMessageType.POST_TASK, ClientMessage.PostTask.serializer(), message)

    is ClientMessage.AgentStatus ->
        envelope(ClientMessageType.AGENT_STATUS, ClientMessage.AgentStatus.serializer(), message)

    is ClientMessage.RunControl ->
        envelope(ClientMessageType.RUN_CONTROL, ClientMessage.RunControl.serializer(), message)

    else -> wrongGroup(message)
}

private fun encodeAgent(message: HostMessage): ByteArray = when (message) {
    is HostMessage.TaskPosted ->
        envelope(HostMessageType.TASK_POSTED, HostMessage.TaskPosted.serializer(), message)

    is HostMessage.AgentSnapshot ->
        envelope(HostMessageType.AGENT_SNAPSHOT, HostMessage.AgentSnapshot.serializer(), message)

    is HostMessage.RunControlled ->
        envelope(HostMessageType.RUN_CONTROLLED, HostMessage.RunControlled.serializer(), message)

    else -> wrongGroup(message)
}

private fun decodeAgentClient(env: WireEnvelope): DecodeResult<ClientMessage>? = when (env.type) {
    ClientMessageType.POST_TASK -> decode(env, ClientMessage.PostTask.serializer())
    ClientMessageType.AGENT_STATUS -> decode(env, ClientMessage.AgentStatus.serializer())
    ClientMessageType.RUN_CONTROL -> decode(env, ClientMessage.RunControl.serializer())
    else -> null
}

private fun decodeAgentHost(env: WireEnvelope): DecodeResult<HostMessage>? = when (env.type) {
    HostMessageType.TASK_POSTED -> decode(env, HostMessage.TaskPosted.serializer())
    HostMessageType.AGENT_SNAPSHOT -> decode(env, HostMessage.AgentSnapshot.serializer())
    HostMessageType.RUN_CONTROLLED -> decode(env, HostMessage.RunControlled.serializer())
    else -> null
}

// ——— Модели: конфигурация, сохранение, проверка доступа ———

private fun encodeModelConfig(message: ClientMessage): ByteArray = when (message) {
    is ClientMessage.AgentConfigRequest -> envelope(
        ClientMessageType.AGENT_CONFIG_REQUEST,
        ClientMessage.AgentConfigRequest.serializer(),
        message,
    )

    is ClientMessage.SaveAgentConfig ->
        envelope(ClientMessageType.SAVE_AGENT_CONFIG, ClientMessage.SaveAgentConfig.serializer(), message)

    is ClientMessage.CheckModel ->
        envelope(ClientMessageType.CHECK_MODEL, ClientMessage.CheckModel.serializer(), message)

    else -> wrongGroup(message)
}

private fun encodeModelConfig(message: HostMessage): ByteArray = when (message) {
    is HostMessage.AgentConfigSnapshot -> envelope(
        HostMessageType.AGENT_CONFIG_SNAPSHOT,
        HostMessage.AgentConfigSnapshot.serializer(),
        message,
    )

    is HostMessage.AgentConfigSaved ->
        envelope(HostMessageType.AGENT_CONFIG_SAVED, HostMessage.AgentConfigSaved.serializer(), message)

    is HostMessage.ModelCheckResult ->
        envelope(HostMessageType.MODEL_CHECK_RESULT, HostMessage.ModelCheckResult.serializer(), message)

    else -> wrongGroup(message)
}

private fun decodeModelConfigClient(env: WireEnvelope): DecodeResult<ClientMessage>? = when (env.type) {
    ClientMessageType.AGENT_CONFIG_REQUEST -> decode(env, ClientMessage.AgentConfigRequest.serializer())
    ClientMessageType.SAVE_AGENT_CONFIG -> decode(env, ClientMessage.SaveAgentConfig.serializer())
    ClientMessageType.CHECK_MODEL -> decode(env, ClientMessage.CheckModel.serializer())
    else -> null
}

private fun decodeModelConfigHost(env: WireEnvelope): DecodeResult<HostMessage>? = when (env.type) {
    HostMessageType.AGENT_CONFIG_SNAPSHOT -> decode(env, HostMessage.AgentConfigSnapshot.serializer())
    HostMessageType.AGENT_CONFIG_SAVED -> decode(env, HostMessage.AgentConfigSaved.serializer())
    HostMessageType.MODEL_CHECK_RESULT -> decode(env, HostMessage.ModelCheckResult.serializer())
    else -> null
}

// ——— Ключи провайдеров: запись, удаление, состояние (T-1.58) ———
//
// Отдельная группа, а не строки в группе моделей: у ключей свой повод меняться — они
// единственные данные, которые уходят к хосту и никогда не возвращаются обратно.

private fun encodeModelSecrets(message: ClientMessage): ByteArray = when (message) {
    is ClientMessage.SetModelSecret ->
        envelope(ClientMessageType.SET_MODEL_SECRET, ClientMessage.SetModelSecret.serializer(), message)

    is ClientMessage.DeleteModelSecret ->
        envelope(ClientMessageType.DELETE_MODEL_SECRET, ClientMessage.DeleteModelSecret.serializer(), message)

    is ClientMessage.ModelSecrets ->
        envelope(ClientMessageType.MODEL_SECRETS, ClientMessage.ModelSecrets.serializer(), message)

    else -> wrongGroup(message)
}

private fun encodeModelSecrets(message: HostMessage): ByteArray = when (message) {
    is HostMessage.ModelSecretChanged ->
        envelope(HostMessageType.MODEL_SECRET_CHANGED, HostMessage.ModelSecretChanged.serializer(), message)

    is HostMessage.ModelSecretsSnapshot ->
        envelope(HostMessageType.MODEL_SECRETS_SNAPSHOT, HostMessage.ModelSecretsSnapshot.serializer(), message)

    else -> wrongGroup(message)
}

private fun decodeModelSecretsClient(env: WireEnvelope): DecodeResult<ClientMessage>? = when (env.type) {
    ClientMessageType.SET_MODEL_SECRET -> decode(env, ClientMessage.SetModelSecret.serializer())
    ClientMessageType.DELETE_MODEL_SECRET -> decode(env, ClientMessage.DeleteModelSecret.serializer())
    ClientMessageType.MODEL_SECRETS -> decode(env, ClientMessage.ModelSecrets.serializer())
    else -> null
}

private fun decodeModelSecretsHost(env: WireEnvelope): DecodeResult<HostMessage>? = when (env.type) {
    HostMessageType.MODEL_SECRET_CHANGED -> decode(env, HostMessage.ModelSecretChanged.serializer())
    HostMessageType.MODEL_SECRETS_SNAPSHOT -> decode(env, HostMessage.ModelSecretsSnapshot.serializer())
    else -> null
}
