package dev.aide.agent.provider

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-1.56: каталог заготовок — данные модуля, а не код.
 *
 * Проверяется состав и полнота полей: заготовка без контекста, предела вывода или цены
 * бесполезна — её пришлось бы дозаполнять вручную по документации провайдера, то есть
 * ровно то, от чего каталог и избавляет.
 */
class ProviderCatalogTest {

    private val entries = ProviderCatalog.entries

    /** Сервисы, которые обязана содержать заготовка (критерий T-1.56). */
    private val requiredProviders = listOf(
        "openai", "anthropic", "moonshot", "deepseek", "qwen", "openrouter",
        "groq", "mistral", "xai", "gemini", "ollama", "lmstudio", "vllm",
    )

    @Test
    fun `каталог содержит все перечисленные сервисы`() {
        val ids = entries.map { it.provider.id }

        assertTrue(entries.isNotEmpty(), "каталог не должен быть пустым")
        assertEquals(requiredProviders, ids, "состав заготовок зафиксирован критерием задачи")
    }

    @Test
    fun `у каждой заготовки есть модель с контекстом, пределом вывода, возможностями и ставками`() {
        val incomplete = entries.filter { entry ->
            entry.models.isEmpty() ||
                entry.models.any {
                    it.contextWindow <= 0 || it.maxOutputTokens <= 0 ||
                        it.pricePerMillionInMicros == null || it.pricePerMillionOutMicros == null
                }
        }

        assertTrue(
            incomplete.isEmpty(),
            "заготовки без заполненной модели: ${incomplete.map { it.provider.id }}",
        )
    }

    @Test
    fun `модели заготовки ссылаются на своего провайдера и уникальны по алиасу`() {
        entries.forEach { entry ->
            assertTrue(
                entry.models.all { it.provider == entry.provider.id },
                "модель заготовки ${entry.provider.id} ссылается на чужого провайдера",
            )
        }
        val aliases = entries.flatMap { entry -> entry.models.map { it.alias } }
        assertEquals(aliases.size, aliases.toSet().size, "алиасы обязаны быть уникальны: $aliases")
    }

    @Test
    fun `локальным провайдерам ключ не нужен`() {
        // Ollama, LM Studio и vLLM работают без ключа — это разрешено явно (решение 6),
        // и пустое имя переменной для них законное состояние профиля, а не опечатка.
        val local = entries.filter { it.provider.id in setOf("ollama", "lmstudio", "vllm") }

        assertEquals(3, local.size, "локальные заготовки обязаны быть в каталоге")
        assertTrue(
            local.all { it.provider.apiKeyEnv == null },
            "локальным провайдерам ключ не нужен: ${local.map { it.provider.apiKeyEnv }}",
        )
    }

    @Test
    fun `заготовка Anthropic объявлена протоколом Anthropic, а не OpenAI`() {
        // Иначе пользователь добавил бы Claude и получил бы загадочный 400 вместо отказа:
        // форматы запроса у двух протоколов разные (О-2).
        val anthropic = entries.single { it.provider.id == "anthropic" }

        assertEquals(dev.aide.domain.ProviderType.ANTHROPIC, anthropic.provider.type)
    }
}
