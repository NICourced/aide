package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.ClientPlatform
import dev.aide.domain.DecisionScope
import dev.aide.domain.DecisionValue
import dev.aide.domain.PacketId
import dev.aide.domain.Permission
import dev.aide.domain.TaskId
import dev.aide.domain.TaskStatus
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolPermission
import dev.aide.host.store.db.HostDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlinx.datetime.Instant

class HostStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))

    @AfterTest
    fun tearDown() {
        driver.close()
    }

    @Test
    fun `задача переживает запись и чтение`() {
        store.tasks.save(StoreFixtures.task)
        assertEquals(StoreFixtures.task, store.tasks.load(StoreFixtures.task.id))
    }

    @Test
    fun `неизвестная задача читается как null`() {
        assertNull(store.tasks.load(TaskId("нет-такой")))
    }

    @Test
    fun `повторная запись задачи заменяет прежнюю`() {
        store.tasks.save(StoreFixtures.task)
        store.tasks.save(StoreFixtures.task.copy(status = TaskStatus.ACCEPTED))

        assertEquals(1L, store.tasks.count())
        assertEquals(TaskStatus.ACCEPTED, store.tasks.load(StoreFixtures.task.id)?.status)
    }

    @Test
    fun `задачи читаются по статусу от новых к старым`() {
        val older = StoreFixtures.task.copy(
            id = TaskId("t-0"),
            title = "Старая задача",
            createdAt = Instant.parse("2026-09-21T10:00:00Z"),
        )
        store.tasks.save(older)
        store.tasks.save(StoreFixtures.task)
        store.tasks.save(StoreFixtures.queuedTask)

        assertEquals(listOf(StoreFixtures.queuedTask), store.tasks.byStatus(TaskStatus.QUEUED))
        assertEquals(listOf(StoreFixtures.task, older), store.tasks.byStatus(TaskStatus.REVIEW))
    }

    @Test
    fun `удаление задачи не удаляет её прогоны`() {
        store.tasks.save(StoreFixtures.task)
        store.runs.save(StoreFixtures.run)

        store.tasks.delete(StoreFixtures.task.id)

        assertNull(store.tasks.load(StoreFixtures.task.id))
        assertEquals(StoreFixtures.run, store.runs.load(StoreFixtures.run.id))
    }

    @Test
    fun `прогон переживает запись и чтение`() {
        store.runs.save(StoreFixtures.run)
        assertEquals(StoreFixtures.run, store.runs.load(StoreFixtures.run.id))
    }

    @Test
    fun `прогоны читаются по задаче в порядке запуска`() {
        store.runs.save(StoreFixtures.run)
        store.runs.save(StoreFixtures.runWithUnknownCost)

        assertEquals(
            listOf(StoreFixtures.run, StoreFixtures.runWithUnknownCost),
            store.runs.byTask(StoreFixtures.run.taskId),
        )
    }

    @Test
    fun `стоимость задачи считается по прогонам`() {
        store.runs.save(StoreFixtures.run)
        store.runs.save(StoreFixtures.runWithUnknownCost)

        val total = store.runs.totalCost(StoreFixtures.run.taskId)
        assertEquals(12_500, total.amountMicros)
        assertFalse(total.known, "Есть прогон с неизвестной ценой — итог помечается неполным (FR-COST-5)")
    }

    @Test
    fun `стоимость известна, когда у всех прогонов есть цена`() {
        store.runs.save(StoreFixtures.run)

        val total = store.runs.totalCost(StoreFixtures.run.taskId)
        assertEquals(12_500, total.amountMicros)
        assertTrue(total.known)
    }

    @Test
    fun `вызовы инструментов читаются по прогону`() {
        store.runs.save(StoreFixtures.run)
        store.toolCalls.save(StoreFixtures.toolCall)

        val calls = store.toolCalls.forRun(StoreFixtures.run.id)
        assertEquals(listOf(StoreFixtures.toolCall), calls)
    }

    @Test
    fun `журнал фильтруется по инструменту`() {
        store.runs.save(StoreFixtures.run)
        store.toolCalls.save(StoreFixtures.toolCall)
        store.toolCalls.save(
            StoreFixtures.toolCall.copy(id = ToolCallId("tc-2"), tool = "git.commit"),
        )

        assertEquals(1, store.toolCalls.forRun(StoreFixtures.run.id, tool = "fs.write").size)
        assertEquals(2, store.toolCalls.forRun(StoreFixtures.run.id).size)
    }

    @Test
    fun `решения ревью читаются по пакету и по ревизии`() {
        store.decisions.save(StoreFixtures.decision)
        store.decisions.save(StoreFixtures.decision.copy(packetRevision = 2, value = DecisionValue.ACCEPTED))

        assertEquals(2, store.decisions.forPacket(PacketId("p-1")).size)
        val revisionOne = store.decisions.forPacket(PacketId("p-1"), revision = 1)
        assertEquals(listOf(StoreFixtures.decision), revisionOne)
    }

    @Test
    fun `платформа клиента сохраняется и участвует в метрике`() {
        store.decisions.save(StoreFixtures.decision)
        store.decisions.save(
            StoreFixtures.decision.copy(
                packetId = PacketId("p-2"),
                clientPlatform = ClientPlatform.DESKTOP_LINUX,
            ),
        )

        assertEquals(1, store.decisions.byPlatform(ClientPlatform.ANDROID).size)
        assertEquals(1, store.decisions.byPlatform(ClientPlatform.DESKTOP_LINUX).size)
        assertEquals(
            mapOf("ANDROID" to 1L, "DESKTOP_LINUX" to 1L),
            store.decisions.countsByPlatform(),
        )
    }

    @Test
    fun `решения на уровне пакета сохраняются с пустым идентификатором блока`() {
        store.decisions.save(StoreFixtures.packetLevelDecision)

        val loaded = store.decisions.forPacket(PacketId("p-1")).single()
        assertEquals(DecisionScope.PACKET, loaded.scope)
        assertNull(loaded.targetHunkId)
        assertNull(loaded.comment)
    }

    @Test
    fun `права на инструменты переживают запись и чтение`() {
        val permission = ToolPermission(tool = "fs.write", read = Permission.ALLOW, write = Permission.ASK)
        store.permissions.save(permission)

        assertEquals(permission, store.permissions.load("fs.write"))
        assertEquals(listOf(permission), store.permissions.all())
    }

    @Test
    fun `повторная запись прав обновляет, а не дублирует`() {
        store.permissions.save(ToolPermission("fs.write", Permission.ALLOW, Permission.ASK))
        store.permissions.save(ToolPermission("fs.write", Permission.ALLOW, Permission.DENY))

        assertEquals(Permission.DENY, store.permissions.load("fs.write")?.write)
        assertEquals(1, store.permissions.all().size)
    }
}
