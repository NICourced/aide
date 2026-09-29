package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestDedupCacheTest {

    private val req = RequestId("req-1")

    @Test
    fun `неизвестный запрос не найден`() {
        assertNull(RequestDedupCache().get(req))
    }

    @Test
    fun `сохранённый ответ возвращается тем же`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cache.get(req))
    }

    @Test
    fun `повторное сохранение того же запроса не создаёт вторую запись`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1))
        cache.put(req, byteArrayOf(1))
        assertEquals(1, cache.size)
    }

    @Test
    fun `при переполнении вытесняется самый старый запрос`() {
        val cache = RequestDedupCache(capacity = 2)
        cache.put(RequestId("a"), byteArrayOf(1))
        cache.put(RequestId("b"), byteArrayOf(2))
        cache.put(RequestId("c"), byteArrayOf(3))

        assertNull(cache.get(RequestId("a")), "Самый старый запрос 'a' должен быть вытеснен")
        assertContentEquals(byteArrayOf(2), cache.get(RequestId("b")))
        assertContentEquals(byteArrayOf(3), cache.get(RequestId("c")))
        assertEquals(2, cache.size)
    }

    @Test
    fun `обращение к записи отодвигает её вытеснение`() {
        val cache = RequestDedupCache(capacity = 2)
        cache.put(RequestId("a"), byteArrayOf(1))
        cache.put(RequestId("b"), byteArrayOf(2))
        cache.get(RequestId("a")) // 'a' становится самой свежей
        cache.put(RequestId("c"), byteArrayOf(3))

        assertContentEquals(byteArrayOf(1), cache.get(RequestId("a")))
        assertNull(cache.get(RequestId("b")))
    }

    @Test
    fun `очистка убирает все записи`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(req))
    }
}
