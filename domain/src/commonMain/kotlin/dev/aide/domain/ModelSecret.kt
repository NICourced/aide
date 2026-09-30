package dev.aide.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Почему защищённое хранилище ключей недоступно (T-1.58).
 *
 * Код, а не текст: понятную строку строит UI из ресурсов (NFR-13), а причина нужна ему,
 * чтобы объяснить пользователю, что именно не работает, — «платформа не поддержана»,
 * «keyring не отвечает» и «нет secret-tool» требуют разных действий.
 */
@Serializable
enum class SecretStoreUnavailableReason {

    /** Платформа хоста не имеет защищённого хранилища в этой сборке (не Linux и не Windows). */
    @SerialName("notLinuxOrWindows")
    NOT_LINUX_OR_WINDOWS,

    /** Хранилище есть, но не отвечает: запертый или недоступный keyring, сбой DPAPI. */
    @SerialName("keyringUnavailable")
    KEYRING_UNAVAILABLE,

    /** Программы-посредника нет в системе (Linux: не найден `secret-tool` из libsecret). */
    @SerialName("toolMissing")
    TOOL_MISSING,
}

/**
 * Состояние ключа провайдера, каким его видит приложение (T-1.58).
 *
 * Только код состояния — **значения ключа здесь нет и быть не может**: ключ уходит
 * от клиента к хосту и обратно не читается ни приложением, ни протоколом.
 *
 * Порядок состояний отражает приоритет источников (решение задачи): сначала
 * защищённое хранилище платформы, затем переменная окружения процесса хоста.
 */
@Serializable
sealed interface ModelSecretStatus {

    /** Ключ лежит в защищённом хранилище платформы. */
    @Serializable
    @SerialName("inStore")
    data object InStore : ModelSecretStatus

    /** Ключа в хранилище нет, но он есть в переменной окружения хоста. */
    @Serializable
    @SerialName("fromEnv")
    data object FromEnv : ModelSecretStatus

    /** Ключа нет нигде: ни в хранилище, ни в окружении. */
    @Serializable
    @SerialName("absent")
    data object Absent : ModelSecretStatus

    /**
     * Хранилище недоступно, и ключа из другого источника тоже нет.
     *
     * Отдельное состояние, а не [Absent]: «хранилища нет» и «ключа нет» требуют от
     * пользователя разного — понять, почему нельзя сохранить ключ, а не искать ключ.
     */
    @Serializable
    @SerialName("storeUnavailable")
    data class StoreUnavailable(
        /** Почему хранилище недоступно. */
        val reason: SecretStoreUnavailableReason,
    ) : ModelSecretStatus
}

/**
 * Почему запись ключа отвергнута по существу (T-1.58).
 *
 * Отличие от [ModelSecretStatus.StoreUnavailable]: там операция не выполнена из-за
 * платформы, здесь — из-за самих данных. Отвергнутое не сохраняется молча.
 */
@Serializable
sealed interface ModelSecretRejection {

    /** Пустое значение: пустой ключ и отсутствие ключа были бы неотличимы. */
    @Serializable
    @SerialName("emptyValue")
    data object EmptyValue : ModelSecretRejection

    /** Провайдера с таким идентификатором нет в конфигурации: ключу негде принадлежать. */
    @Serializable
    @SerialName("unknownProvider")
    data class UnknownProvider(
        /** Идентификатор провайдера, которого нет в таблице. */
        val provider: String,
    ) : ModelSecretRejection
}
