package dev.aide.agent

import dev.aide.agent.llm.LlmResponse
import dev.aide.domain.AgentRun
import dev.aide.domain.Cost
import dev.aide.domain.RunId
import dev.aide.domain.RunState
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.datetime.Instant

/**
 * T-1.5: время прогона — длительность от старта до завершения, а не сумма времени модели.
 *
 * Проверяется на писателе состояния, потому что именно он ставит терминальные отметки:
 * через движок это доказывалось бы косвенно, а `finish`, `fail` и `stop` — три разных
 * пути, и каждый обязан выставить одно и то же поле.
 */
class RunStateWriterTest {

    private val runs = FakeRunRepository()
    private val tasks = FakeTaskRepository()
    private val sink = RecordingEventSink(runs, tasks)

    /** Часы, которые двигает тест: иначе длина прогона осталась бы нулевой. */
    private var now = Instant.fromEpochMilliseconds(START_MILLIS)

    private val writer = RunStateWriter(runs, tasks, sink, { now }, Mutex())

    private fun run(): AgentRun =
        testRun("r-1", RunState.RUNNING).copy(startedAt = Instant.fromEpochMilliseconds(START_MILLIS))

    @Test
    fun `накопление складывает стоимость, но не время ответа модели`() {
        runBlocking {
            val updated = writer.accumulate(
                run(),
                LlmResponse.Text(text = "готово", cost = Cost(amountMicros = 5, known = true), elapsedMillis = 9_000),
            )

            assertEquals(Cost(amountMicros = 5, known = true), updated.cost)
            assertEquals(0, updated.elapsedMillis, "время модели больше не подменяет длительность прогона")
            assertEquals(updated, runs.load(RunId("r-1")), "стоимость обязана быть сохранена")
        }
    }

    @Test
    fun `завершение выставляет длительность от старта до finishedAt`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS)

            val finished = writer.finish(run())

            assertEquals(RunState.FINISHED, finished.state)
            assertDurationOf(finished)
        }
    }

    @Test
    fun `отказ выставляет длительность и причину`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS)

            val failed = writer.fail(run(), "unexpected")

            assertEquals(RunState.FAILED, failed.state)
            assertEquals("unexpected", failed.interruptReason)
            assertDurationOf(failed)
        }
    }

    @Test
    fun `остановка выставляет длительность и причину`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS)

            val stopped = writer.stop(run())

            assertEquals(RunState.STOPPED, stopped.state)
            assertEquals(RunInterruptReason.USER_STOP, stopped.interruptReason)
            assertDurationOf(stopped)
        }
    }

    @Test
    fun `смена состояния посреди прогона несёт накопленное время, а не ноль`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS)

            val paused = writer.persist(run().copy(state = RunState.PAUSED))

            assertEquals(DURATION_MILLIS, paused.elapsedMillis)
            assertEquals(
                DURATION_MILLIS,
                sink.events.last().elapsedMillis,
                "событие смены состояния обязано нести свежее время, иначе клиент покажет 0 с",
            )
            assertEquals(DURATION_MILLIS, runs.load(paused.id)?.elapsedMillis)
        }
    }

    @Test
    fun `завершённый прогон не пересчитывается повторной записью`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS)
            val finished = writer.finish(run())

            now = Instant.fromEpochMilliseconds(START_MILLIS + DURATION_MILLIS * 3)
            writer.persist(finished)

            assertEquals(
                DURATION_MILLIS,
                runs.load(finished.id)?.elapsedMillis,
                "итог завершённого прогона фиксируется и больше не меняется",
            )
        }
    }

    @Test
    fun `отрицательное время не попадает в прогон`() {
        runBlocking {
            now = Instant.fromEpochMilliseconds(START_MILLIS - 500)

            val paused = writer.persist(run().copy(state = RunState.PAUSED))
            val finished = writer.finish(paused)

            assertEquals(0, paused.elapsedMillis, "время идущего прогона отбрасывается к нулю в источнике")
            assertEquals(0, finished.elapsedMillis, "терминальная длительность тоже не бывает отрицательной")
        }
    }

    /** Длительность равна расстоянию между стартом и меткой завершения — и в базе тоже. */
    private fun assertDurationOf(run: AgentRun) {
        val finishedAt = assertNotNull(run.finishedAt, "терминальный переход обязан поставить finishedAt")
        assertEquals(
            (finishedAt - run.startedAt).inWholeMilliseconds,
            run.elapsedMillis,
            "длительность прогона — finishedAt − startedAt (FR-AGENT-10)",
        )
        assertEquals(DURATION_MILLIS, run.elapsedMillis)
        assertEquals(run, runs.load(run.id), "итог обязан быть в хранилище")
    }

    private companion object {
        /** Момент старта прогона в тесте. */
        const val START_MILLIS: Long = 10_000

        /** Сколько «идёт» прогон к моменту завершения. */
        const val DURATION_MILLIS: Long = 3_000
    }
}
