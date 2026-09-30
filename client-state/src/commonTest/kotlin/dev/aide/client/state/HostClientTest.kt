package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostEvent
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Обновление сессии после открытия воркспейса проверяется на обоих порядках: ответ раньше
 * события и событие раньше ответа. Второй порядок — не экзотика: хост отправляет ответ
 * и рассылает [HostEvent.WorkspaceChanged] подряд, а клиент разбирает их в разных корутинах,
 * и порядок обработки не определён.
 */
class HostClientTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val connection = FakeHostConnection()
    private lateinit var client: HostClient

    @BeforeTest
    fun setUp() {
        client = HostClient(connection, scope, requestIdPrefix = "test")
        client.start()
    }

    @AfterTest
    fun tearDown() {
        scope.cancel()
    }

    @Test
    fun `событие об открытии, пришедшее раньше ответа, не теряется`() {
        runBlocking {
            connection.beforeRespond = { message ->
                if (message is ClientMessage.OpenWorkspace) {
                    // Порядок «событие раньше ответа» держит барьер, а не задержка: обработчик
                    // разбирает события по порядку, поэтому реакция на следующее событие означает,
                    // что WorkspaceChanged уже обработан — и обработан при сессии ещё без открытого
                    // воркспейса. Именно в этом порядке событие раньше и терялось.
                    connection.emit(HostEvent.WorkspaceChanged(connection.workspaceId))
                    connection.emit(HostEvent.HostShuttingDown)
                    client.hostShuttingDown.first { shuttingDown -> shuttingDown }
                }
            }

            val opened = client.openWorkspace(REPO_PATH)

            assertEquals(connection.workspaceId, opened)
            assertTrue(
                awaitSessionRefresh(),
                "Событие, пришедшее раньше ответа, обязано обновить сессию, а не потеряться",
            )
            assertNotNull(client.session.value.hostState, "То же обновление запрашивает и состояние хоста")

            val requests = connection.takeRequests()
            assertEquals(1, requests.count { it is ClientMessage.FileTree }, "Дерево запрашивается один раз")
            assertEquals(1, requests.count { it is ClientMessage.HostState }, "Состояние запрашивается один раз")
        }
    }

    @Test
    fun `событие об открытии обновляет сессию ровно один раз`() {
        runBlocking {
            client.openWorkspace(REPO_PATH)

            // Ответ пришёл раньше события: клиент ещё ничего не дозапрашивал.
            assertEquals(
                0,
                connection.takeRequests().count { it is ClientMessage.FileTree },
                "Открытие воркспейса не заменяет обновление по событию",
            )

            connection.emit(HostEvent.WorkspaceChanged(connection.workspaceId))

            assertTrue(awaitSessionRefresh(), "Обновление по событию обязано дойти до сессии")
            assertEquals(
                1,
                connection.takeRequests().count { it is ClientMessage.FileTree },
                "Повторное обновление того же открытия — лишний запрос дерева",
            )
        }
    }

    /** Ждёт первого обновления сессии; false означает, что обновления не было вовсе. */
    private suspend fun awaitSessionRefresh(): Boolean = withTimeoutOrNull(WAIT_MILLIS) {
        while (client.session.value.tree == null) delay(POLL_MILLIS)
        true
    } == true

    private companion object {
        const val REPO_PATH = "/projects/aide"
        const val WAIT_MILLIS = 5_000L
        const val POLL_MILLIS = 10L
    }
}
