package dev.aide.agent.provider

import dev.aide.domain.SecretStoreUnavailableReason

/**
 * Хранилище-заглушка для тестов (T-1.58).
 *
 * Повторяет контракт настоящих реализаций, а не смягчает его: пока хранилище доступно,
 * операции работают с картой в памяти; при недоступном — бросают, как и платформенные
 * классы. Иначе тест «хранилище недоступно» проверял бы заглушку, а не поведение.
 */
internal class FakeSecretStore(
    initial: Map<String, String> = emptyMap(),
    private val availability: SecretStoreAvailability = SecretStoreAvailability.Available,
) : SecretStore {

    private val values = initial.toMutableMap()

    /** Записи, лежащие в хранилище: по ним проверяется и запись, и удаление. */
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

    /** Недоступное хранилище отказывает на любой операции — как и настоящие реализации. */
    private fun requireAvailable(): Unit = when (availability) {
        is SecretStoreAvailability.Available -> Unit
        is SecretStoreAvailability.Unavailable -> throw SecretStoreUnavailableException(availability.reason)
    }

    companion object {
        /** Недоступное хранилище с заданной причиной: так проверяется явный отказ. */
        fun unavailable(reason: SecretStoreUnavailableReason): FakeSecretStore =
            FakeSecretStore(availability = SecretStoreAvailability.Unavailable(reason))
    }
}
