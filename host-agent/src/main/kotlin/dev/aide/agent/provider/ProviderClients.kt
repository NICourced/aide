package dev.aide.agent.provider

import dev.aide.agent.provider.anthropic.AnthropicClient
import dev.aide.agent.provider.openai.OpenAiCompatibleClient
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import io.ktor.client.HttpClient

/**
 * Клиенты провайдеров, собранные из адаптеров (T-1.56, T-1.57).
 *
 * Выбор адаптера — по протоколу профиля, а не по вендору (О-2): новый сервис,
 * говорящий на chat completions, не требует ни строки кода.
 *
 * `when` перечисляет протоколы без ветки «остальное»: у каждого значения перечисления
 * есть адаптер, и новый протокол, объявленный в домене, обязан сломать сборку здесь,
 * а не получить тихий отказ. Молчаливое `Unsupported` для протокола, который кто-то
 * добавил в каталог намеренно, выглядело бы как неисправность провайдера.
 *
 * @param http клиент Ktor, разделяемый всеми адаптерами: соединения и пул — общие,
 *   а владеет им хост, который и закрывает его при остановке.
 */
class ProviderClients(private val http: HttpClient) : ProviderClientFactory {

    override fun create(
        provider: ProviderProfile,
        model: ModelProfile,
        apiKey: String?,
    ): Result<ProviderClient> = when (provider.type) {
        ProviderType.OPENAI_COMPATIBLE ->
            Result.success(OpenAiCompatibleClient(provider, model, apiKey, http))

        ProviderType.ANTHROPIC ->
            Result.success(AnthropicClient(provider, model, apiKey, http))
    }
}
