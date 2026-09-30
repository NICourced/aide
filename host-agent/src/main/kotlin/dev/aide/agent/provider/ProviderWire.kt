package dev.aide.agent.provider

import dev.aide.agent.llm.LlmErrorKind
import dev.aide.agent.llm.LlmResponse
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.HttpStatusCode
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

// Общая часть адаптеров провайдеров (T-1.57).
//
// Протоколы разные, а требования к отказу и стоимости — одни: коды причин заданы одним
// словарём (решение 4), стоимость считается по ставкам профиля модели, а не по ответу
// провайдера (О-2). Второй экземпляр этой арифметики разошёлся бы с первым молча, и
// пользователь увидел бы два разных ответа на «сколько это стоило»; поэтому арифметика
// живёт здесь одна, а адаптеры приносят ей только числа токенов.

/** Микроединиц стоимости в единице тарификации провайдера. */
internal const val MICROS_PER_MILLION: Long = 1_000_000

/** Наносекунд в миллисекунде: длительность вызова записывается в миллисекундах. */
private const val NANOS_PER_MILLI: Long = 1_000_000

/** Отказ проверки доступа: ответ 200 пришёл не JSON-ом — разбирать его нечем. */
internal const val NOT_JSON_DETAIL: String = "ответ не JSON"

/**
 * Стоимость по числу токенов и ставкам профиля модели.
 *
 * Ставки берутся из профиля, а не из ответа: провайдер отдаёт токены, цену за них
 * знает только пользовательская настройка (О-2). Нет любой из ставок — цена неизвестна,
 * а не ноль (FR-COST-5): провайдер, не посчитавший токены или не имеющий цены, не даёт
 * права утверждать, что прогон ничего не стоил.
 */
internal fun costOf(inputTokens: Long, outputTokens: Long, model: ModelProfile): Cost {
    val inRate = model.pricePerMillionInMicros
    val outRate = model.pricePerMillionOutMicros
    if (inRate == null || outRate == null) return Cost(known = false)
    return Cost(
        amountMicros = inputTokens * inRate / MICROS_PER_MILLION + outputTokens * outRate / MICROS_PER_MILLION,
        known = true,
    )
}

/**
 * Неразбираемый ответ — вариант ответа, а не исключение наружу (О-9).
 *
 * Строится общим кодом: у обоих протоколов «ответ не разобран» — одно состояние, и
 * деталь в нём техническая, для журнала, а не для экрана (NFR-13).
 */
internal fun unreadableResponse(detail: String): LlmResponse =
    LlmResponse.Error(LlmErrorKind.RESPONSE_UNREADABLE, detail)

/** Число токенов в поле `usage`; отсутствие поля считается нулём — иначе отказ был бы непонятен. */
internal fun JsonObject.tokenCount(field: String): Long =
    (this[field] as? JsonPrimitive)?.longOrNull ?: 0

/** Строковое поле объекта; не строка и не примитив — то же, что отсутствие поля. */
internal fun JsonObject.stringField(name: String): String? =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Длительность вызова в миллисекундах. */
internal fun elapsedMillis(startedNanos: Long): Long = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI

/**
 * Как ошибка HTTP отображается в код причины отказа модели (решение 4).
 *
 * Вендор в коде не назван намеренно: пользователю незачем знать, кто ответил ошибкой,
 * а словарь причин один для обоих протоколов. Всё, что не опознано как отказ по ключу
 * или по лимиту, — «провайдер не ответил»: 5xx, 400 и разрыв соединения для движка
 * одинаковы.
 */
internal fun errorKindOf(status: HttpStatusCode): LlmErrorKind = when (status.value) {
    HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> LlmErrorKind.UNAUTHORIZED
    HttpStatusCode.TooManyRequests.value -> LlmErrorKind.RATE_LIMITED
    else -> LlmErrorKind.REQUEST_FAILED
}

/**
 * Ответ на запрос списка моделей: 401/403 — ключ, 429 — лимит, 404/405 — запрос не поддерживается.
 *
 * Семантика проверки доступа общая для протоколов: «ключа нет» и «такого запроса у меня
 * нет» — разные состояния, и второе обязано быть видно как `Unsupported`, а не как ложное
 * «ключ верен» или «ключ неверен».
 */
internal suspend fun checkFailureOf(response: HttpResponse): ModelCheckFailure? = when (response.status.value) {
    HttpStatusCode.OK.value ->
        if (response.bodyIsJson()) null else ModelCheckFailure.ResponseUnreadable(NOT_JSON_DETAIL)

    HttpStatusCode.Unauthorized.value, HttpStatusCode.Forbidden.value -> ModelCheckFailure.Unauthorized
    HttpStatusCode.TooManyRequests.value -> ModelCheckFailure.RateLimited
    HttpStatusCode.NotFound.value, HttpStatusCode.MethodNotAllowed.value -> ModelCheckFailure.Unsupported
    else -> ModelCheckFailure.RequestFailed("HTTP ${response.status.value}")
}

/** Тело ответа разбирается как JSON: у списка моделей другого формата не бывает. */
private suspend fun HttpResponse.bodyIsJson(): Boolean =
    runCatching { Json.parseToJsonElement(bodyAsText()) }.isSuccess
