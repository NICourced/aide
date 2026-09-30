package dev.aide.agent.config

import dev.aide.domain.AgentConfig
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-1.56: формат файла конфигурации моделей.
 *
 * Здесь проверяется только JSON: файл, переименование и атомарность — дело хоста
 * (`AgentConfigStore`), а формат — договор с человеком, который откроет файл редактором.
 */
class AgentConfigCodecTest {

    private val config = AgentConfig(
        defaultModel = "deepseek/deepseek-chat",
        providers = listOf(
            ProviderProfile(
                id = "deepseek",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1",
                apiKeyEnv = "DEEPSEEK_API_KEY",
                customHeaders = mapOf("X-Gateway" to "aide"),
            ),
            ProviderProfile(
                id = "ollama",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:11434/v1",
                apiKeyEnv = null,
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = "deepseek/deepseek-chat",
                provider = "deepseek",
                model = "deepseek-chat",
                displayName = "DeepSeek Chat",
                contextWindow = 64_000,
                maxOutputTokens = 8_192,
                toolUse = true,
                pricePerMillionInMicros = 270_000,
                pricePerMillionOutMicros = 1_100_000,
            ),
        ),
    )

    @Test
    fun `конфигурация переживает round-trip через текст`() {
        assertEquals(config, AgentConfigCodec.decode(AgentConfigCodec.encode(config)))
    }

    @Test
    fun `пустой текст читается как пустая конфигурация`() {
        // Файл создаётся пустым при первом запуске: это законное состояние чистой установки,
        // а не ошибка чтения, которая отобрала бы настройки.
        assertEquals(AgentConfig(), AgentConfigCodec.decode("   \n"))
    }

    @Test
    fun `файл с неизвестным полем не роняет чтение`() {
        // Файл правят руками, и лишняя строка в нём (в том числе от более новой версии)
        // не должна отбирать настройки целиком.
        val withUnknown = AgentConfigCodec.encode(config).replaceFirst("{", """{"futureField": 42,""")

        assertEquals(config, AgentConfigCodec.decode(withUnknown))
    }

    @Test
    fun `в файле есть имена переменных окружения, и нет места под ключ`() {
        val text = AgentConfigCodec.encode(config)

        assertTrue(text.contains("DEEPSEEK_API_KEY"), "имя переменной обязано быть в файле: $text")
        // Ключа тут нет не потому, что «мы его не пишем», а потому, что в типе конфигурации
        // нет поля, куда его положить: это единственная надёжная защита (NFR-8).
        assertFalse(text.contains("\"apiKey\""), "поля для ключа в файле быть не должно: $text")
        assertFalse(text.contains("\"token\""), "поля для токена в файле быть не должно: $text")
    }

    @Test
    fun `нулевая ставка остаётся нулевой, а не превращается в «неизвестно»`() {
        // Локальные модели стоят ноль — это известная цена, а не отсутствие данных
        // (FR-COST-5 различает именно это).
        val free = config.copy(
            models = listOf(config.models.single().copy(pricePerMillionInMicros = 0, pricePerMillionOutMicros = 0)),
        )

        val decoded = AgentConfigCodec.decode(AgentConfigCodec.encode(free))

        assertEquals(0L, decoded.models.single().pricePerMillionInMicros)
        assertEquals(0L, decoded.models.single().pricePerMillionOutMicros)
    }
}
