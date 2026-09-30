package dev.aide.agent

import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.channels.Channel

/**
 * Модель с заданной последовательностью ответов (О-11).
 *
 * [beforeResponse] вызывается перед выдачей ответа с его номером: так тест держит
 * вызов открытым и успевает отправить паузу или стоп ровно между шагами, не полагаясь
 * на задержки. [awaitCall] — барьер: тест ждёт начала нужного вызова, а не спит.
 */
class ScriptedLlmClient(
    private val responses: List<LlmResponse>,
    private val beforeResponse: suspend (Int) -> Unit = {},
) : LlmClient {

    private val started = Channel<Int>(Channel.UNLIMITED)
    private val counter = AtomicInteger(0)

    /** Запросы в порядке вызова; нужны тестам, чтобы проверить, что шаг не повторялся. */
    val requests = mutableListOf<LlmRequest>()

    override suspend fun complete(request: LlmRequest): LlmResponse {
        val index = counter.getAndIncrement()
        requests += request
        started.trySend(index)
        beforeResponse(index)
        return responses.getOrElse(index) { responses.last() }
    }

    /** Ждёт начала вызова с номером [index]. */
    suspend fun awaitCall(index: Int) {
        while (true) {
            if (started.receive() == index) return
        }
    }

    /** Сколько вызовов модели сделано. */
    val callCount: Int get() = counter.get()
}
