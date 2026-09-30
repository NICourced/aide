package dev.aide.host

import io.ktor.client.HttpClient
import io.ktor.client.HttpClientConfig
import io.ktor.client.engine.HttpClientEngine
import io.ktor.client.engine.HttpClientEngineConfig
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

/**
 * Таймауты запроса к провайдеру модели (T-1.56).
 *
 * Запросы не потоковые (О-2): движок ждёт ответ целиком, а `max_tokens` в профиле модели
 * доходит до 16 тысяч — на медленном провайдере такой ответ пишется заметно дольше
 * библиотечного умолчания. Поэтому общий таймаут задан явно и щедро: он должен отсекать
 * только зависший запрос, а не медленный. Пятнадцать секунд (умолчание движка CIO) молча
 * обрывали бы честный ответ и превращали его в «провайдер не ответил».
 *
 * Сокет и общий таймаут различаются не для красоты: молчание провайдера внутри уже
 * открытого соединения — признак зависания, и ждать его минутами незачем, а вот ответ
 * целиком может идти долго.
 */
data class ProviderTimeouts(
    /** Сколько ждать установления соединения: сеть до провайдера, не ответ модели. */
    val connectMillis: Long = CONNECT_TIMEOUT_MILLIS,

    /** Сколько ждать данных внутри открытого соединения: молчание дольше — зависание. */
    val socketMillis: Long = SOCKET_TIMEOUT_MILLIS,

    /** Сколько ждать ответ целиком: длинный ответ на большой `max_tokens` — норма. */
    val requestMillis: Long = REQUEST_TIMEOUT_MILLIS,
) {

    companion object {

        /** Соединение до провайдера: секунды, а не минуты — недоступный адрес виден сразу. */
        const val CONNECT_TIMEOUT_MILLIS: Long = 30_000

        /** Пауза в молчании внутри соединения: минута тишины означает зависший ответ. */
        const val SOCKET_TIMEOUT_MILLIS: Long = 60_000

        /**
         * Ответ целиком: десять минут.
         *
         * Оценка снизу — ответ на 16 тысяч токенов при 30 токенах в секунду идёт около
         * девяти минут; это медленный, но живой провайдер, и обрывать его нельзя.
         */
        const val REQUEST_TIMEOUT_MILLIS: Long = 600_000
    }
}

/** Собирает HTTP-клиент провайдеров с явными таймаутами; движок — CIO. */
fun providerHttpClient(timeouts: ProviderTimeouts = ProviderTimeouts()): HttpClient =
    HttpClient(CIO) { installTimeouts(timeouts) }

/**
 * Тот же клиент на заданном движке — так его собирает тест.
 *
 * Тест подставляет `MockEngine`, чтобы проверить именно **эту** сборку: если плагин
 * таймаутов ставит сам тест, проверка доказывает работу плагина, а не настройку хоста.
 */
fun providerHttpClient(engine: HttpClientEngine, timeouts: ProviderTimeouts = ProviderTimeouts()): HttpClient =
    HttpClient(engine) { installTimeouts(timeouts) }

/** Ставит плагин таймаутов: одна и та же настройка у хоста и у теста. */
private fun <T : HttpClientEngineConfig> HttpClientConfig<T>.installTimeouts(timeouts: ProviderTimeouts) {
    install(HttpTimeout) {
        connectTimeoutMillis = timeouts.connectMillis
        socketTimeoutMillis = timeouts.socketMillis
        requestTimeoutMillis = timeouts.requestMillis
    }
}
