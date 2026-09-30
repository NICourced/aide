package dev.aide.client.ui.strings

import androidx.compose.runtime.Composable
import dev.aide.client.state.ScreenState
import dev.aide.protocol.IncompatibilityReason
import org.jetbrains.compose.resources.StringResource

/** Как показать текст состояния: ресурс с подстановками либо уже готовый текст. */
sealed interface StateMessage {

    /** Текст берётся из ресурса; [arguments] подставляются по порядку. */
    data class Resource(val resource: StringResource, val arguments: List<String>) : StateMessage

    /** Готовый текст без ресурса. */
    data class Literal(val text: String) : StateMessage
}

/**
 * Сопоставляет вид ошибки и её параметры с ресурсной строкой (NFR-13).
 *
 * Единственное исключение из NFR-13 — [ScreenState.ErrorKind.OTHER]: такую ошибку
 * классифицировать нельзя (внутренняя ошибка хоста, нереализованная возможность),
 * поэтому текст уже готов и приходит от хоста — он показывается как есть.
 */
fun failureMessage(kind: ScreenState.ErrorKind, arguments: List<String>): StateMessage = when (kind) {
    ScreenState.ErrorKind.PATH_MISSING ->
        StateMessage.Resource(Strings.stateErrorPathMissing, arguments)

    ScreenState.ErrorKind.NOT_A_REPOSITORY ->
        StateMessage.Resource(Strings.stateErrorNotARepo, arguments)

    ScreenState.ErrorKind.WORKSPACE_CLOSED ->
        StateMessage.Resource(Strings.stateErrorWorkspaceClosed, emptyList())

    ScreenState.ErrorKind.INCOMPATIBLE ->
        StateMessage.Resource(incompatibleResource(arguments.firstOrNull()), arguments.drop(1).take(2))

    ScreenState.ErrorKind.CONFIG_REJECTED ->
        StateMessage.Resource(Strings.stateErrorConfigRejected, emptyList())

    ScreenState.ErrorKind.OTHER ->
        StateMessage.Literal(arguments.firstOrNull().orEmpty())
}

/** Текст о несовместимости версий; общий для шапки и состояния экрана. */
fun incompatibleMessage(
    reason: IncompatibilityReason,
    clientVersion: String,
    hostVersion: String,
): StateMessage = StateMessage.Resource(incompatibleResource(reason.name), listOf(clientVersion, hostVersion))

/** Читает текст сообщения состояния в composable-контексте. */
@Suppress("SpreadOperator") // Передача подстановок вариадической stringResource требует spread.
@Composable
fun stateMessageText(message: StateMessage): String = when (message) {
    is StateMessage.Resource -> Strings.text(message.resource, *message.arguments.toTypedArray())
    is StateMessage.Literal -> message.text
}

private fun incompatibleResource(reasonName: String?): StringResource =
    when (IncompatibilityReason.entries.firstOrNull { it.name == reasonName }) {
        IncompatibilityReason.HOST_OUTDATED -> Strings.connectionIncompatibleHostOutdated
        IncompatibilityReason.MALFORMED_HELLO -> Strings.connectionIncompatibleMalformed
        IncompatibilityReason.CLIENT_OUTDATED, null -> Strings.connectionIncompatibleClientOutdated
    }
