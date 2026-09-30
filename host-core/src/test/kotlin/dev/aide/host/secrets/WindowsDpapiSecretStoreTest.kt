package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.58: хранилище Windows — блоб рядом с базой и ничего открытым текстом.
 *
 * Шифр подставлен тестовым: DPAPI работает только на Windows, и вызывать его здесь нечем,
 * а проверить нужно именно устройство хранилища — формат блоба, атомарность, поведение
 * при битом файле и то, что ключа нет в файле открытым текстом. Настоящий DPAPI-вызов
 * (JNA, `Crypt32Util`) на этой платформе не исполняется: это остаётся непроверенным здесь
 * и подтверждается только на Windows.
 */
class WindowsDpapiSecretStoreTest {

    /** Значение ключа, которого не должно быть в файле. */
    private val secret = "sk-windows-2f6a1c"

    private val cipher = XorCipher()

    private fun store(path: Path): WindowsDpapiSecretStore = WindowsDpapiSecretStore(path, cipher)

    private fun blobPath(): Path = Files.createTempDirectory("aide-dpapi").resolve("model-secrets.bin")

    @Test
    fun `ключ переживает запись и чтение`() {
        val path = blobPath()

        store(path).put("deepseek", secret)

        assertEquals(secret, store(path).get("deepseek"), "ключ обязан читаться из того же блоба")
    }

    @Test
    fun `в файле нет значения ключа открытым текстом`() {
        val path = blobPath()

        store(path).put("deepseek", secret)

        val bytes = Files.readAllBytes(path)
        assertFalse(
            bytes.toString(StandardCharsets.UTF_8).contains(secret),
            "значение ключа не должно лежать в файле открытым текстом",
        )
        // Блоб не пуст и не равен открытому тексту: проверка не проходит по пустоте.
        assertTrue(bytes.isNotEmpty(), "блоб обязан быть записан")
        assertFalse(bytes.contentEquals(secret.toByteArray(StandardCharsets.UTF_8)))
    }

    @Test
    fun `доступность не зависит от наличия файла`() {
        val path = blobPath()

        assertEquals(SecretStoreAvailability.Available, store(path).availability())
        assertNull(store(path).get("deepseek"), "нет файла — нет и ключей")
        assertFalse(Files.exists(path), "чтение не создаёт блоб")
    }

    @Test
    fun `удаление убирает ключ, а не весь блоб`() {
        val path = blobPath()
        store(path).put("deepseek", secret)
        store(path).put("kimi", "sk-kimi")

        store(path).delete("deepseek")

        assertNull(store(path).get("deepseek"))
        assertEquals("sk-kimi", store(path).get("kimi"), "соседний ключ обязан остаться")
    }

    @Test
    fun `удаление отсутствующего ключа не портит блоб`() {
        val path = blobPath()
        store(path).put("deepseek", secret)

        store(path).delete("нет-такого")

        assertEquals(secret, store(path).get("deepseek"))
    }

    @Test
    fun `битый блоб — отказ, а не «ключей нет»`() {
        // Иначе чужие или испорченные ключи молча превратились бы в «ключ не задан»,
        // и пользователь искал бы причину не там.
        val path = blobPath()
        Files.write(path, byteArrayOf(1, 2, 3, 4))

        val error = assertFailsWith<SecretStoreUnavailableException> { store(path).get("deepseek") }

        assertEquals(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE, error.reason)
    }

    @Test
    fun `блоб, зашифрованный другой учётной записью, даёт отказ`() {
        // Смена учётной записи — тот же случай, что и битый блоб: DPAPI не расшифрует.
        val path = blobPath()
        store(path).put("deepseek", secret)
        val stranger = WindowsDpapiSecretStore(path, FailingCipher())

        assertFailsWith<SecretStoreUnavailableException> { stranger.get("deepseek") }
    }

    /** Обратимое, но настоящее преобразование: значение в файл не попадает как есть. */
    private class XorCipher : SecretCipher {

        override fun encrypt(plain: ByteArray): ByteArray = plain.map { (it.toInt() xor KEY).toByte() }.toByteArray()

        override fun decrypt(blob: ByteArray): ByteArray = encrypt(blob)

        private companion object {
            const val KEY = 0x5A
        }
    }

    /** Шифр чужой учётной записи: расшифровать не может — как DPAPI под другим пользователем. */
    private class FailingCipher : SecretCipher {

        override fun encrypt(plain: ByteArray): ByteArray = plain

        override fun decrypt(blob: ByteArray): ByteArray = error("чужая учётная запись")
    }
}
