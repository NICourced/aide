package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * T-1.58: платформа без защищённого хранилища получает явный отказ, а не тихую запись.
 *
 * Это и есть вторая половина критерия: «на платформе без поддержки — явный отказ с понятной
 * причиной, а не тихое сохранение в файл». Проверяется не обещание, а поведение: любая
 * операция отказывает, и в каталоге данных не появляется ни одного файла.
 */
class UnavailableSecretStoreTest {

    private val reason = SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS
    private val store = UnavailableSecretStore(reason)

    @Test
    fun `доступность сообщает причину отказа`() {
        assertEquals(SecretStoreAvailability.Unavailable(reason), store.availability())
    }

    @Test
    fun `чтение отказывает, а не отвечает «ключа нет»`() {
        // null читался бы как «ключа нет» и разрешал молча искать дальше; хранилище,
        // которого нет, обязано сказать именно это.
        val error = assertFailsWith<SecretStoreUnavailableException> { store.get("stub") }

        assertEquals(reason, error.reason)
    }

    @Test
    fun `запись отказывает с причиной`() {
        val error = assertFailsWith<SecretStoreUnavailableException> { store.put("stub", "ключ") }

        assertEquals(reason, error.reason)
    }

    @Test
    fun `удаление отказывает с причиной`() {
        val error = assertFailsWith<SecretStoreUnavailableException> { store.delete("stub") }

        assertEquals(reason, error.reason)
    }
}
