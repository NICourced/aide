package dev.aide.agent.config

import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * T-1.56: минимальная проверка конфигурации перед сохранением.
 *
 * Проверка не даёт записать заведомо нерабочее: повтор алиаса (реестр взял бы первый),
 * пустой адрес, размеры ≤ 0 (они уходят в `max_tokens`), модель без провайдера. Всё
 * остальное принимается: конфигурацию правит человек, и мешать ему больше, чем она
 * выдержит, нельзя.
 */
class AgentConfigValidatorTest {

    private val config = AgentConfig(
        defaultModel = "stub/model",
        providers = listOf(provider()),
        models = listOf(model()),
    )

    @Test
    fun `полная конфигурация принимается`() {
        assertNull(AgentConfigValidator.validate(config), "корректная конфигурация обязана сохраняться")
    }

    @Test
    fun `пустая конфигурация принимается`() {
        // Чистая установка — законное состояние: пустая конфигурация не «неверна», просто
        // прогон на ней падает с NOT_CONFIGURED и говорит, что делать.
        assertNull(AgentConfigValidator.validate(AgentConfig()))
    }

    @Test
    fun `повтор алиаса отвергается`() {
        val broken = config.copy(models = listOf(model(), model()))

        assertEquals(
            AgentConfigRejection.DuplicateModelAlias("stub/model"),
            AgentConfigValidator.validate(broken),
        )
    }

    @Test
    fun `пустой базовый адрес отвергается`() {
        val broken = config.copy(providers = listOf(provider(baseUrl = "   ")))

        assertEquals(AgentConfigRejection.EmptyBaseUrl("stub"), AgentConfigValidator.validate(broken))
    }

    @Test
    fun `модель без провайдера отвергается`() {
        val broken = config.copy(providers = emptyList())

        assertEquals(
            AgentConfigRejection.UnknownProvider(alias = "stub/model", provider = "stub"),
            AgentConfigValidator.validate(broken),
        )
    }

    @Test
    fun `неположительный контекст отвергается`() {
        val broken = config.copy(models = listOf(model().copy(contextWindow = 0)))

        assertEquals(AgentConfigRejection.NonPositiveContext("stub/model"), AgentConfigValidator.validate(broken))
    }

    @Test
    fun `неположительный предел вывода отвергается`() {
        val broken = config.copy(models = listOf(model().copy(maxOutputTokens = -1)))

        assertEquals(
            AgentConfigRejection.NonPositiveMaxOutput("stub/model"),
            AgentConfigValidator.validate(broken),
        )
    }

    @Test
    fun `нулевая ставка принимается, а отсутствие ставки — тоже`() {
        // Ноль — известная цена (локальная модель), а null — «цена неизвестна» (FR-COST-5).
        // Оба состояния законны, и проверка не должна их путать с ошибкой.
        val free = config.copy(
            models = listOf(
                model().copy(pricePerMillionInMicros = 0, pricePerMillionOutMicros = 0),
                model(alias = "stub/second").copy(pricePerMillionInMicros = null),
            ),
        )

        assertNull(AgentConfigValidator.validate(free))
    }

    @Test
    fun `модель без провайдера и с чужим провайдером отвергается одинаково`() {
        val foreign = config.copy(models = listOf(model(provider = "нет-такого")))

        assertEquals(
            AgentConfigRejection.UnknownProvider(alias = "stub/model", provider = "нет-такого"),
            AgentConfigValidator.validate(foreign),
        )
    }

    private fun provider(baseUrl: String = "http://127.0.0.1:9/v1"): ProviderProfile = ProviderProfile(
        id = "stub",
        type = ProviderType.OPENAI_COMPATIBLE,
        baseUrl = baseUrl,
        apiKeyEnv = null,
    )

    /** Образец модели: варианты задаются `copy` на месте, чтобы не плодить параметры. */
    private fun model(alias: String = "stub/model", provider: String = "stub"): ModelProfile = ModelProfile(
        alias = alias,
        provider = provider,
        model = "stub-model",
        contextWindow = 8_192,
        maxOutputTokens = 512,
        toolUse = true,
        pricePerMillionInMicros = 1_000_000,
        pricePerMillionOutMicros = 2_000_000,
    )
}
