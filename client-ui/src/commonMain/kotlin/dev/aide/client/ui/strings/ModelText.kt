package dev.aide.client.ui.strings

import androidx.compose.runtime.Composable
import dev.aide.client.state.ModelConfigError
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.SecretStoreUnavailableReason
import org.jetbrains.compose.resources.StringResource

/**
 * Тексты раздела «Модель» — из ресурсов, а не из кода (NFR-13).
 *
 * Файл назван по общему предмету: здесь и результат проверки доступа, и название протокола,
 * и причина непринятой конфигурации — всё, что раздел показывает словами.
 */

/**
 * Текст результата проверки модели.
 *
 * Разбор исчерпывающий: новый вариант [ModelCheckFailure] не соберётся, пока для него нет
 * строки. Общий текст «проверка не удалась» на непредусмотренный случай показывал бы
 * пользователю неправду вместо причины и прятал бы забытую строку.
 */
fun modelCheckResource(failure: ModelCheckFailure?): StringResource = when (failure) {
    null -> Strings.settingsModelCheckOk
    is ModelCheckFailure.NotConfigured -> Strings.settingsModelCheckNotConfigured
    is ModelCheckFailure.UnknownModel -> Strings.settingsModelCheckUnknownModel
    is ModelCheckFailure.MissingKey -> Strings.settingsModelCheckMissingKey
    is ModelCheckFailure.Unsupported -> Strings.settingsModelCheckUnsupported
    is ModelCheckFailure.Unauthorized -> Strings.settingsModelCheckUnauthorized
    is ModelCheckFailure.RateLimited -> Strings.settingsModelCheckRateLimited
    is ModelCheckFailure.RequestFailed -> Strings.settingsModelCheckFailed
    is ModelCheckFailure.ResponseUnreadable -> Strings.settingsModelCheckUnreadable
}

/** Название протокола по-русски: внутреннее имя перечисления пользователю ничего не говорит. */
fun providerTypeResource(type: ProviderType): StringResource = when (type) {
    ProviderType.OPENAI_COMPATIBLE -> Strings.providerTypeOpenAiCompatible
    ProviderType.ANTHROPIC -> Strings.providerTypeAnthropic
}

/**
 * Текст отказа настроек моделей: связь или отказ конфигурации по существу.
 *
 * Имя модели и провайдера подставляются как есть — по ним пользователь и находит строку,
 * которую надо поправить.
 */
@Composable
fun modelErrorString(error: ModelConfigError): String = when (error) {
    ModelConfigError.Unreachable -> Strings.text(Strings.settingsModelErrorUnreachable)
    is ModelConfigError.Rejected -> rejectionString(error.rejection)
    is ModelConfigError.SecretRejected -> secretRejectionString(error.rejection)
}

/**
 * Текст состояния ключа провайдера (T-1.58).
 *
 * Строка состояния — единственное, что приложение знает о ключе: значения оно не видит.
 * Поэтому «взят из окружения» называет переменную: по ней пользователь понимает, откуда
 * ключ, и что произойдёт, когда он её уберёт.
 */
@Composable
fun secretStatusString(provider: ProviderProfile, status: ModelSecretStatus): String = when (status) {
    ModelSecretStatus.InStore -> Strings.text(Strings.settingsModelSecretInStore)
    ModelSecretStatus.FromEnv -> Strings.text(Strings.settingsModelSecretFromEnv, provider.apiKeyEnv.orEmpty())
    ModelSecretStatus.Absent -> Strings.text(Strings.settingsModelSecretAbsent)
    is ModelSecretStatus.StoreUnavailable ->
        Strings.text(Strings.settingsModelSecretUnavailable, secretReasonString(status.reason))
}

/** Причина недоступности хранилища словами: код перечисления пользователю ничего не говорит. */
@Composable
fun secretReasonString(reason: SecretStoreUnavailableReason): String = when (reason) {
    SecretStoreUnavailableReason.NOT_LINUX_OR_WINDOWS -> Strings.text(Strings.settingsModelSecretReasonNotSupported)
    SecretStoreUnavailableReason.KEYRING_UNAVAILABLE -> Strings.text(Strings.settingsModelSecretReasonKeyring)
    SecretStoreUnavailableReason.TOOL_MISSING -> Strings.text(Strings.settingsModelSecretReasonToolMissing)
}

/** Почему запись ключа отвергнута по существу: пустое значение или неизвестный провайдер. */
@Composable
private fun secretRejectionString(rejection: ModelSecretRejection): String = when (rejection) {
    ModelSecretRejection.EmptyValue -> Strings.text(Strings.settingsModelSecretRejectionEmpty)
    is ModelSecretRejection.UnknownProvider ->
        Strings.text(Strings.settingsModelSecretRejectionUnknownProvider, rejection.provider)
}

@Composable
private fun rejectionString(rejection: AgentConfigRejection): String = when (rejection) {
    is AgentConfigRejection.EmptyBaseUrl ->
        Strings.text(Strings.settingsModelRejectionBaseUrl, rejection.provider)

    is AgentConfigRejection.DuplicateModelAlias ->
        Strings.text(Strings.settingsModelRejectionDuplicateAlias, rejection.alias)

    is AgentConfigRejection.UnknownProvider ->
        Strings.text(Strings.settingsModelRejectionUnknownProvider, rejection.alias, rejection.provider)

    is AgentConfigRejection.NonPositiveContext ->
        Strings.text(Strings.settingsModelRejectionContext, rejection.alias)

    is AgentConfigRejection.NonPositiveMaxOutput ->
        Strings.text(Strings.settingsModelRejectionMaxOutput, rejection.alias)
}
