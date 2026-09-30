package dev.aide.host.agent

import dev.aide.agent.AgentRunEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlin.math.min
import kotlinx.coroutines.launch
import org.slf4j.LoggerFactory

/**
 * Воркер очереди: единственное место, где прогоны запускаются (решение 1).
 *
 * Ждёт сигнала о постановке задачи и выполняет очередь до конца. Прогон идёт в
 * отдельной корутине внутри движка, поэтому отмена прогона не завершает сам воркер.
 *
 * Сбой обработки одной задачи не должен останавливать очередь, но и не должен
 * превращаться в холостой цикл: путь ошибки обязан включать приостановку. Пока
 * хранилище недоступно, задача у головы очереди остаётся `QUEUED`, и без задержки
 * воркер крутил бы её на полной загрузке ядра, заливая журнал. Пауза с ростом
 * позволяет восстановиться, когда отказ временный, и не жжёт процессор, когда он
 * постоянный. Пометить задачу `FAILED` здесь нельзя: отказывает то же хранилище,
 * через которое шла бы и эта запись.
 *
 * @param initialRetryMillis пауза перед повтором после первого сбоя.
 * @param maxRetryMillis потолок роста паузы.
 */
class RunWorker(
    private val engine: AgentRunEngine,
    private val initialRetryMillis: Long = INITIAL_RETRY_MILLIS,
    private val maxRetryMillis: Long = MAX_RETRY_MILLIS,
) {

    private val logger = LoggerFactory.getLogger(RunWorker::class.java)

    /** Сбои подряд: обращение к движку сбрасывает счётчик, а он задаёт длину паузы. */
    private var consecutiveFailures = 0

    /** Запускает цикл; возвращает job, отменив который хост останавливает воркер. */
    fun start(scope: CoroutineScope): Job = scope.launch {
        while (true) {
            engine.taskWakeUp.receive()
            drain()
        }
    }

    /** Выполняет задачи, пока очередь не опустеет; следующая задача ждёт завершения текущей. */
    private suspend fun drain() {
        var hasTask = processNextSafely()
        while (hasTask) {
            hasTask = processNextSafely()
        }
    }

    /** Обрабатывает одну задачу; отмена хоста проходит насквозь, сбой даёт паузу перед повтором. */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun processNextSafely(): Boolean = try {
        engine.processNext().also { consecutiveFailures = 0 }
    } catch (error: Exception) {
        currentCoroutineContext().ensureActive()
        consecutiveFailures += 1
        val wait = backoffMillis(consecutiveFailures)
        logger.warn(
            "Сбой обработки задачи (подряд $consecutiveFailures), повтор через $wait мс: ${error.message}",
            error,
        )
        delay(wait)
        true
    }

    /**
     * Экспоненциальный рост с потолком — та же идиома, что в `KtorHostConnection`:
     * частые повторы вначале, редкие — при долгом отказе.
     */
    private fun backoffMillis(attempt: Int): Long {
        var millis = initialRetryMillis
        repeat(attempt - 1) { millis = min(millis * 2, maxRetryMillis) }
        return min(millis, maxRetryMillis)
    }

    private companion object {
        /** Пауза перед первым повтором. */
        const val INITIAL_RETRY_MILLIS: Long = 200

        /** Потолок роста паузы. */
        const val MAX_RETRY_MILLIS: Long = 5_000
    }
}
