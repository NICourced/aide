package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStore
import dev.aide.domain.SecretStoreUnavailableReason
import java.nio.file.Path

/**
 * Выбор защищённого хранилища по платформе хоста (T-1.58).
 *
 * Обычный класс с фабричным методом, а не `expect class`: `expect` финален и не
 * подставляется фейком в тестах, поэтому выбор платформы проверялся бы только на той
 * платформе, где идёт сборка, а третья ветка — «платформы нет» — не проверялась бы вовсе.
 *
 * @param osName имя операционной системы; по умолчанию берётся у JVM, в тестах задаётся —
 *   иначе ветка Windows не выполнилась бы ни разу за всю жизнь сборки.
 * @param blobPath путь блоба DPAPI; нужен только ветке Windows и лежит рядом с базой.
 */
class SecretStoreFactory(
    private val blobPath: Path,
    private val osName: String = System.getProperty(OS_NAME_PROPERTY).orEmpty(),
) {

    /** Собирает хранилище для этой платформы: Linux — libsecret, Windows — DPAPI, иначе отказ. */
    fun create(): SecretStore = when {
        osName.startsWith("Linux", ignoreCase = true) -> LinuxSecretStore()
        osName.startsWith("Windows", ignoreCase = true) ->
            WindowsDpapiSecretStore(blobPath, DpapiSecretCipher())

        else -> UnavailableSecretStore(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS)
    }

    private companion object {
        /** Системное свойство с именем ОС; переопределяется тестом, а не конфигурацией. */
        const val OS_NAME_PROPERTY = "os.name"
    }
}
