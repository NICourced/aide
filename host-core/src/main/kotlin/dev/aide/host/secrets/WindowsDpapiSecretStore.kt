// CBOR и бинарные encodeToByteArray/decodeFromByteArray помечены в kotlinx.serialization
// экспериментальными; opt-in нужен и объявлениям уровня файла, которым не достаётся
// аннотация объекта.
@file:OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)

package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.cbor.Cbor
import org.slf4j.LoggerFactory

/**
 * Защищённое хранилище ключей на Windows: DPAPI и шифрованный блоб рядом с базой (T-1.58).
 *
 * Ключи лежат одним файлом рядом с базой хоста, но **зашифрованным** через DPAPI: блоб
 * расшифровывается только учётной записью пользователя на этой машине, поэтому копия
 * каталога данных, украденная вместе с репозиторием или унесённая на другом диске, ключей
 * не содержит. Файл — не «сохранение в файл вместо хранилища»: в хранилище-то и есть смысл
 * DPAPI, и открытого текста в файле нет; так работает и сам Windows для подобных данных.
 *
 * Блоб целиком читается и перезаписывается под замком: иначе два одновременных сохранения
 * (телефон и десктоп) делили бы файл и теряли ключи друг друга. Запись атомарная —
 * уникальный временный файл рядом и переименование поверх: недописанный блоб равен
 * потере всех сохранённых ключей сразу.
 *
 * @param path путь блоба; лежит рядом с базой хоста.
 * @param cipher платформенное шифрование; подставляется тестом, потому что DPAPI работает
 *   только на Windows, а логику хранения проверить нужно везде.
 */
class WindowsDpapiSecretStore(
    private val path: Path,
    private val cipher: SecretCipher,
) : SecretStore {

    private val logger = LoggerFactory.getLogger(WindowsDpapiSecretStore::class.java)

    /** Замок на чтение-изменение-запись одного файла: критическая секция — работа с диском. */
    private val lock = ReentrantLock()

    /** DPAPI доступен всегда, когда есть учётная запись пользователя; сбой виден на чтении. */
    override fun availability(): SecretStoreAvailability = SecretStoreAvailability.Available

    override fun get(name: String): String? = lock.withLock { readAll()[name] }

    override fun put(name: String, value: String) {
        lock.withLock { writeAll(readAll() + (name to value)) }
    }

    override fun delete(name: String) {
        lock.withLock { writeAll(readAll() - name) }
    }

    /**
     * Читает блоб.
     *
     * Нет файла — ключей нет. Не расшифрован или не разобран — отказ, а не пустая карта:
     * пустая карта молча превратила бы «чужие ключи» в «ключей нет», и следующий прогон
     * упал бы с «ключ не задан» вместо честного «хранилище недоступно».
     */
    private fun readAll(): Map<String, String> {
        if (!Files.isRegularFile(path)) return emptyMap()
        return try {
            decode(cipher.decrypt(Files.readAllBytes(path)))
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            logger.warn("Блоб ключей в $path не прочитан: ${error.message}")
            throw SecretStoreUnavailableException(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE)
        }
    }

    /** Пишет блоб целиком: шифрованный временный файл рядом, затем переименование поверх. */
    private fun writeAll(secrets: Map<String, String>) {
        val directory = path.toAbsolutePath().parent ?: Path.of(".")
        directory.createDirectories()
        val temp = Files.createTempFile(directory, path.fileName.toString(), TEMP_SUFFIX)
        try {
            Files.write(temp, cipher.encrypt(encode(secrets)))
            moveIntoPlace(temp)
        } catch (@Suppress("TooGenericExceptionCaught") error: Exception) {
            Files.deleteIfExists(temp)
            logger.warn("Блоб ключей не записан: ${error.message}")
            throw SecretStoreUnavailableException(SecretStoreUnavailableReason.KEYRING_UNAVAILABLE)
        }
    }

    /**
     * Переименовывает временный файл поверх целевого.
     *
     * Атомарное переименование поддерживают не все файловые системы; там, где его нет,
     * остаётся обычная замена — и это лучше, чем не сохранить ключ.
     */
    private fun moveIntoPlace(temp: Path) {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            logger.debug("Атомарное переименование недоступно для $path: ${error.message}")
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun encode(secrets: Map<String, String>): ByteArray =
        cbor.encodeToByteArray(serializer, secrets)

    private fun decode(bytes: ByteArray): Map<String, String> = cbor.decodeFromByteArray(serializer, bytes)

    companion object {

        /** Имя блоба рядом с базой хоста. */
        const val FILE_NAME: String = "model-secrets.bin"

        /** Расширение временного файла: рядом с блобом, чтобы он читался в каталоге. */
        private const val TEMP_SUFFIX = ".tmp"

        /** Формат блоба: карта «провайдер → ключ». Данные внутренние, наружу не выходят. */
        private val serializer = MapSerializer(String.serializer(), String.serializer())

        private val cbor = Cbor { encodeDefaults = true }
    }
}
