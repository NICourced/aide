package dev.aide.tools.sandbox

import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.Path
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay

/**
 * Сколько байт каждого потока (stdout, stderr) отдаётся модели (T-1.9).
 *
 * Число с причиной: вывод команды уезжает в контекст модели и оплачивается токенами,
 * а команда вроде `cat` большого файла или сборка с логом на мегабайт вытеснила бы
 * из контекста саму задачу. 64 КиБ — примерно как лимит `read_file`, и его хватает
 * на осмысленный хвост вывода; остальное вычитывается и отбрасывается, чтобы процесс
 * не встал на полной трубе.
 */
internal const val MAX_COMMAND_OUTPUT_BYTES: Int = 64 * 1024

/**
 * Как часто прогон проверяет, завершился ли процесс.
 *
 * Число с причиной: ожидание отменяемое (`delay`, а не блокирующий `waitFor`), и шаг
 * опроса задаёт задержку между завершением процесса и возвратом результата. Двадцать
 * миллисекунд незаметны человеку и не жгут поток, тогда как опрос в цикле без паузы
 * занял бы ядро целиком на всё время команды.
 */
private const val POLL_INTERVAL_MILLIS: Long = 20

/** Размер буфера чтения потока: 8 КиБ — штатный размер трубы, читаем пачками. */
private const val READ_BUFFER_BYTES: Int = 8 * 1024

/**
 * Что вернула команда: код возврата и оба потока по отдельности.
 *
 * [stdoutTruncated] и [stderrTruncated] — не «ошибка вывода», а факт: показанное короче
 * напечатанного. Модель обязана это видеть, иначе примет обрезок за весь вывод.
 */
data class CommandResult(
    val exitCode: Int,
    val stdout: String,
    val stderr: String,
    val stdoutTruncated: Boolean,
    val stderrTruncated: Boolean,
)

/**
 * Запуск команды в песочнице-каталоге воркспейса (T-1.9).
 *
 * Это **не изоляция**: java-процесс запускается от того же пользователя и видит ту же
 * файловую систему и сеть, что и хост. Что здесь делается — рабочая директория внутри
 * воркспейса, окружение по списку вместо наследуемого и убийство дерева процессов по
 * отмене. Предполётную сверку аргументов делает [CommandGuard], а настоящий барьер —
 * подтверждение пользователя (умолчание `ASK`, решение 8), потому что обойти проверку
 * аргументов программой внутри команды ничто не мешает (FR-TOOLS-13, T-3.22).
 *
 * Класс не берёт таймаут на себя: исход «не успел» формирует точка вызова (`withTimeout`
 * в `ToolInvoker`), и по отмене корутины обязан состояться [killTree]. Ожидание поэтому
 * отменяемое — блокирующий `waitFor` не дал бы отмене случиться вовсе.
 *
 * @param pollIntervalMillis шаг опроса завершения; подменяется, чтобы тесты не спали.
 */
class CommandRunner(private val pollIntervalMillis: Long = POLL_INTERVAL_MILLIS) {

    /**
     * Запускает [command] в [directory] с окружением [environment] и ждёт завершения.
     *
     * stdout и stderr читаются **одновременно**: если читать только один, переполненная
     * труба второго остановит процесс, и прогон повиснет — не «медленно», а навсегда.
     * Лишённый читателя хвост второго потока при этом вычитывается и отбрасывается:
     * остановиться на обрезке значило бы дать трубе заполниться и подвесить процесс.
     *
     * stdin сразу закрывается: интерактивного ввода у команды нет, а без EOF читающая
     * команда (`cat` без аргумента, `read`) висела бы до таймаута и держала открытый fd.
     *
     * По отмене корутины (таймаут точки вызова, стоп прогона) процесс и его потомки
     * убиваются в `finally`, и только потом отмена летит дальше. Без этого `withTimeout`
     * вернул бы `TIMEOUT`, а команда продолжила бы держать файлы и порты.
     *
     * @throws IOException процесс не удалось запустить (нет программы, нет прав) — это
     *   сбой инструмента, а не отказ, и решение о нём принимает вызывающий.
     */
    suspend fun run(command: List<String>, directory: Path, environment: Map<String, String>): CommandResult {
        val process = start(command, directory, environment)
        // stdin закрывается до чтения труб: команда получает EOF вместо ожидания ввода.
        closeQuietly(process.outputStream)
        return coroutineScope {
            val stdout = async(Dispatchers.IO) { capture(process.inputStream) }
            val stderr = async(Dispatchers.IO) { capture(process.errorStream) }
            try {
                val exitCode = awaitExit(process)
                val out = stdout.await()
                val err = stderr.await()
                CommandResult(exitCode, out.text, err.text, out.truncated, err.truncated)
            } finally {
                killTree(process)
            }
        }
    }

    /** Запускает процесс без оболочки: [command] — программа и её аргументы, а не строка для `sh`. */
    private fun start(command: List<String>, directory: Path, environment: Map<String, String>): Process =
        ProcessBuilder(command)
            .directory(directory.toFile())
            .apply {
                // Окружение не наследуется: ключ провайдера живёт в переменных окружения
                // хоста (T-1.58), и `env` внутри команды прочитал бы его, не будь список
                // переменных закрытым. Белый список собирает инструмент, а не бегунок.
                environment().clear()
                environment().putAll(environment)
            }
            .start()

    /**
     * Ждёт завершения процесса отменяемым опросом.
     *
     * `isAlive`, а не `waitFor`: блокирующее ожидание не прерывается отменой корутины,
     * и таймаут точки вызова не сработал бы до конца команды. После `isAlive` == false
     * `exitValue()` доступен всегда — процесс завершён и повторно не оживёт.
     */
    private suspend fun awaitExit(process: Process): Int {
        while (process.isAlive) delay(pollIntervalMillis)
        return process.exitValue()
    }

    /**
     * Убивает процесс и его потомков.
     *
     * Потомки — обязательны: команда могла запустить дочерний процесс, который держит
     * унаследованную трубу вывода, и без его убийства чтение stdout не увидело бы EOF.
     * Список потомков снимается **до** `destroyForcibly` родителя: после его смерти
     * ядро передаёт детей init, и вернуть их ручки уже нечем.
     */
    private fun killTree(process: Process) {
        val descendants = runCatching { process.descendants().toList() }.getOrDefault(emptyList())
        descendants.forEach { it.destroyForcibly() }
        process.destroyForcibly()
        // Закрытые на нашей стороне трубы дают чтению EOF и выпускают читателей, даже
        // если потомок остался жив и держал бы свой конец открытым.
        closeQuietly(process.inputStream)
        closeQuietly(process.errorStream)
        closeQuietly(process.outputStream)
    }

    /** Читает поток целиком, сохраняя начало до лимита; остаток вычитывается и отбрасывается. */
    private fun capture(stream: InputStream): Capture = stream.use { input ->
        val buffer = ByteArray(READ_BUFFER_BYTES)
        val kept = ByteArrayOutputStream()
        var total = 0L
        while (true) {
            val read = readOrEnd(input, buffer)
            if (read < 0) break
            total += read
            val room = MAX_COMMAND_OUTPUT_BYTES - kept.size()
            if (room > 0) kept.write(buffer, 0, minOf(room, read))
        }
        Capture(String(kept.toByteArray(), UTF_8), total > MAX_COMMAND_OUTPUT_BYTES)
    }

    /**
     * Читает пачку или сообщает о конце.
     *
     * Поток, закрытый [killTree] у нас на стороне, даёт `IOException` вместо EOF: это
     * не сбой чтения, а конец вывода убитой команды, и сообщать о нём нечего.
     */
    @Suppress("SwallowedException")
    private fun readOrEnd(input: InputStream, buffer: ByteArray): Int = try {
        input.read(buffer)
    } catch (closed: IOException) {
        -1
    }

    private fun closeQuietly(closeable: Closeable) {
        runCatching { closeable.close() }
    }
}

/** Начало потока и признак, что напечатанного было больше. */
private class Capture(val text: String, val truncated: Boolean)
