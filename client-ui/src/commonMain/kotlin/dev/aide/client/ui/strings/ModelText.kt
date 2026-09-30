package dev.aide.client.ui.strings

import androidx.compose.runtime.Composable
import dev.aide.client.state.ModelConfigError
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ProviderType
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
