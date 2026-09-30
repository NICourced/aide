package dev.aide.agent.provider

import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderProfile
import org.slf4j.LoggerFactory

/**
 * Ключи провайдеров: значение по приоритету, состояние и запись (T-1.58).
 *
 * Приоритет источников — решение задачи: сначала защищённое хранилище платформы, затем
 * переменная окружения процесса хоста. Переменные окружения остаются рабочим путём для
 * десктопа и CI: хранилище не заменяет их, а идёт перед ними. Локальным провайдерам
 * (пустое имя переменной) ключ не нужен — это законное состояние, а не ошибка.
 *
 * Наружу отсюда выходит либо значение (и только потребителю — клиенту провайдера),
 * либо код состояния. Состояние считается по приоритету так же, как значение: сначала
 * хранилище, потом окружение, и лишь при отсутствии обоих сообщается причина недоступности
 * хранилища. Иначе пользователь с рабочим ключом в окружении видел бы «хранилище
 * недоступно» там, где всё работает, а понять, что ключ нельзя сохранить, он смог бы
 * только по отказу записи.
 *
 * @param store защищённое хранилище; имена записей — идентификаторы провайдеров.
 * @param env окружение хоста. Инъекция нужна тестам и честности: значение ключа приходит
 *   ровно из одного места, и это место видно.
 */
class ModelSecrets(
    private val store: SecretStore,
    private val env: (String) -> String? = System::getenv,
) {

    private val logger = LoggerFactory.getLogger(ModelSecrets::class.java)

    /** Значение ключа провайдера или null, если ключа нет ни в одном источнике. */
    fun resolve(provider: ProviderProfile): String? = storedKey(provider) ?: envKey(provider)

    /** Состояние ключа провайдера: код состояния, значение наружу не выходит. */
    fun status(provider: ProviderProfile): ModelSecretStatus {
        val stored = storedKey(provider)
        val fromEnvironment = envKey(provider)
        return when {
            stored != null -> ModelSecretStatus.InStore
            fromEnvironment != null -> ModelSecretStatus.FromEnv
            else -> when (val availability = store.availability()) {
                is SecretStoreAvailability.Available -> ModelSecretStatus.Absent
                is SecretStoreAvailability.Unavailable -> ModelSecretStatus.StoreUnavailable(availability.reason)
            }
        }
    }

    /**
     * Сохраняет ключ провайдера.
     *
     * @return состояние ключа после записи: обычно [ModelSecretStatus.InStore], а при
     *   недоступном хранилище — [ModelSecretStatus.StoreUnavailable] с причиной. Отказ
     *   платформы возвращается состоянием, потому что запрос выполнен и ответ есть;
     *   исключение осталось бы за границей протокола.
     * @throws ModelSecretRejectedException пустое значение.
     */
    fun put(provider: ProviderProfile, value: String): ModelSecretStatus {
        if (value.isBlank()) throw ModelSecretRejectedException(ModelSecretRejection.EmptyValue)
        return write(provider) { store -> store.put(provider.id, value) }
    }

    /**
     * Удаляет ключ провайдера.
     *
     * @return состояние ключа после удаления: обычно [ModelSecretStatus.Absent] или
     *   [ModelSecretStatus.FromEnv], если переменная окружения задана.
     */
    fun delete(provider: ProviderProfile): ModelSecretStatus = write(provider) { store ->
        store.delete(provider.id)
    }

    /**
     * Выполняет запись и возвращает состояние после неё.
     *
     * Недоступное хранилище превращается в состояние здесь, а не выбрасывается наружу:
     * пользователю нужно увидеть причину, а не «запрос не выполнен».
     */
    private fun write(provider: ProviderProfile, op: (SecretStore) -> Unit): ModelSecretStatus =
        when (val availability = store.availability()) {
            is SecretStoreAvailability.Available -> {
                op(store)
                status(provider)
            }

            is SecretStoreAvailability.Unavailable -> ModelSecretStatus.StoreUnavailable(availability.reason)
        }

    /**
     * Значение из защищённого хранилища.
     *
     * Недоступное хранилище — не ошибка выбора ключа: оно просто не может дать значение,
     * и приоритет переходит к переменной окружения. Сбой чтения при доступном хранилище
     * (заперли keyring между проверкой и чтением, сломался DPAPI) тоже не роняет выбор
     * модели: он означает «отсюда ключ не получить», и об этом пишет журнал.
     */
    private fun storedKey(provider: ProviderProfile): String? {
        if (store.availability() is SecretStoreAvailability.Unavailable) return null
        return try {
            store.get(provider.id)?.takeIf { it.isNotBlank() }
        } catch (error: SecretStoreUnavailableException) {
            logger.warn("Хранилище ключей отказало при чтении (${error.reason})", error)
            null
        }
    }

    /** Значение из переменной окружения по имени из профиля; пустое имя — ключ не нужен. */
    private fun envKey(provider: ProviderProfile): String? {
        val name = provider.apiKeyEnv?.takeIf { it.isNotBlank() } ?: return null
        return env(name)?.takeIf { it.isNotBlank() }
    }
}
