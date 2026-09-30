package dev.aide.agent.provider

import dev.aide.agent.provider.openai.OpenAiCompatibleClient
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import io.ktor.client.HttpClient

/**
 * Клиенты провайдеров, собранные из адаптеров (T-1.56).
 *
 * Выбор адаптера — по протоколу профиля, а не по вендору (О-2): новый сервис,
 * говорящий на chat completions, не требует ни строки кода.
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

        // Заготовка Anthropic в каталоге есть, адаптер — T-1.57. Честный отказ вместо
        // «попробуем chat completions и посмотрим»: у Anthropic другой формат запроса,
        // и запрос к нему в чужом формате дал бы не ошибку протокола, а загадочный 400.
        ProviderType.ANTHROPIC -> Result.failure(UnsupportedProviderProtocolException(provider))
    }
}
