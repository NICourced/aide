package dev.aide.host.config

import dev.aide.agent.config.AgentConfigCodec
import dev.aide.domain.AgentConfig
import dev.aide.host.store.DatabaseFactory
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock
import kotlin.io.path.createDirectories
import org.slf4j.LoggerFactory

/**
 * Файл конфигурации моделей (T-1.56, решение 2).
 *
 * Конфигурация живёт файлом рядом с базой хоста, а не таблицей в базе: это настройка,
 * которую правит человек, а не доменные данные (§ 9). Ключа в файле нет ни в каком
 * виде — только имена переменных окружения (NFR-8).
 *
 * Запись **атомарная**: недописанный файл настроек равен потере настроек, а потерять
 * их можно в самый неудачный момент — между «пользователь нажал сохранить» и «хост
 * прочитал файл». Содержимое пишется во временный файл **с уникальным именем** рядом
 * и переименовывается поверх: переименование в пределах каталога атомарно, и читатель
 * видит либо старую конфигурацию целиком, либо новую целиком. Уникальность имени
 * обязательна: у общего временного пути два одновременных сохранения (телефон и
 * десктоп, повтор после реконнекта) писали бы в один файл и перемешали бы содержимое.
 *
 * @param path путь к файлу настроек.
 */
class AgentConfigStore(private val path: Path) {

    private val logger = LoggerFactory.getLogger(AgentConfigStore::class.java)

    /**
     * Замок чтения и записи.
     *
     * Нужен не только потому, что «общее состояние — под замком»: без него два
     * одновременных сохранения чередовали бы свои шаги, и настройки терялись бы даже
     * при уникальных временных файлах — победитель определялся бы гонкой. Замок обычный,
     * а не корутинный: критическая секция — файловая операция, она и так блокирует поток,
     * а [load] вызывается ещё и из не-приостанавливающего `ModelProvider.current()`.
     */
    private val lock = ReentrantLock()

    /** Путь к файлу конфигурации; нужен тестам и диагностике. */
    val configPath: Path get() = path

    /**
     * Читает конфигурацию.
     *
     * Нет файла, файл пуст, файл не разобран — пустая конфигурация и запись в журнал.
     * Это не «молчаливая потеря настроек», а единственный разумный ответ: без файла
     * хост обязан подняться, а прогон на пустой конфигурации падает с `NOT_CONFIGURED`,
     * то есть пользователь видит причину, а не отказ запуска хоста.
     */
    fun load(): AgentConfig = lock.withLock {
        if (!Files.isRegularFile(path)) {
            return@withLock AgentConfig()
        }
        runCatching { AgentConfigCodec.decode(Files.readString(path, StandardCharsets.UTF_8)) }
            .getOrElse { error ->
                logger.warn("Конфигурация моделей в $path не разобрана: ${error.message}", error)
                AgentConfig()
            }
    }

    /** Записывает конфигурацию атомарно: временный файл рядом, затем переименование. */
    fun save(config: AgentConfig) {
        lock.withLock { writeAtomically(config) }
    }

    /**
     * Любой сбой записи — сбой записи: временный файл надо убрать и не выдать успех.
     * Разбирать здесь вид ошибки (нет прав, нет места, цель занята) незачем: все они
     * одинаково означают «настройки не сохранены», а деталь уносит исходное исключение.
     */
    @Suppress("TooGenericExceptionCaught")
    private fun writeAtomically(config: AgentConfig) {
        val directory = path.toAbsolutePath().parent ?: Path.of(".")
        directory.createDirectories()
        // Уникальное имя: два одновременных сохранения не должны делить один файл.
        val temp = Files.createTempFile(directory, TEMP_PREFIX, TEMP_SUFFIX)
        try {
            Files.writeString(temp, AgentConfigCodec.encode(config), StandardCharsets.UTF_8)
            moveIntoPlace(temp)
        } catch (error: Exception) {
            // Временный файл — деталь записи, а не мусор рядом с настройками: после срыва
            // он обязан исчезнуть, иначе каталог зарастает файлами, а следующая запись
            // идёт рядом с ними.
            Files.deleteIfExists(temp)
            throw error
        }
    }

    /**
     * Переименовывает временный файл поверх целевого.
     *
     * Атомарное переименование поддерживают не все файловые системы; там, где его нет,
     * остаётся обычная замена — и это лучше, чем не сохранить настройки вовсе.
     */
    private fun moveIntoPlace(temp: Path) {
        try {
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (error: AtomicMoveNotSupportedException) {
            logger.debug("Атомарное переименование недоступно для $path: ${error.message}")
            Files.move(temp, path, StandardCopyOption.REPLACE_EXISTING)
        }
    }

    companion object {

        /** Имя файла конфигурации рядом с базой хоста. */
        const val FILE_NAME: String = "agent-config.json"

        /** Приставка временного файла: имя цели, чтобы файл читался в каталоге. */
        private const val TEMP_PREFIX = "agent-config.json."

        /** Расширение временного файла. */
        private const val TEMP_SUFFIX = ".tmp"

        /** Путь по умолчанию: рядом с базой хоста, где бы она ни лежала. */
        fun defaultPath(databasePath: Path? = null): Path =
            (databasePath ?: DatabaseFactory.defaultDatabasePath()).resolveSibling(FILE_NAME)
    }
}
