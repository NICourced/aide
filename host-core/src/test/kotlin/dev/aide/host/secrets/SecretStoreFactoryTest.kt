package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * T-1.58: выбор хранилища по платформе.
 *
 * Имя платформы задаётся тестом: иначе ветка Windows не выполнилась бы ни разу за всю
 * жизнь сборки (CI — Linux), а ветка «платформы нет» — никогда. Проверяется именно выбор
 * класса: поведение каждого выбора покрыто своими тестами.
 */
class SecretStoreFactoryTest {

    private val blobPath = Files.createTempDirectory("aide-secret-factory").resolve("model-secrets.bin")

    @Test
    fun `на Linux выбирается хранилище libsecret`() {
        assertIs<LinuxSecretStore>(SecretStoreFactory(blobPath, osName = "Linux").create())
    }

    @Test
    fun `на Windows выбирается хранилище DPAPI`() {
        // Имя ОС как его отдаёт JVM: «Windows 11», «Windows Server 2022».
        assertIs<WindowsDpapiSecretStore>(SecretStoreFactory(blobPath, osName = "Windows 11").create())
    }

    @Test
    fun `на неподдержанной платформе выбирается явный отказ`() {
        val store = SecretStoreFactory(blobPath, osName = "Mac OS X").create()

        assertEquals(
            SecretStoreAvailability.Unavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
            store.availability(),
        )
        assertFailsWith<SecretStoreUnavailableException> { store.put("stub", "ключ") }
        assertTrue(!Files.exists(blobPath), "отказ не создаёт блоб рядом с базой")
    }

    @Test
    fun `по умолчанию платформа берётся у JVM, а не оставлена пустой`() {
        // Пустое имя платформы в приложении означало бы отказ «платформа не поддержана»
        // на живом хосте: значение по умолчанию обязано читать систему.
        val fromJvm = SecretStoreFactory(blobPath, osName = System.getProperty("os.name").orEmpty()).create()

        assertEquals(fromJvm::class, SecretStoreFactory(blobPath).create()::class)
    }
}
