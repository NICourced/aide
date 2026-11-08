package dev.aide.agent

import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.RunId
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.datetime.Instant
import org.slf4j.LoggerFactory

/**
 * Интервал рассылки прогресса прогона (T-1.5).
 *
 * Секунда даёт двукратный запас к требованию «значения обновляются не реже раза в две
 * секунды» (FR-AGENT-10) и остаётся дешёвой: событие несёт три поля, а не полный прогон.
 */
internal const val RUN_PROGRESS_INTERVAL_MILLIS: Long = 1_000

/**
 * Таймер прогресса прогона: время и стоимость раз в [intervalMillis] (T-1.5).
 *
 * Отдельный класс, как [PlanGate] и [RunSnapshots]: у движка и писателя состояния уже
 * по десять функций, и таймер — самостоятельная забота, а не переход состояния.
 *
 * Тик ничего не пишет в базу: `elapsedMillis` выводится из `startedAt`, а стоимость уже
 * сохранена ответом модели. Секундная запись в SQLite ради выводимого значения была бы
 * лишней и к тому же могла бы перезаписать более новую запись — поэтому чтение идёт под
 * общим замком писателя, но рассылка выполняется уже без замка.
 *
 * Сбой таймера не роняет прогон: событие прогресса — украшение, а прогон — работа.
 * Отличие от журнала вызовов (T-1.3) осознано: журнал отключать нельзя, его отсутствие
 * означает потерю аудита, а пропущенный тик восполнится следующим. Ловится и сбой
 * **чтения**, не только рассылки: таймер — дочерняя корутина прогона, и исключение,
 * вышедшее наружу, отменило бы родителя, оставив прогон `RUNNING` в базе. Сбой чтения
 * повторяется на следующем тике, а не останавливает таймер: одна неудачная выборка ещё
 * не говорит о том, что прогон завершён.
 *
 * Таймер запускается после снапшота начала прогона, поэтому первые доли секунды (до
 * первого тика) прогресса нет. В накопленное время снапшот входит: `startedAt`
 * записывается раньше, — так что потерянного времени нет, есть только задержка показа.
 *
 * @param runs чтение текущего прогона; запись таймер не делает.
 * @param events приёмник события прогресса.
 * @param clock источник времени; подменяется в тестах.
 * @param lock общий с [RunStateWriter] замок: прогон читается не посередине записи.
 * @param intervalMillis интервал рассылки; в тестах короче секунды.
 */
internal class RunProgress(
    private val runs: RunRepository,
    private val events: AgentEventSink,
    private val clock: () -> Instant,
    private val lock: Mutex,
    private val intervalMillis: Long = RUN_PROGRESS_INTERVAL_MILLIS,
) {

    private val logger = LoggerFactory.getLogger(RunProgress::class.java)

    /**
     * Запускает таймер и возвращает его корутину; вызывающий обязан отменить её в `finally`.
     *
     * Корутина — дочерняя к текущей, и `coroutineScope` вокруг прогона ждёт всех детей.
     * Поэтому без [Job.cancel] в `finally` прогон не завершился бы, пока таймер не увидит
     * `finishedAt` на следующем тике, а при остановленном прогоне, который так и не записал
     * финал, — не завершился бы вовсе. Отмена — обязанность вызывающего, и она стоит в
     * `finally` обхода прогона, а не в теле таймера.
     */
    suspend fun start(runId: RunId): Job = CoroutineScope(currentCoroutineContext()).launch {
        while (isActive) {
            delay(intervalMillis)
            if (!tick(runId)) break
        }
    }

    /**
     * Один тик; false — прогон завершён или исчез, и прогресс больше не нужен.
     *
     * Сбой чтения ловится здесь, а не в [emitProgress]: исключение из дочерней корутины
     * отменило бы корутину прогона, и тот остался бы `RUNNING` без итоговой записи.
     * После сбоя таймер продолжает тикать — следующая выборка может пройти.
     */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun tick(runId: RunId): Boolean {
        val run = try {
            lock.withLock { runs.load(runId) }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (error: Exception) {
            logger.warn("Прогон ${runId.value}: состояние не прочитано, повтор на следующем тике", error)
            return true
        }
        return if (run == null || run.finishedAt != null) {
            false
        } else {
            emitProgress(run)
            true
        }
    }

    @Suppress("TooGenericExceptionCaught")
    private suspend fun emitProgress(run: AgentRun) {
        val elapsedMillis = (clock() - run.startedAt).inWholeMilliseconds.coerceAtLeast(0)
        try {
            events.runProgress(run.id, elapsedMillis, run.cost)
        } catch (cancel: CancellationException) {
            // Отмена таймера — не сбой: она обязана дойти до корутины, иначе `finally`
            // прогона не увидит, что таймер остановлен.
            throw cancel
        } catch (error: Exception) {
            // Ловится всё, что бросил транспорт: у приёмника нет общего типа отказа,
            // а пропущенный тик не должен превращаться в отказ прогона.
            logger.warn("Прогон ${run.id.value}: прогресс не разослан", error)
        }
    }
}
