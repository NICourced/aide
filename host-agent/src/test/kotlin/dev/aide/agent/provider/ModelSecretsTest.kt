package dev.aide.agent.provider

import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.SecretStoreUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.58: ключ провайдера — приоритет источников, состояние, запись и удаление.
 *
 * Ни файла, ни сети: хранилище подставлено заглушкой, окружение — функцией. Настоящие
 * реализации (libsecret, DPAPI) проверяются отдельно — там, где они есть.
 */
class ModelSecretsTest {

    private val store = FakeSecretStore()

    private fun provider(apiKeyEnv: String? = "AIDE_TEST_KEY", id: String = "stub"): ProviderProfile =
        ProviderProfile(
            id = id,
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:9/v1",
            apiKeyEnv = apiKeyEnv,
        )

    private fun secrets(
        env: (String) -> String? = { name -> if (name == "AIDE_TEST_KEY") "ключ-из-окружения" else null },
        backing: FakeSecretStore = store,
    ): ModelSecrets = ModelSecrets(store = backing, env = env)

    @Test
    fun `ключ берётся из хранилища, когда он там есть`() {
        store.put("stub", "ключ-из-хранилища")

        assertEquals("ключ-из-хранилища", secrets().resolve(provider()))
    }

    @Test
    fun `хранилище идёт перед переменной окружения`() {
        // Приоритет — решение задачи: сохранённый из приложения ключ обязан побеждать
        // переменную окружения, иначе «удалить ключ» не вернуло бы прогон к окружению.
        store.put("stub", "ключ-из-хранилища")

        assertEquals("ключ-из-хранилища", secrets().resolve(provider()))
        assertEquals(ModelSecretStatus.InStore, secrets().status(provider()))
    }

    @Test
    fun `без ключа в хранилище берётся переменная окружения`() {
        assertEquals("ключ-из-окружения", secrets().resolve(provider()))
        assertEquals(ModelSecretStatus.FromEnv, secrets().status(provider()))
    }

    @Test
    fun `пустая переменная окружения считается отсутствующей`() {
        // Пустое значение — та же беда, что и незаданная переменная: ключом его не сделать.
        assertNull(secrets(env = { "   " }).resolve(provider()))
        assertEquals(ModelSecretStatus.Absent, secrets(env = { "   " }).status(provider()))
    }

    @Test
    fun `ключ не нужен провайдеру без имени переменной`() {
        assertNull(secrets().resolve(provider(apiKeyEnv = null)))
        assertEquals(ModelSecretStatus.Absent, secrets().status(provider(apiKeyEnv = null)))
    }

    @Test
    fun `ни хранилища, ни окружения — ключа нет`() {
        assertNull(secrets(env = { null }).resolve(provider()))
        assertEquals(ModelSecretStatus.Absent, secrets(env = { null }).status(provider()))
    }

    @Test
    fun `недоступное хранилище не мешает ключу из окружения`() {
        // Переменные окружения остаются рабочим путём (уточнение задачи): недоступность
        // хранилища — причина не сохранять, а не повод не работать вовсе.
        val unavailable = FakeSecretStore.unavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE)

        assertEquals("ключ-из-окружения", secrets(backing = unavailable).resolve(provider()))
        assertEquals(ModelSecretStatus.FromEnv, secrets(backing = unavailable).status(provider()))
    }

    @Test
    fun `недоступное хранилище и нет окружения — состояние с причиной`() {
        val unavailable = FakeSecretStore.unavailable(SecretStoreUnavailableReason.TOOL_MISSING)
        val secrets = secrets(env = { null }, backing = unavailable)

        assertNull(secrets.resolve(provider()))
        assertEquals(
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.TOOL_MISSING),
            secrets.status(provider()),
        )
    }

    @Test
    fun `запись ключа кладёт значение в хранилище и возвращает состояние`() {
        val status = secrets(env = { null }).put(provider(), "новый-ключ")

        assertEquals(ModelSecretStatus.InStore, status)
        assertEquals("новый-ключ", store.stored["stub"], "значение обязано лечь в хранилище")
    }

    @Test
    fun `запись при недоступном хранилище ничего не сохраняет и возвращает причину`() {
        val unavailable = FakeSecretStore.unavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS)
        val status = secrets(backing = unavailable).put(provider(), "новый-ключ")

        assertEquals(
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS),
            status,
        )
        assertTrue(unavailable.stored.isEmpty(), "недоступное хранилище не должно ничего записать")
    }

    @Test
    fun `пустое значение отвергается, а не сохраняется`() {
        val rejection = assertFailsWith<ModelSecretRejectedException> {
            secrets().put(provider(), "   ")
        }

        assertEquals(ModelSecretRejection.EmptyValue, rejection.rejection)
        assertTrue(store.stored.isEmpty(), "пустое значение не должно попасть в хранилище")
    }

    @Test
    fun `удаление убирает ключ из хранилища`() {
        store.put("stub", "ключ-из-хранилища")

        val status = secrets(env = { null }).delete(provider())

        assertEquals(ModelSecretStatus.Absent, status)
        assertNull(store.get("stub"), "после удаления записи быть не должно")
    }

    @Test
    fun `удаление возвращает ключ к переменной окружения, а не к ошибке`() {
        // Критерий задачи: после удаления следующий прогон падает с «ключ не задан»,
        // а не с ошибкой авторизации. Если переменная окружения задана, источник — она.
        store.put("stub", "ключ-из-хранилища")

        val status = secrets().delete(provider())

        assertEquals(ModelSecretStatus.FromEnv, status)
        assertEquals("ключ-из-окружения", secrets().resolve(provider()))
    }

    @Test
    fun `удаление при недоступном хранилище не выдаёт себя за успех`() {
        val unavailable = FakeSecretStore.unavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE)

        val status = secrets(backing = unavailable).delete(provider())

        assertEquals(
            ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE),
            status,
        )
    }
}
