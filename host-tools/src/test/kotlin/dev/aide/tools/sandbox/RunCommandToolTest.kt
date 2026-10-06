package dev.aide.tools.sandbox

import dev.aide.domain.Permission
import dev.aide.domain.RunId
import dev.aide.domain.ToolOutcome
import dev.aide.domain.ToolPermission
import dev.aide.tools.ToolInvoker
import dev.aide.tools.ToolRegistry
import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.limits.NetworkPolicy
import dev.aide.tools.testInvoker
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking

/**
 * T-1.9: `run_command` через настоящую точку вызова — отказы, сеть, окружение, таймаут.
 *
 * Проверяется наблюдаемый факт, а не «код написан»: отклонённая команда не оставляет
 * своего следа на диске (значит, процесс не стартовал), вывод доходит до результата,
 * а по таймауту процесса с записанным pid больше нет.
 */
class RunCommandToolTest {

    private val workspace = ToolsWorkspace()
    private val runId = RunId("run-command")

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `обычная команда выполняется, и её вывод виден`() {
        val result = runBlocking { invoke("""{"command":["echo","привет"]}""") }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertTrue(result.text.contains("код возврата: 0"), result.text)
        assertTrue(result.text.contains("привет"), "stdout обязан попасть в текст: ${result.text}")
    }

    @Test
    fun `абсолютный путь наружу отклоняется и команда не запускается`() {
        val result = runBlocking { invoke("""{"command":["touch","$ESCAPED_FILE","/etc/passwd"]}""") }

        assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
        assertTrue(result.text.contains("PATH_NOT_ALLOWED"), "код предела обязан быть в тексте: ${result.text}")
        assertFalse(escaped(), "файл-маркер появился бы, если бы команда запустилась")
    }

    @Test
    fun `побег через две точки отклоняется и команда не запускается`() {
        val result = runBlocking { invoke("""{"command":["touch","$ESCAPED_FILE","../снаружи.txt"]}""") }

        assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
        assertTrue(result.text.contains("PATH_NOT_ALLOWED"), result.text)
        assertFalse(escaped(), "команда с побегом через .. не должна запускаться")
    }

    @Test
    fun `путь в значении опции отклоняется и команда не запускается`() {
        val result = runBlocking { invoke("""{"command":["touch","$ESCAPED_FILE","--target=/etc/passwd"]}""") }

        assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
        assertTrue(result.text.contains("PATH_NOT_ALLOWED"), result.text)
        assertFalse(escaped(), "путь в --опция=путь обязан сверяться так же, как отдельный аргумент")
    }

    @Test
    fun `хост в аргументе при пустом списке отклоняется и команда не запускается`() {
        val result = runBlocking { invoke("""{"command":["touch","$ESCAPED_FILE","https://example.com"]}""") }

        assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
        assertTrue(result.text.contains("NETWORK_FORBIDDEN"), "код сети обязан быть в тексте: ${result.text}")
        assertFalse(escaped(), "команда с сетевым адресом не должна запускаться")
    }

    @Test
    fun `scp-подобный адрес с хостом отклоняется`() {
        val result = runBlocking {
            invoke("""{"command":["touch","$ESCAPED_FILE","git@github.com:repo.git"]}""")
        }

        assertEquals(ToolOutcome.DENIED, result.outcome, result.text)
        assertTrue(result.text.contains("NETWORK_FORBIDDEN"), result.text)
        assertFalse(escaped(), "команда с scp-адресом не должна запускаться")
    }

    @Test
    fun `разрешённый хост пропускается`() {
        val result = runBlocking {
            invoke("""{"command":["echo","https://example.com"]}""", NetworkPolicy(setOf("example.com")))
        }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertTrue(result.text.contains("example.com"), result.text)
    }

    @Test
    fun `пустая команда — отказ без запуска`() {
        val result = runBlocking { invoke("""{"command":[]}""") }

        assertEquals(ToolOutcome.FAILURE, result.outcome, result.text)
        assertTrue(result.text.contains("пустая команда"), result.text)
    }

    @Test
    fun `окружение ограничено списком, а HOME и TMPDIR внутри воркспейса`() {
        val environment = commandEnvironment(
            root = workspace.root,
            host = mapOf("PATH" to "/usr/bin", "LANG" to "ru_RU.UTF-8", SECRET_NAME to SECRET_VALUE),
        )

        assertEquals("/usr/bin", environment["PATH"], "PATH обязан передаваться: без него команда не найдёт программу")
        assertEquals("ru_RU.UTF-8", environment["LANG"])
        assertEquals(workspace.root.toString(), environment["HOME"], "HOME обязан смотреть внутрь воркспейса")
        assertEquals(workspace.root.toString(), environment["TMPDIR"], "TMPDIR обязан смотреть внутрь воркспейса")
        assertFalse(environment.containsKey(SECRET_NAME), "посторонняя переменная хоста не должна попадать команде")
    }

    @Test
    fun `команда видит HOME внутри воркспейса`() {
        val result = runBlocking { invoke("""{"command":["env"]}""") }

        assertEquals(ToolOutcome.SUCCESS, result.outcome, result.text)
        assertTrue(
            result.text.contains("HOME=${workspace.root}"),
            "команда обязана получить HOME внутри воркспейса: ${result.text}",
        )
    }

    @Test
    fun `таймаут убивает зависшую команду и её потомка`() {
        runBlocking {
            val tool = RunCommandTool(NetworkPolicy(), timeoutMillis = KILL_TIMEOUT_MILLIS)
            val invoker = invokerFor(tool)
            val arguments = """{"command":["sh","-c","$KILL_SCRIPT"]}"""

            val result = invoker.invoke(runId, RunCommandTool.TOOL_NAME, arguments, workspace.context)

            assertEquals(ToolOutcome.TIMEOUT, result.outcome, result.text)
            val parent = assertNotNull(readPid(PARENT_PID_FILE), "скрипт обязан записать pid оболочки")
            val child = assertNotNull(readPid(CHILD_PID_FILE), "скрипт обязан записать pid потомка")
            assertTrue(awaitGone(parent), "по таймауту оболочка обязана быть убита: pid $parent жив")
            assertTrue(awaitGone(child), "по таймауту потомок обязан быть убит: pid $child жив")
        }
    }

    private suspend fun invoke(arguments: String, network: NetworkPolicy = NetworkPolicy()) =
        invokerFor(RunCommandTool(network)).invoke(runId, RunCommandTool.TOOL_NAME, arguments, workspace.context)

    /** Точка вызова с разрешением записи: права здесь не проверяются, проверяются отказы инструмента. */
    private fun invokerFor(tool: RunCommandTool): ToolInvoker = testInvoker(
        registry = ToolRegistry(listOf(tool)),
        stored = { name -> ToolPermission(name, Permission.ALLOW, Permission.ALLOW) },
    )

    private fun escaped(): Boolean = workspace.root.resolve(ESCAPED_FILE).exists()

    private fun readPid(name: String): Long? {
        val file: Path = workspace.root.resolve(name)
        return if (file.exists()) file.readText().trim().toLongOrNull() else null
    }

    /** Ждёт исчезновения процесса: после SIGKILL ручка обновляется не мгновенно. */
    private suspend fun awaitGone(pid: Long): Boolean {
        val deadline = System.currentTimeMillis() + AWAIT_GONE_MILLIS
        while (System.currentTimeMillis() < deadline) {
            val handle = ProcessHandle.of(pid)
            if (handle.isEmpty || !handle.get().isAlive) return true
            delay(POLL_MILLIS)
        }
        return false
    }

    private companion object {

        /** Файл-маркер: его появление доказало бы, что отклонённая команда всё-таки запустилась. */
        const val ESCAPED_FILE: String = "escaped.txt"
        const val SECRET_NAME: String = "AIDE_FAKE_SECRET"
        const val SECRET_VALUE: String = "ключ-провайдера"
        const val KILL_TIMEOUT_MILLIS: Long = 1_500
        const val AWAIT_GONE_MILLIS: Long = 5_000
        const val POLL_MILLIS: Long = 20
        const val PARENT_PID_FILE: String = "parent.pid"
        const val CHILD_PID_FILE: String = "child.pid"

        /** Пишет pid оболочки и фонового `sleep`, затем висит — до убийства по таймауту. */
        const val KILL_SCRIPT: String =
            "echo \$\$ > parent.pid; sleep 30 >/dev/null 2>&1 & echo \$! > child.pid; " +
                "sleep 30 >/dev/null 2>&1"
    }
}
