package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createDirectories
import kotlin.io.path.setPosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.58: защищённое хранилище Linux — libsecret через `secret-tool`.
 *
 * Тест **не падает на машине без keyring** и не притворяется, что проверил хранилище:
 * есть `secret-tool` и рабочий keyring — ключ проходит настоящий путь «запись → чтение →
 * удаление» в системном хранилище; нет — проверяется явный отказ с причиной, и это тоже
 * требование задачи (тихое сохранение в файл запрещено).
 *
 * Значение для пробы своё: чужие записи тест не читает и не удаляет.
 */
class LinuxSecretStoreTest {

    private val store = LinuxSecretStore()
    private val providerId = "aide-test-${System.currentTimeMillis()}"

    @AfterTest
    fun tearDown() {
        if (store.availability() is SecretStoreAvailability.Available) {
            runCatching { store.delete(providerId) }
        }
    }

    @Test
    fun `ключ проходит настоящий путь, если keyring доступен, и хранилище отказывает, если нет`() {
        if (store.availability() !is SecretStoreAvailability.Available) {
            assertRefusal()
            return
        }
        val secret = "sk-linux-${System.nanoTime()}"
        assertNull(store.get(providerId), "до записи ключа быть не должно")

        store.put(providerId, secret)

        assertEquals(secret, store.get(providerId), "ключ обязан читаться из системного хранилища")
        store.delete(providerId)
        assertNull(store.get(providerId), "после удаления записи быть не должно")
    }

    @Test
    fun `недоступное хранилище отказывает, не создавая файла`() {
        // Программы нет — ветка та же, что и на машине без libsecret: отказ с причиной.
        val missing = LinuxSecretStore(executable = "aide-нет-такой-программы")
        val directory: Path = Files.createTempDirectory("aide-linux-secret").createDirectories()

        assertEquals(
            SecretStoreAvailability.Unavailable(SecretStoreUnavailableReason.TOOL_MISSING),
            missing.availability(),
        )
        val error = assertFailsWith<SecretStoreUnavailableException> { missing.put("stub", "ключ") }
        assertEquals(SecretStoreUnavailableReason.TOOL_MISSING, error.reason)

        val files = Files.list(directory).use { stream -> stream.toList() }
        assertTrue(files.isEmpty(), "хранилище не имеет права писать в файлы: $files")
    }

    @Test
    fun `зависшая программа обрывается по таймауту, а не вешает хост`() {
        // Запертый keyring заставляет `secret-tool` ждать разблокировки. Здесь ожидание
        // изображает скрипт-заглушка: важно, что вызов возвращается, а не висит вечно.
        val blocked = LinuxSecretStore(
            executable = fakeTool("#!/bin/sh\nsleep $HANGING_SCRIPT_SECONDS\n").toString(),
            timeoutMillis = 300,
        )

        val started = System.currentTimeMillis()
        val availability = blocked.availability()
        val elapsed = System.currentTimeMillis() - started

        assertEquals(
            SecretStoreAvailability.Unavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE),
            availability,
        )
        assertTrue(elapsed < HANGING_SCRIPT_MILLIS, "вызов обязан прерваться по таймауту, прошло $elapsed мс")
        assertFailsWith<SecretStoreUnavailableException> { blocked.get("stub") }
    }

    @Test
    fun `нулевой код программы отдаёт значение ключа`() {
        val store = LinuxSecretStore(executable = fakeTool("#!/bin/sh\nprintf 'ключ-из-скрипта'\n").toString())

        assertEquals(SecretStoreAvailability.Available, store.availability())
        assertEquals("ключ-из-скрипта", store.get(providerId))
    }

    @Test
    fun `ненулевой код с пустым stderr означает, что записи нет`() {
        // Так отвечает настоящий `secret-tool`, когда ключа нет: код 1 и молчание.
        // Это не отказ: иначе недоступным объявлялось бы исправное хранилище.
        val store = LinuxSecretStore(executable = fakeTool("#!/bin/sh\nexit 1\n").toString())

        assertEquals(SecretStoreAvailability.Available, store.availability())
        assertNull(store.get(providerId))
    }

    @Test
    fun `ненулевой код с диагностикой в stderr означает недоступное хранилище`() {
        // По коду возврата эти два случая неразличимы: и «ключа нет», и «сервис не отвечает»
        // дают единицу. Различие несёт stderr — и без него хранилище молча уступало бы место
        // переменной окружения, чего контракт порта не разрешает.
        val diagnostic = "The name org.freedesktop.secrets was not provided by any .service files"
        val store = LinuxSecretStore(
            executable = fakeTool("#!/bin/sh\necho '$diagnostic' >&2\nexit 2\n").toString(),
        )

        assertEquals(
            SecretStoreAvailability.Unavailable(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE),
            store.availability(),
        )
        val error = assertFailsWith<SecretStoreUnavailableException> { store.get(providerId) }
        assertEquals(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE, error.reason)
    }

    @Test
    fun `длинный вывод программы не превращается в таймаут`() {
        // Канал процесса вмещает килобайты: если читать вывод только после выхода, процесс
        // упрётся в заполненную трубу и вызов оборвётся таймаутом на исправном хранилище.
        val store = LinuxSecretStore(
            executable = fakeTool("#!/bin/sh\nhead -c $LONG_OUTPUT_BYTES /dev/zero | tr '\\0' 'x'\n").toString(),
            timeoutMillis = 5_000,
        )

        val value = store.get(providerId)

        assertEquals(LONG_OUTPUT_BYTES, value?.length, "вывод обязан дойти целиком, а не оборваться таймаутом")
    }

    /**
     * Ветка «хранилища нет»: причина названа, операции отказывают, файла не появляется.
     *
     * Проверяется обе стороны: «недоступно» обязано быть одной из двух причин Linux
     * (нет программы или не отвечает keyring), а не общим «что-то пошло не так».
     */
    private fun assertRefusal() {
        val unavailable = assertIs<SecretStoreAvailability.Unavailable>(store.availability())

        assertTrue(
            unavailable.reason == SecretStoreUnavailableReason.TOOL_MISSING ||
                unavailable.reason == SecretStoreUnavailableReason.KEYRING_UNAVAILABLE,
            "причина недоступности обязана быть объяснена: ${unavailable.reason}",
        )
        val read = assertFailsWith<SecretStoreUnavailableException> { store.get(providerId) }
        val write = assertFailsWith<SecretStoreUnavailableException> { store.put(providerId, "ключ") }

        assertEquals(unavailable.reason, read.reason)
        assertEquals(unavailable.reason, write.reason)
    }

    private companion object {
        /** Сколько «спит» скрипт-заглушка: заведомо дольше таймаута вызова. */
        const val HANGING_SCRIPT_SECONDS = 30

        /** Верхняя граница разумного ожидания теста; сон заведомо больше. */
        const val HANGING_SCRIPT_MILLIS = 20_000L

        /** Объём вывода, заведомо больший буфера канала: проверка параллельного чтения. */
        const val LONG_OUTPUT_BYTES = 200_000

        /** Пишет исполняемый скрипт-заглушку `secret-tool`, отвечающий заданным образом. */
        private fun fakeTool(script: String): Path {
            val tool = Files.createTempDirectory("aide-fake-secret-tool").resolve("secret-tool")
            tool.toFile().writeText(script)
            tool.setPosixFilePermissions(PosixFilePermissions.fromString("rwxr-xr-x"))
            return tool
        }
    }
}
