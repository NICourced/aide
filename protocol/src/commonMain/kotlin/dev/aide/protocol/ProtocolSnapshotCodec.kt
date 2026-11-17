// CBOR и бинарные encodeToByteArray/decodeFromByteArray помечены в kotlinx.serialization
// экспериментальными; opt-in нужен и объявлениям уровня файла (cbor, envelope, decode),
// которым не достаётся аннотация объекта.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.aide.protocol

import kotlinx.serialization.ExperimentalSerializationApi

// ——— Снапшоты: список, предпросмотр и откат (T-1.6) ———
//
// Вынесено из ProtocolCodec.kt: вместе с остальными группами снапшоты превышали
// порог TooManyFunctions файла (25). Отдельный файл сохраняет разбиение по поводу.

internal fun encodeSnapshots(message: ClientMessage): ByteArray = when (message) {
    is ClientMessage.SnapshotList ->
        envelope(ClientMessageType.SNAPSHOT_LIST, ClientMessage.SnapshotList.serializer(), message)

    is ClientMessage.SnapshotPreviewRequest -> envelope(
        ClientMessageType.SNAPSHOT_PREVIEW_REQUEST,
        ClientMessage.SnapshotPreviewRequest.serializer(),
        message,
    )

    is ClientMessage.SnapshotRevert ->
        envelope(ClientMessageType.SNAPSHOT_REVERT, ClientMessage.SnapshotRevert.serializer(), message)

    else -> wrongGroup(message)
}

internal fun encodeSnapshots(message: HostMessage): ByteArray = when (message) {
    is HostMessage.SnapshotListResponse -> envelope(
        HostMessageType.SNAPSHOT_LIST_RESPONSE,
        HostMessage.SnapshotListResponse.serializer(),
        message,
    )

    is HostMessage.SnapshotPreviewResponse -> envelope(
        HostMessageType.SNAPSHOT_PREVIEW_RESPONSE,
        HostMessage.SnapshotPreviewResponse.serializer(),
        message,
    )

    is HostMessage.SnapshotReverted ->
        envelope(HostMessageType.SNAPSHOT_REVERTED, HostMessage.SnapshotReverted.serializer(), message)

    else -> wrongGroup(message)
}

internal fun decodeSnapshotsClient(env: WireEnvelope): DecodeResult<ClientMessage>? = when (env.type) {
    ClientMessageType.SNAPSHOT_LIST -> decode(env, ClientMessage.SnapshotList.serializer())
    ClientMessageType.SNAPSHOT_PREVIEW_REQUEST -> decode(env, ClientMessage.SnapshotPreviewRequest.serializer())
    ClientMessageType.SNAPSHOT_REVERT -> decode(env, ClientMessage.SnapshotRevert.serializer())
    else -> null
}

internal fun decodeSnapshotsHost(env: WireEnvelope): DecodeResult<HostMessage>? = when (env.type) {
    HostMessageType.SNAPSHOT_LIST_RESPONSE -> decode(env, HostMessage.SnapshotListResponse.serializer())
    HostMessageType.SNAPSHOT_PREVIEW_RESPONSE -> decode(env, HostMessage.SnapshotPreviewResponse.serializer())
    HostMessageType.SNAPSHOT_REVERTED -> decode(env, HostMessage.SnapshotReverted.serializer())
    else -> null
}
