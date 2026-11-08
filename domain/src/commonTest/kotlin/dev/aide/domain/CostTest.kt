package dev.aide.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * T-1.5, FR-COST-2/5: стоимость складывается доменным правилом, и неизвестная позиция
 * делает неполным весь итог.
 *
 * Правило проверяется на домене, потому что им пользуются и писатель состояния прогона,
 * и клиент, считающий сумму по задаче: вторая копия неминуемо разошлась бы с первой.
 */
class CostTest {

    @Test
    fun `сложение складывает суммы и сохраняет полноту обеих`() {
        val total = Cost(amountMicros = 5, known = true) + Cost(amountMicros = 7, known = true)

        assertEquals(Cost(amountMicros = 12, known = true), total)
    }

    @Test
    fun `неизвестная позиция делает неполным и итог`() {
        val total = Cost(amountMicros = 5, known = true) + Cost(amountMicros = 0, known = false)

        assertEquals(Cost(amountMicros = 5, known = false), total)
        assertFalse(total.known, "ноль вместо неизвестной цены запрещён (FR-COST-5)")
    }

    @Test
    fun `стоимость задачи — сумма только её прогонов`() {
        val first = run("r-1", "t-1", Cost(amountMicros = 5, known = true))
        val second = run("r-2", "t-1", Cost(amountMicros = 7, known = true))
        val foreign = run("r-3", "t-2", Cost(amountMicros = 100, known = true))

        assertEquals(Cost(amountMicros = 12, known = true), costOfTask(listOf(first, second, foreign), TaskId("t-1")))
    }

    @Test
    fun `неизвестная цена одного прогона делает неполным итог задачи`() {
        val first = run("r-1", "t-1", Cost(amountMicros = 5, known = true))
        val second = run("r-2", "t-1", Cost(amountMicros = 0, known = false))

        val total = costOfTask(listOf(first, second), TaskId("t-1"))

        assertFalse(total.known, "итог задачи с неизвестной позицией помечается неполным (FR-COST-5)")
    }

    @Test
    fun `у задачи без прогонов стоимость известна и равна нулю`() {
        val total = costOfTask(emptyList(), TaskId("t-1"))

        assertTrue(total.known, "пустая сумма — не «неизвестно», а честный ноль")
        assertEquals(0, total.amountMicros)
    }

    /** Прогон указанной задачи: для суммы важны только задача и стоимость. */
    private fun run(id: String, taskId: String, cost: Cost): AgentRun = DomainFixtures.run.copy(
        id = RunId(id),
        taskId = TaskId(taskId),
        cost = cost,
    )
}
