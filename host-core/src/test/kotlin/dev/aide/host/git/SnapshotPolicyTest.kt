package dev.aide.host.git

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-1.19: вытеснение старых снапшотов — по количеству, с защитой используемых.
 *
 * Политика чистая, поэтому проверяется без git и хранилища: списком ссылок
 * и множеством «в использовании».
 */
class SnapshotPolicyTest {

    @Test
    fun `последние пятьдесят снапшотов не вытесняются`() {
        // Числа здесь и ниже — из критерия T-1.19, а не из константы политики: тест,
        // сверяющийся с той же константой, что и код, не заметил бы её изменения.
        assertTrue(SnapshotPolicy.evictable(refs(50), inUse = emptySet()).isEmpty())
    }

    @Test
    fun `пятьдесят первый снапшот вытесняет самый старый`() {
        val refs = refs(51)

        assertEquals(listOf(refs.first()), SnapshotPolicy.evictable(refs, inUse = emptySet()))
    }

    @Test
    fun `снапшот незакрытой задачи остаётся, даже когда он самый старый`() {
        val refs = refs(51)

        // Защищён самый старый: уходит следующий за ним, а не он сам — иначе обещание
        // «откат к последнему снапшоту» для незакрытой задачи стало бы пустым.
        assertEquals(listOf(refs[1]), SnapshotPolicy.evictable(refs, inUse = setOf(refs.first())))
    }

    @Test
    fun `защищённых больше переполнения — остаются все защищённые`() {
        val refs = refs(53)
        val protected = refs.take(5).toSet()

        assertEquals(listOf(refs[5], refs[6], refs[7]), SnapshotPolicy.evictable(refs, inUse = protected))
    }

    /** [count] ссылок с возрастающими метками — от старых к новым. */
    private fun refs(count: Int): List<String> =
        (0 until count).map { snapshotRefName(BASE_MILLIS + it, "before-agent-step") }

    private companion object {
        /** Базовая метка времени: значение не важно, важна только возрастающая последовательность. */
        const val BASE_MILLIS = 1_758_535_200_000L
    }
}
