package dev.aide.agent

import dev.aide.agent.ports.AgentEventSink
import dev.aide.agent.ports.RunRepository
import dev.aide.domain.AgentRun
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import dev.aide.domain.Task
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Instant

/**
 * T-1.5: таймер прогресса рассылает время и стоимость, не пишет в базу и не роняет прогон.
 *
 * Интервал короткий, часы двигает тест: иначе проверка ждала бы секунду на каждый тик.
 * Прогон лежит в фейковом хранилище — таймеру нужен только текущий прогон, база здесь ни к чему.
 */
class RunProgressTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)

    /** Часы, которые двигает тест; старт прогона — в [startedAt]. */
    private var now = INSTANT

    private val progress = RunProgress(runs, sink, { now }, Mutex(), INTERVAL_MILLIS)

    /** Идущий прогон, стартовавший в [INSTANT]: от него и считается длительность. */
    private fun runningRun(): AgentRun = testRun("r-1", RunState.RUNNING).copy(startedAt = INSTANT)

    @Test
    fun `прогресс приходит по интервалу, и время растёт`() {
        runBlocking {
            val run = runningRun()
            runs.save(run)

            val job = progress.start(run.id)
            try {
                // Два тика за секунду при интервале в миллисекунды: рассылка периодическая,
                // а не одно событие на старте.
                awaitProgressAtLeast(2)
                val before = sink.progress.first()
                now = Instant.fromEpochMilliseconds(INSTANT.toEpochMilliseconds() + ELAPSED_MILLIS)

                // Ждём именно событие с нужным временем, а не «третье по счёту»: тик с
                // интервалом в миллисекунды мог успеть прислать ещё несколько событий.
                val after = awaitProgressValue(ELAPSED_MILLIS)
                assertNotNull(
                    after,
                    "прогресс с временем $ELAPSED_MILLIS мс обязан прийти: ${sink.progress.map { it.elapsedMillis }}",
                )
                assertEquals(run.id, after.runId, "прогресс адресован своему прогону")
                assertTrue(
                    after.elapsedMillis > before.elapsedMillis,
                    "время обязано расти: ${sink.progress.map { it.elapsedMillis }}",
                )
                assertEquals(Cost(), after.cost, "стоимость берётся из прогона как есть")
                // Второй тик с тем же временем не появляется: часы не двигались.
                assertEquals(
                    after.elapsedMillis,
                    sink.progress.last().elapsedMillis,
                    "без движения часов время не меняется",
                )
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `после завершения прогона прогресс прекращается сам`() {
        runBlocking {
            val run = runningRun()
            runs.save(run)
            val job = progress.start(run.id)
            awaitProgressAtLeast(1)

            runs.save(run.copy(state = RunState.FINISHED, finishedAt = now))
            val finished = withTimeoutOrNull(WAIT_MILLIS) { job.join() }
            assertNotNull(finished, "таймер обязан завершиться, увидев finishedAt, а не крутиться вечно")

            val emitted = sink.progress.size
            delay(INTERVAL_MILLIS * SETTLE_INTERVALS)
            assertEquals(emitted, sink.progress.size, "после завершения новых событий быть не должно")
        }
    }

    @Test
    fun `сбой рассылки не роняет таймер и не выходит наружу`() {
        runBlocking {
            var attempts = 0
            val failing = object : AgentEventSink {
                override suspend fun runStateChanged(run: AgentRun) = Unit

                override suspend fun taskStateChanged(task: Task) = Unit

                override suspend fun runProgress(runId: RunId, elapsedMillis: Long, cost: Cost) {
                    attempts += 1
                    if (attempts == 1) error("сеть недоступна")
                }
            }
            val run = runningRun()
            runs.save(run)
            val job = RunProgress(runs, failing, { now }, Mutex(), INTERVAL_MILLIS).start(run.id)
            try {
                withTimeout(WAIT_MILLIS) {
                    while (attempts < 2) delay(POLL_MILLIS)
                }
                assertTrue(job.isActive, "после сбоя таймер обязан продолжать работу")
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `сбой чтения не роняет родительскую корутину и повторяется на следующем тике`() {
        runBlocking {
            val run = runningRun()
            runs.save(run)
            // Таймер запускается дочерней корутиной к текущей: если исключение чтения выйдет
            // наружу, оно отменит родителя — и прогон остался бы RUNNING без итоговой записи.
            val flaky = FlakyRunRepository(runs, failures = 2)
            val job = RunProgress(flaky, sink, { now }, Mutex(), INTERVAL_MILLIS).start(run.id)
            try {
                awaitProgressAtLeast(1)
                assertTrue(flaky.attempts >= 3, "после сбоев чтение обязано повториться: ${flaky.attempts}")
                assertTrue(job.isActive, "сбой чтения не должен останавливать таймер")
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `время раньше старта не превращается в минус`() {
        runBlocking {
            val run = runningRun()
            runs.save(run)
            now = Instant.fromEpochMilliseconds(INSTANT.toEpochMilliseconds() - 500)
            val job = progress.start(run.id)
            try {
                awaitProgressAtLeast(1)
                assertTrue(
                    sink.progress.all { it.elapsedMillis == 0L },
                    "отрицательное время отбрасывается к нулю в источнике: ${sink.progress.map { it.elapsedMillis }}",
                )
            } finally {
                job.cancel()
            }
        }
    }

    @Test
    fun `отменённый таймер больше не рассылает прогресс`() {
        runBlocking {
            val run = runningRun()
            runs.save(run)
            val job: Job = progress.start(run.id)
            awaitProgressAtLeast(1)

            job.cancel()
            assertIs<Unit>(withTimeoutOrNull(WAIT_MILLIS) { job.join() }, "отмена обязана завершить таймер")

            val emitted = sink.progress.size
            delay(INTERVAL_MILLIS * SETTLE_INTERVALS)
            assertEquals(emitted, sink.progress.size, "отменённый таймер не переживает прогон")
        }
    }

    @Test
    fun `интервал прогресса укладывается в требование двух секунд`() {
        // Требование FR-AGENT-10: значения обновляются не реже раза в две секунды. Периодичность
        // самого таймера проверена выше; здесь зафиксировано, что боевой интервал не вырос.
        assertTrue(
            RUN_PROGRESS_INTERVAL_MILLIS <= 2_000,
            "интервал прогресса $RUN_PROGRESS_INTERVAL_MILLIS мс нарушает требование «не реже раза в 2 с»",
        )
    }

    /** Ждёт, пока приёмник получит не меньше [count] событий прогресса. */
    private suspend fun awaitProgressAtLeast(count: Int) {
        withTimeout(WAIT_MILLIS) {
            while (sink.progress.size < count) delay(POLL_MILLIS)
        }
    }

    /** Ждёт событие с точно заданным временем: тик мог прислать ещё несколько событий. */
    private suspend fun awaitProgressValue(elapsedMillis: Long): RecordedProgress? = withTimeoutOrNull(WAIT_MILLIS) {
        var found = sink.progress.lastOrNull { it.elapsedMillis == elapsedMillis }
        while (found == null) {
            delay(POLL_MILLIS)
            found = sink.progress.lastOrNull { it.elapsedMillis == elapsedMillis }
        }
        found
    }

    /**
     * Хранилище, у которого первые [failures] чтений падают.
     *
     * На нём проверяется главное: сбой чтения в таймере не выходит наружу и не отменяет
     * корутину прогона; после восстановления прогресс продолжается.
     */
    private class FlakyRunRepository(
        private val delegate: FakeRunRepository,
        private var failures: Int,
    ) : RunRepository {

        /** Сколько раз таймер пытался прочитать прогон. */
        var attempts = 0
            private set

        override fun save(run: AgentRun) = delegate.save(run)

        override fun load(id: RunId): AgentRun? {
            attempts += 1
            if (failures > 0) {
                failures -= 1
                error("база недоступна")
            }
            return delegate.load(id)
        }

        override fun unfinished(): List<AgentRun> = delegate.unfinished()
    }

    private companion object {
        /** Интервал теста: короче секунды, чтобы проверка не ждала реальные тики. */
        const val INTERVAL_MILLIS: Long = 10

        /** Момент старта прогона в тесте. */
        val INSTANT: Instant = Instant.fromEpochMilliseconds(1_000)

        /** Сколько интервалов наблюдать за тишиной после завершения. */
        const val SETTLE_INTERVALS: Long = 3

        /** Предел ожидания события: с большим запасом к [INTERVAL_MILLIS]. */
        const val WAIT_MILLIS: Long = 2_000

        /** Шаг опроса в ожиданиях. */
        const val POLL_MILLIS: Long = 2

        /** Насколько тест двигает часы: столько прогон и «идёт». */
        const val ELAPSED_MILLIS: Long = 1_500
    }
}
