package dev.aide.client.ui

import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.StateMessage
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.failureMessage
import dev.aide.client.ui.strings.incompatibleMessage
import dev.aide.protocol.IncompatibilityReason
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * NFR-13: текст состояния собирается в UI из ресурсов, а слои ниже передают только
 * код и параметры. Единственное исключение — [ScreenState.ErrorKind.OTHER].
 */
class StateMessagesTest {

    @Test
    fun `путь и не-git-каталог берут текст из ресурса`() {
        assertEquals(
            StateMessage.Resource(Strings.stateErrorPathMissing, listOf("/nope")),
            failureMessage(ScreenState.ErrorKind.PATH_MISSING, listOf("/nope")),
        )
        assertEquals(
            StateMessage.Resource(Strings.stateErrorNotARepo, listOf("/tmp/x")),
            failureMessage(ScreenState.ErrorKind.NOT_A_REPOSITORY, listOf("/tmp/x")),
        )
    }

    @Test
    fun `закрытый воркспейс берёт текст из ресурса без параметров`() {
        assertEquals(
            StateMessage.Resource(Strings.stateErrorWorkspaceClosed, emptyList()),
            failureMessage(ScreenState.ErrorKind.WORKSPACE_CLOSED, emptyList()),
        )
    }

    @Test
    fun `несовместимость выбирает ресурс по причине и несёт обе версии`() {
        assertEquals(
            StateMessage.Resource(Strings.connectionIncompatibleClientOutdated, listOf("1.0", "2.0")),
            failureMessage(
                ScreenState.ErrorKind.INCOMPATIBLE,
                listOf(IncompatibilityReason.CLIENT_OUTDATED.name, "1.0", "2.0"),
            ),
        )
        assertEquals(
            StateMessage.Resource(Strings.connectionIncompatibleHostOutdated, listOf("1.5", "1.2")),
            failureMessage(
                ScreenState.ErrorKind.INCOMPATIBLE,
                listOf(IncompatibilityReason.HOST_OUTDATED.name, "1.5", "1.2"),
            ),
        )
    }

    @Test
    fun `текст о несовместимости для шапки совпадает с состоянием экрана`() {
        assertEquals(
            failureMessage(
                ScreenState.ErrorKind.INCOMPATIBLE,
                listOf(IncompatibilityReason.CLIENT_OUTDATED.name, "1.0", "2.0"),
            ),
            incompatibleMessage(IncompatibilityReason.CLIENT_OUTDATED, "1.0", "2.0"),
        )
    }

    @Test
    fun `вид OTHER показывает текст хоста как есть, без ресурса`() {
        assertEquals(
            StateMessage.Literal("внутренняя ошибка хоста"),
            failureMessage(ScreenState.ErrorKind.OTHER, listOf("внутренняя ошибка хоста")),
        )
    }
}
