package dev.aide.tools.sandbox

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.exists
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout

/**
 * T-1.9: запуск команды — окружение, вывод, рабочая директория, убийство дерева.
 *
 * Проверки идут на настоящих процессах: подделка `Process` проверяла бы подделку, а риск
 * здесь именно в поведении ядра (трубы, потомки, сигналы). Тесты `host-tools` в CI идут
 * на Linux, там `sh`, `env`, `cat`, `echo` и `ProcessHandle` есть всегда.
 */
class CommandRunnerTest {

    private val runner = CommandRunner(pollIntervalMillis = RUNNER_POLL_MILLIS)
    private val directories = mutableListOf<Path>()

    @AfterTest
    fun tearDown() {
        directories.forEach { it.toFile().deleteRecursively() }
        directories.clear()
    }

    @Test
    fun `команда возвращает stdout и нулевой код возврата`() {
        runBlocking {
            val result = runner.run(listOf("echo", "привет"), directory(), environment())

            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains("привет"), "stdout обязан дойти целиком: ${result.stdout}")
            assertEquals("", result.stderr, "в stderr ничего не писалось")
            assertFalse(result.stdoutTruncated)
        }
    }

    @Test
    fun `ненулевой код возврата не считается сбоем`() {
        runBlocking {
            val result = runner.run(listOf("cat", "нет-такого-файла"), directory(), environment())

            assertNotEquals(0, result.exitCode, "cat по отсутствующему файлу обязан вернуть не ноль")
            assertTrue(result.stderr.isNotBlank(), "причина обязана быть в stderr: ${result.stderr}")
        }
    }

    @Test
    fun `команда запускается в переданной рабочей директории`() {
        runBlocking {
            val directory = directory()
            directory.resolve(PROBE_FILE).writeText(PROBE_CONTENT)

            // Относительный путь разрешается только от cwd: если бы командой управлял не переданный
            // каталог, cat не нашёл бы файл и проверка провалилась бы на пустом stdout.
            val result = runner.run(listOf("cat", PROBE_FILE), directory, environment())

            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains(PROBE_CONTENT), "файл обязан читаться из cwd: ${result.stdout}")
        }
    }

    @Test
    fun `окружение не наследуется, а берётся по списку`() {
        runBlocking {
            val hostPathVariable = System.getenv("PATH").orEmpty()
            val passed = mapOf("PATH" to hostPathVariable, PASSED_MARKER to MARKER_VALUE)

            val result = runner.run(listOf("env"), directory(), passed)

            val printed = result.stdout.lines().filter { it.isNotBlank() }.toSet()
            assertEquals(
                setOf("PATH=$hostPathVariable", "$PASSED_MARKER=$MARKER_VALUE"),
                printed,
                "команда обязана видеть ровно переданные переменные, а не окружение хоста",
            )
        }
    }

    @Test
    fun `заполнение второй трубы не подвешивает прогон, а обрезка помечается`() {
        runBlocking {
            // `head` пишет в stderr заметно больше ёмкости трубы (64 КиБ) и лишь потом
            // доходит до `echo done`. Читай мы stdout, не читая stderr, производитель встал
            // бы на полной трубе и «done» не появилось бы никогда; `withTimeout` превращает
            // это в красный тест, а не в зависание. На `cat` проверка не опирается: это ядро
            // может переносить в трубу через `splice` и не блокироваться.
            val result = withTimeout(OUTPUT_TIMEOUT_MILLIS) {
                runner.run(listOf("sh", "-c", PIPE_FILL_SCRIPT), directory(), environment())
            }

            assertEquals(0, result.exitCode, result.stderr)
            assertTrue(result.stdout.contains(DONE_OUTPUT), "второй поток обязан дойти: ${result.stdout}")
            assertTrue(result.stderrTruncated, "stderr обязан быть помечен обрезанным")
            assertEquals(MAX_COMMAND_OUTPUT_BYTES, result.stderr.toByteArray().size)
        }
    }

    @Test
    fun `команда, читающая stdin, получает EOF и завершается сама`() {
        runBlocking {
            // `cat` без аргумента читает stdin: не закрой мы свою сторону трубы, он висел бы
            // до таймаута. Проверка ловит именно EOF, а не «рано или поздно убили».
            val result = withTimeout(STDIN_TIMEOUT_MILLIS) {
                runner.run(listOf("cat"), directory(), environment())
            }

            assertEquals(0, result.exitCode, result.stderr)
            assertEquals("", result.stdout, "на закрытом stdin читать нечего")
        }
    }

    @Test
    fun `таймаут убивает процесс и его потомка`() {
        runBlocking {
            val directory = directory()
            assertFailsWith<TimeoutCancellationException> {
                withTimeout(KILL_TIMEOUT_MILLIS) {
                    runner.run(listOf("sh", "-c", KILL_SCRIPT), directory, environment())
                }
            }

            val parent = assertNotNull(readPid(directory.resolve(PARENT_PID_FILE)), "скрипт обязан записать свой pid")
            val child = assertNotNull(readPid(directory.resolve(CHILD_PID_FILE)), "скрипт обязан записать pid потомка")
            assertTrue(awaitGone(parent), "по таймауту прямой процесс обязан быть убит: pid $parent жив")
            assertTrue(awaitGone(child), "по таймауту потомок обязан быть убит: pid $child жив")
        }
    }

    /** Свежий каталог под команду: команды могут создавать файлы, и они не должны мешать друг другу. */
    private fun directory(): Path =
        Files.createTempDirectory(TEMP_PREFIX).toRealPath().also { directories.add(it) }

    /** Минимум окружения, чтобы нашлись `echo`, `env`, `cat` и `sh`. */
    private fun environment(): Map<String, String> = mapOf("PATH" to System.getenv("PATH").orEmpty())

    private fun readPid(file: Path): Long? =
        if (file.exists()) file.readText().trim().toLongOrNull() else null

    /** Ждёт, пока процесса с [pid] не станет; короткое окно нужно зомби-состоянию после SIGKILL. */
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

        const val PROBE_FILE: String = "проба.txt"
        const val PROBE_CONTENT: String = "содержимое пробы"
        const val PASSED_MARKER: String = "AIDE_PASSED_VARIABLE"
        const val MARKER_VALUE: String = "видно"
        const val PARENT_PID_FILE: String = "parent.pid"
        const val CHILD_PID_FILE: String = "child.pid"
        const val TEMP_PREFIX: String = "aide-command-"
        const val DONE_OUTPUT: String = "done"

        /**
         * Производитель, заполняющий **вторую** трубу: пять буферов по 64 КиБ в stderr и
         * лишь потом слово в stdout. Пяти буферов хватает, чтобы процесс встал, если
         * stderr никто не читает, — на этом и стоит проверка параллельности чтения.
         */
        const val PIPE_FILL_BYTES: Int = MAX_COMMAND_OUTPUT_BYTES * 5
        const val PIPE_FILL_SCRIPT: String = "head -c $PIPE_FILL_BYTES /dev/zero 1>&2; echo $DONE_OUTPUT"

        /**
         * Скрипт пишет pid оболочки и pid фонового `sleep`, затем висит.
         *
         * Два `sleep`: фоновый даёт потомка, которого надо убить, а фоновый плюс передний
         * `sleep` не дают оболочке заменить себя на одну команду (`exec`), иначе потомка
         * не было бы вовсе. Вывод потомков уводится в `/dev/null`: иначе они держали бы
         * нашу трубу открытой, и проверка проверяла бы трубу, а не убийство.
         */
        const val KILL_SCRIPT: String =
            "echo \$\$ > parent.pid; sleep 30 >/dev/null 2>&1 & echo \$! > child.pid; " +
                "sleep 30 >/dev/null 2>&1"

        /** Таймаут короче тридцати секунд `sleep`: команда обязана не дожить до конца. */
        const val KILL_TIMEOUT_MILLIS: Long = 2_000

        /** Шаг опроса завершения: короче обычного, чтобы тест не ждал лишние миллисекунды. */
        const val RUNNER_POLL_MILLIS: Long = 5

        /** Сколько тест ждёт исчезновения процесса: после SIGKILL ручка обновляется не мгновенно. */
        const val AWAIT_GONE_MILLIS: Long = 5_000

        /** Предел ожидания прогона с заполненной трубой: зависание должно стать красным тестом. */
        const val OUTPUT_TIMEOUT_MILLIS: Long = 30_000

        /** Предел ожидания команды, читающей stdin: на закрытом stdin она завершается сразу. */
        const val STDIN_TIMEOUT_MILLIS: Long = 5_000

        const val POLL_MILLIS: Long = 20
    }
}
