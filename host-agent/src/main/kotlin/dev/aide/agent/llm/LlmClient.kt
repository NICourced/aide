package dev.aide.agent.llm

import dev.aide.domain.Cost
import dev.aide.domain.ModelFailureCode

/**
 * Запрос к модели.
 *
 * Системная часть вынесена отдельно от диалога: у провайдеров она занимает своё
 * место в теле запроса. Сообщения — просто строки: в этапе 1 диалог состоит только
 * из реплик пользователя (системная роль вынесена), а роли появятся вместе с
 * потоковым чатом (T-1.40) — до тех пор перечисление из одного значения было бы
 * мёртвым. Потоковой передачи нет (О-2): движок ждёт ответ целиком.
 */
data class LlmRequest(
    /** Системная часть: роль агента и правила. */
    val system: String,
    /** Реплики пользователя в порядке следования. */
    val messages: List<String>,
)

/**
 * Ответ модели: либо текст, либо типизированная ошибка.
 *
 * Ошибка — вариант ответа, а не исключение: исключение наружу пересекло бы границу
 * модуля, а движок обязан перевести неудачу в состояние `FAILED`, а не упасть (О-9).
 */
sealed interface LlmResponse {

    /** Успешный ответ. */
    data class Text(
        /** Текст ответа модели. */
        val text: String,
        /** Токены и стоимость; нет данных — [Cost.known] = false, а не ноль (FR-COST-5). */
        val cost: Cost,
        /** Сколько длился вызов. */
        val elapsedMillis: Long,
    ) : LlmResponse

    /** Провайдер не ответил: причина — код, понятный текст строит UI (NFR-13). */
    data class Error(
        /** Почему не получилось. */
        val kind: LlmErrorKind,
        /** Техническая деталь для лога; в UI не показывается. */
        val detail: String? = null,
    ) : LlmResponse
}

/**
 * Почему модель не ответила: коды ответа провайдера.
 *
 * Здесь только то, что производит **транспорт**. «Модель не настроена» в это перечисление
 * не входит: такой отказ рождается раньше вызова — при выборе модели в реестре
 * ([dev.aide.domain.ModelCheckFailure.NotConfigured]), и значение без производителя было бы
 * долгом. Код отказа движок записывает в причину прогона, строку для пользователя
 * строит UI из ресурсов (NFR-13).
 */
enum class LlmErrorKind {
    /** Ключ отвергнут провайдером (401, 403). */
    UNAUTHORIZED,

    /** Провайдер ответил, что лимит запросов исчерпан (429). */
    RATE_LIMITED,

    /** Сеть недоступна или сервер ответил ошибкой (5xx, разрыв соединения). */
    REQUEST_FAILED,

    /** Ответ провайдера не разобран: не JSON или в нём нет текста модели. */
    RESPONSE_UNREADABLE,
}

/** Клиент модели для движка прогона: один вызов — один ответ (О-2). */
interface LlmClient {

    /** Выполняет запрос и возвращает ответ целиком. */
    suspend fun complete(request: LlmRequest): LlmResponse
}

/**
 * Код причины отказа провайдера для строки состояния (NFR-13).
 *
 * Тот же словарь, что у отказа выбрать модель ([dev.aide.domain.ModelFailureCode]):
 * клиент показывает одно и то же объяснение, откуда бы отказ ни пришёл — из реестра
 * провайдеров или из ответа сети.
 */
val LlmErrorKind.code: String
    get() = when (this) {
        LlmErrorKind.UNAUTHORIZED -> ModelFailureCode.UNAUTHORIZED
        LlmErrorKind.RATE_LIMITED -> ModelFailureCode.RATE_LIMITED
        LlmErrorKind.REQUEST_FAILED -> ModelFailureCode.REQUEST_FAILED
        LlmErrorKind.RESPONSE_UNREADABLE -> ModelFailureCode.RESPONSE_UNREADABLE
    }

/**
 * Ошибка вызова модели, поднятая внутри рантайма агента; наружу не пересекает границу модуля.
 *
 * В сообщение попадает техническая деталь: код причины сохраняется в состоянии, но
 * диагностировать отказ провайдера без детали нечем, и её надо видеть в логе.
 */
class LlmCallException(val error: LlmResponse.Error) : Exception(
    listOfNotNull(error.kind.name, error.detail).joinToString(": "),
)
