package dev.aide.protocol

/**
 * Кэш ответов по [RequestId] — механизм идемпотентности из § 8.4.
 *
 * Если клиент повторит запрос после обрыва связи (он не знает, дошёл ли запрос),
 * хост вернёт сохранённый ответ и не выполнит операцию второй раз. Порядок
 * вытеснения — LRU: обращение к записи отодвигает её вытеснение.
 *
 * Хранит не объекты, а уже закодированные байты: ответ должен уйти повторно
 * ровно в том же виде, в каком был сформирован.
 */
class RequestDedupCache(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "Ёмкость кэша должна быть положительной, получено $capacity" }
    }

    private val responses = LinkedHashMap<RequestId, ByteArray>(capacity, LOAD_FACTOR, /* accessOrder = */ true)

    /** Число сохранённых ответов. */
    val size: Int get() = responses.size

    /** Возвращает сохранённый ответ или null, если запрос ещё не выполнялся. */
    fun get(requestId: RequestId): ByteArray? = responses[requestId]

    /** Сохраняет ответ; повторное сохранение перезаписывает запись, не добавляя новую. */
    fun put(requestId: RequestId, response: ByteArray) {
        responses[requestId] = response
        while (responses.size > capacity) {
            val oldest = responses.keys.first()
            responses.remove(oldest)
        }
    }

    /** Полная очистка; вызывается при смене воркспейса, когда прежние ответы уже неактуальны. */
    fun clear() = responses.clear()

    companion object {
        /** Значение по умолчанию: с запасом покрывает запросы одного экрана ревью. */
        const val DEFAULT_CAPACITY: Int = 256

        /** Коэффициент загрузки: при переполнении вытесняет самый старый запрос (LRU). */
        private const val LOAD_FACTOR: Float = 0.75f
    }
}
