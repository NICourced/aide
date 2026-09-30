package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason

/**
 * Явный отказ вместо хранилища (T-1.58).
 *
 * Платформа, для которой в сборке нет защищённого хранилища (всё, кроме Linux и Windows),
 * получает эту реализацию: сохранить ключ нельзя, и хост говорит об этом причиной, а не
 * пишет ключ в файл рядом с настройками. Ни одного пути к файлу у класса нет вовсе —
 * «тихое сохранение» здесь невозможно не по договорённости, а потому что некуда.
 */
class UnavailableSecretStore(private val reason: SecretStoreUnavailableReason) : SecretStore {

    override fun availability(): SecretStoreAvailability = SecretStoreAvailability.Unavailable(reason)

    // Отказ, а не null: null означал бы «ключа нет», то есть разрешение искать дальше молча.
    override fun get(name: String): String? = throw SecretStoreUnavailableException(reason)

    override fun put(name: String, value: String): Nothing = throw SecretStoreUnavailableException(reason)

    override fun delete(name: String): Nothing = throw SecretStoreUnavailableException(reason)
}
