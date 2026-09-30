package dev.aide.agent.llm

/**
 * Модель по умолчанию: провайдер не настроен.
 *
 * Отвечает типизированной ошибкой, а не исключением и не молчанием: на чистой
 * установке задача обязана дойти до состояния `FAILED` с внятной причиной, а не
 * зависнуть, потому что модель не отвечает. Настоящий провайдер подключает T-1.56.
 */
class NotConfiguredLlmClient : LlmClient {

    override suspend fun complete(request: LlmRequest): LlmResponse =
        LlmResponse.Error(
            kind = LlmErrorKind.NOT_CONFIGURED,
            detail = "Провайдер модели не настроен: добавьте его в настройках (T-1.56)",
        )
}
