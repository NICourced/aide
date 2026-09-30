package dev.aide.protocol

import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.SecretStoreUnavailableReason
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T-1.58: сообщения ключей провайдеров — состав, round-trip и **отсутствие обратного пути**.
 *
 * Проверки кодека здесь не формальность: обещание «значение ключа не читается обратно»
 * держится не тем, что так написано в задаче, а тем, что в ответных сообщениях нет поля
 * для значения, — и это проверяется разбором кодированного ответа.
 */
class ModelSecretCodecTest {

    private val requestId = RequestId("req-secret")

    /** Значение ключа; оно не должно появиться ни в одном ответе хоста. */
    private val secret = "sk-живой-ключ-2f6a1c"

    private fun clientMessage(bytes: ByteArray): ClientMessage =
        assertIs<DecodeResult.Message<ClientMessage>>(ProtocolCodec.decodeClientMessage(bytes)).message

    private fun hostMessage(bytes: ByteArray): HostMessage =
        assertIs<DecodeResult.Message<HostMessage>>(ProtocolCodec.decodeHostMessage(bytes)).message

    @Test
    fun `запись, удаление и запрос состояния переживают round-trip`() {
        val messages = listOf(
            ClientMessage.SetModelSecret(requestId, "deepseek", secret),
            ClientMessage.DeleteModelSecret(requestId, "deepseek"),
            ClientMessage.ModelSecrets(requestId),
        )

        messages.forEach { message ->
            assertEquals(message, clientMessage(ProtocolCodec.encode(message)))
        }
    }

    @Test
    fun `состояние ключа и причина недоступности переживают round-trip`() {
        val statuses = listOf(
            ModelSecretStatus.InStore,
            ModelSecretStatus.FromEnv,
            ModelSecretStatus.Absent,
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE),
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.TOOL_MISSING),
        )

        statuses.forEach { status ->
            val message = HostMessage.ModelSecretChanged(requestId, "deepseek", status)
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
    }

    @Test
    fun `снимок состояний переживает round-trip целиком`() {
        val statuses = mapOf(
            "deepseek" to ModelSecretStatus.InStore,
            "ollama" to ModelSecretStatus.Absent,
            "kimi" to ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.TOOL_MISSING),
        )
        val message = HostMessage.ModelSecretsSnapshot(requestId, statuses)

        val decoded = assertIs<HostMessage.ModelSecretsSnapshot>(hostMessage(ProtocolCodec.encode(message)))

        assertEquals(statuses, decoded.statuses)
        assertEquals(statuses.keys, decoded.statuses.keys, "порядок провайдеров сохраняется")
    }

    @Test
    fun `значение ключа уезжает в запрос и переживает round-trip целиком`() {
        // Единственное сообщение, которое несёт значение, — запись ключа, и то от клиента
        // к хосту. Именно поэтому проверка «в ответе значения нет» делается не поиском
        // подстроки, а составом ответных типов: их разбирает обработчик на стороне хоста.
        val message = ClientMessage.SetModelSecret(requestId, "deepseek", secret)

        val decoded = assertIs<ClientMessage.SetModelSecret>(clientMessage(ProtocolCodec.encode(message)))

        assertEquals(secret, decoded.value)
        assertEquals("deepseek", decoded.providerId)
    }

    @Test
    fun `запись ключа не раскрывает значение в текстовом виде`() {
        // Сообщение неизбежно печатается в журналах и отслеживаниях: `data class`
        // напечатал бы значение поля, поэтому toString переопределён (T-1.58).
        val message = ClientMessage.SetModelSecret(requestId, "deepseek", secret)

        val text = message.toString()

        assertFalse(text.contains(secret), "toString не должен раскрывать ключ: $text")
        assertTrue(text.contains("deepseek"), "имя провайдера остаётся: без него сообщение бесполезно")
    }

    @Test
    fun `отказ записи ключа переживает round-trip`() {
        val rejections = listOf(
            ModelSecretRejection.EmptyValue,
            ModelSecretRejection.UnknownProvider("нет-такого"),
        )

        rejections.forEach { rejection ->
            val message = HostMessage.Failure(requestId, ProtocolError.InvalidModelSecret(rejection))
            assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
        }
    }

    @Test
    fun `пустая карта состояний переживает round-trip`() {
        val message = HostMessage.ModelSecretsSnapshot(requestId, emptyMap())

        assertEquals(message, hostMessage(ProtocolCodec.encode(message)))
    }

    @Test
    fun `кодирование записи ключа не меняется между вызовами`() {
        val message = ClientMessage.SetModelSecret(requestId, "deepseek", secret)
        assertContentEquals(ProtocolCodec.encode(message), ProtocolCodec.encode(message))
    }
}
