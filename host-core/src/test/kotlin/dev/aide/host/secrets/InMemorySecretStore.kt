package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason

/**
 * Хранилище-заглушка для тестов хоста (T-1.58).
 *
 * Повторяет контракт настоящих реализаций: пока хранилище доступно — операции работают
 * с картой в памяти, при недоступном — бросают. Смягчать контракт нельзя: иначе тест
 * «хранилище недоступно» проверял бы заглушку, а не поведение хоста.
 */
internal class InMemorySecretStore(
    initial: Map<String, String> = emptyMap(),
    private val availability: SecretStoreAvailability = SecretStoreAvailability.Available,
) : SecretStore {

    private val values = initial.toMutableMap()

    /** Записи хранилища: по ним проверяется и запись через протокол, и удаление. */
    val stored: Map<String, String> get() = values.toMap()

    override fun availability(): SecretStoreAvailability = availability

    override fun get(name: String): String? {
        requireAvailable()
        return values[name]
    }

    override fun put(name: String, value: String) {
        requireAvailable()
        values[name] = value
    }

    override fun delete(name: String) {
        requireAvailable()
        values.remove(name)
    }

    private fun requireAvailable(): Unit = when (availability) {
        is SecretStoreAvailability.Available -> Unit
        is SecretStoreAvailability.Unavailable -> throw SecretStoreUnavailableException(availability.reason)
    }

    companion object {

        /** Недоступное хранилище с причиной: так проверяется явный отказ с объяснением. */
        fun unavailable(reason: SecretStoreUnavailableReason): InMemorySecretStore =
            InMemorySecretStore(availability = SecretStoreAvailability.Unavailable(reason))
    }
}
