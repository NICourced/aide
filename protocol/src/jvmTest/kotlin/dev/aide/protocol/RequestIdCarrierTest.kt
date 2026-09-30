package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Носители идентификатора запроса (T-1.58).
 *
 * Идентификатор есть у всех сообщений, кроме трёх: приветствий клиента и хоста,
 * несовместимости версий (соединение закрывается) и события хоста (приходит без запроса).
 * Разбор «несёт ли сообщение идентификатор» раньше был таблицей `when` в трёх местах и рос
 * вместе с протоколом; теперь идентификатор объявлен интерфейсом [RequestIdCarrier].
 *
 * Перебор идёт по **вариантам, объявленным компилятором** (`permittedSubclasses`), а не по
 * списку, набранному руками: сообщение, забывшее интерфейс, обязано покраснеть само, без
 * правки теста. Руками задан только список исключений — и его устаревание тоже проверяется.
 */
class RequestIdCarrierTest {

    @Test
    fun `каждый вариант сам объявляет, несёт ли он идентификатор запроса`() {
        assertCarriers(ClientMessage::class.java, CLIENT_WITHOUT_REQUEST)
        assertCarriers(HostMessage::class.java, HOST_WITHOUT_REQUEST)
    }

    @Test
    fun `сообщения без запроса идентификатора не отдают`() {
        assertNull(ClientMessage.Hello(ProtocolVersion.CURRENT).requestIdOrNull, "приветствие — не запрос")
        assertNull(
            HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s-1")).requestIdOrNull,
            "приветствие хоста — не ответ",
        )
        assertNull(
            HostMessage.Incompatible(IncompatibilityReason.HOST_OUTDATED, ProtocolVersion(2, 0)).requestIdOrNull,
            "несовместимость версий закрывает соединение, а не отвечает на запрос",
        )
        assertNull(HostMessage.Event(HostEvent.HostShuttingDown).requestIdOrNull, "событие приходит без запроса")
    }

    @Test
    fun `сообщения с запросом отдают именно свой идентификатор`() {
        val requestId = RequestId("req-1")

        assertEquals(requestId, ClientMessage.ModelSecrets(requestId).requestIdOrNull)
        assertEquals(requestId, HostMessage.RunControlled(requestId, dev.aide.domain.RunId("r-1")).requestIdOrNull)
    }

    /**
     * Сверяет объявленные варианты с интерфейсом-носителем.
     *
     * Три утверждения, каждое из которых ловит свой отказ: вариантов вообще нет (перебор
     * бесполезен), исключение исчезло из типов (список устарел), вариант несёт интерфейс
     * не так, как ожидает список исключений (новая неувязка в протоколе).
     */
    private fun assertCarriers(messageType: Class<*>, withoutRequest: Set<String>) {
        val variants = messageType.permittedSubclasses
        assertTrue(
            !variants.isNullOrEmpty(),
            "${messageType.simpleName} обязан быть запечатанным: иначе перебирать нечего",
        )
        val names = variants.map { it.simpleName }.toSet()
        withoutRequest.forEach { name ->
            assertTrue(
                name in names,
                "«$name» больше не вариант ${messageType.simpleName}: список исключений устарел",
            )
        }
        assertTrue(
            variants.any { it.simpleName !in withoutRequest },
            "проверка пуста: у ${messageType.simpleName} не осталось ни одного носителя",
        )

        variants.forEach { variant ->
            assertEquals(
                variant.simpleName !in withoutRequest,
                RequestIdCarrier::class.java.isAssignableFrom(variant),
                "${variant.simpleName}: носитель идентификатора объявляется интерфейсом RequestIdCarrier",
            )
        }
    }

    private companion object {

        /** Единственное сообщение клиента без запроса — приветствие. */
        val CLIENT_WITHOUT_REQUEST: Set<String> = setOf("Hello")

        /** Сообщения хоста, которые не отвечают на запрос: приветствие, версии, событие. */
        val HOST_WITHOUT_REQUEST: Set<String> = setOf("Hello", "Incompatible", "Event")
    }
}
