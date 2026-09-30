package dev.aide.host

import dev.aide.agent.provider.SecretStore
import dev.aide.host.config.AgentConfigStore
import dev.aide.host.secrets.SecretStoreFactory
import dev.aide.host.secrets.WindowsDpapiSecretStore
import java.nio.file.Path

/**
 * Состояние хоста на диске: база метаданных, файл настроек моделей и защищённое
 * хранилище ключей (T-1.56, T-1.58).
 *
 * Экземпляр ровно один на хост, и это не удобство, а условие корректности: хранилище
 * настроек держит замок записи, а хранилище ключей — свой, поэтому два экземпляра на
 * одном пути означали бы два независимых замка, то есть отсутствие защиты от
 * одновременной записи. Поэтому все три части создаются вместе, здесь, а не по месту
 * каждым потребителем.
 *
 * Файл настроек и блоб ключей лежат рядом с базой: части состояния принадлежат одному
 * хосту, и разносить их по каталогам значило бы однажды разойтись — база от одного
 * запуска, настройки от другого.
 */
class HostStorage private constructor(
    /** Путь к базе метаданных; null — база приложения по умолчанию. */
    val databasePath: Path?,

    /** Хранилище настроек моделей — единственный экземпляр на этот хост. */
    val config: AgentConfigStore,

    /** Защищённое хранилище ключей провайдеров — единственный экземпляр на этот хост. */
    val secrets: SecretStore,
) {

    companion object {

        /** Имя блоба ключей рядом с базой; используется веткой DPAPI. */
        const val SECRETS_FILE_NAME: String = WindowsDpapiSecretStore.FILE_NAME

        /**
         * Создаёт состояние хоста: настройки и блоб ключей лягут рядом с базой.
         *
         * @param secrets готовое хранилище; null — выбор по платформе хоста. Параметр
         *   существует для тестов, подставляющих фейк: настоящее хранилище проверяется
         *   отдельно и только там, где оно есть (тест не должен падать на машине без keyring).
         */
        fun of(databasePath: Path? = null, secrets: SecretStore? = null): HostStorage {
            val configPath = AgentConfigStore.defaultPath(databasePath)
            return HostStorage(
                databasePath = databasePath,
                config = AgentConfigStore(configPath),
                secrets = secrets ?: SecretStoreFactory(configPath.resolveSibling(SECRETS_FILE_NAME)).create(),
            )
        }
    }
}
