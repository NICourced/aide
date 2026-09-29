package dev.aide.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Идентификатор запроса. Повтор запроса с тем же значением не создаёт вторую операцию (§ 8.4). */
@Serializable
@JvmInline
value class RequestId(val value: String)

/** Идентификатор открытого на хосте воркспейса; непрозрачный для клиента. */
@Serializable
@JvmInline
value class WorkspaceId(val value: String)

/** Идентификатор сессии клиента на хосте. Меняется при полном переподключении. */
@Serializable
@JvmInline
value class SessionId(val value: String)
