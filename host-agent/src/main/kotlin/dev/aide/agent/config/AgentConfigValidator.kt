package dev.aide.agent.config

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection

/**
 * Проверка конфигурации моделей перед сохранением (T-1.56, решение 12).
 *
 * Проверяется минимум, без которого конфигурация заведомо не работает: уникальность
 * алиасов (иначе реестр молча брал бы первый, а второй не запускался бы никогда), непустой
 * адрес провайдера, существование провайдера у модели и положительные размеры (они уходят
 * в запрос как `max_tokens` и в расчёт контекста). Всё остальное — забота каталога и
 * пользователя: конфигурация правится руками, и запрещать в ней больше, чем она выдержит,
 * значило бы мешать настройке.
 *
 * Типизированный отказ, а не исключение (О-9): «почему не сохранилось» обязан увидеть
 * пользователь, а не только журнал.
 */
object AgentConfigValidator {

    /**
     * Проверяет конфигурацию.
     *
     * @return первое нарушение или null, если конфигурация принимается. Порядок проверок
     *   задан от «ломает всё» к «ломает одну модель»: пользователь чинит начиная с первого.
     */
    fun validate(config: AgentConfig): AgentConfigRejection? {
        val duplicateAlias = config.models.groupBy { it.alias }.keys.firstOrNull { alias ->
            config.models.count { it.alias == alias } > 1
        }
        val providerWithoutUrl = config.providers.firstOrNull { it.baseUrl.isBlank() }
        val modelWithoutProvider = config.models.firstOrNull { model ->
            config.providers.none { it.id == model.provider }
        }
        val modelWithoutContext = config.models.firstOrNull { it.contextWindow <= 0 }
        val modelWithoutOutput = config.models.firstOrNull { it.maxOutputTokens <= 0 }

        return when {
            duplicateAlias != null -> AgentConfigRejection.DuplicateModelAlias(duplicateAlias)
            providerWithoutUrl != null -> AgentConfigRejection.EmptyBaseUrl(providerWithoutUrl.id)
            modelWithoutProvider != null ->
                AgentConfigRejection.UnknownProvider(modelWithoutProvider.alias, modelWithoutProvider.provider)

            modelWithoutContext != null -> AgentConfigRejection.NonPositiveContext(modelWithoutContext.alias)
            modelWithoutOutput != null -> AgentConfigRejection.NonPositiveMaxOutput(modelWithoutOutput.alias)
            else -> null
        }
    }
}
