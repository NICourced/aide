package dev.aide.host.secrets

import dev.aide.agent.provider.SecretStore
import dev.aide.agent.provider.SecretStoreAvailability
import dev.aide.agent.provider.SecretStoreUnavailableException
import dev.aide.domain.SecretStoreUnavailableReason
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import org.slf4j.LoggerFactory

/**
 * Защищённое хранилище ключей на Linux: libsecret через `secret-tool` (T-1.58).
 *
 * `secret-tool` — штатный клиент libsecret: он говорит с системным keyring (GNOME Keyring
 * или другой secret service) и не требует ни графической сессии, ни своей криптографии.
 * Писать свой файл с ключом нельзя ни в каком виде: именно этого задача и не разрешает.
 *
 * Записи адресуются атрибутами `service` и `provider`: имя записи — идентификатор
 * провайдера, поэтому ключ переживает перезапуск хоста и доступен любому клиенту.
 * Значение передаётся программе **на стандартный ввод**, а не аргументом командной
 * строки: аргументы видны в списке процессов (`ps`), и ключ утёк бы любому, кто
 * посмотрит на запущенные процессы.
 *
 * **Как различаются «сервиса нет» и «ключа нет».** По коду возврата их различить нельзя:
 * `secret-tool` отвечает единицей и когда записи нет, и когда secret service недоступен
 * (нет сессии D-Bus, юнит `org.freedesktop.secrets` не зарегистрирован, keyring заперт).
 * Различие несёт **stderr**: при недоступном сервисе libsecret пишет туда причину
 * («Cannot autolaunch D-Bus», «The name org.freedesktop.secrets was not provided by any
 * .service files»), а при ненайденной записи молчит. Поэтому правило такое: ненулевой код
 * **с непустым stderr** — хранилище недоступно ([SecretStoreUnavailableReason.KEYRING_UNAVAILABLE]),
 * ненулевой код с пустым stderr — «записи нет». Иначе недоступный сервис выглядел бы как
 * «ключа нет», и [dev.aide.agent.provider.ModelSecrets] молча ушёл бы к переменной окружения —
 * ровно то, что контракт порта запрещает.
 *
 * **Ни вывод программы-посредника, ни её аргументы не попадают в журнал.** В stdout
 * лежит само значение ключа (`lookup`), и записать его в лог — тот же провайдер
 * утечки, что и файл настроек; поэтому в журнал идут только код возврата и причина.
 *
 * @param executable имя или путь `secret-tool`; подменяется в тестах.
 * @param timeoutMillis предел ожидания одного вызова. Запертый keyring заставляет
 *   `secret-tool` ждать разблокировки, и без таймаута выбор модели повис бы навсегда;
 *   по истечении срока процесс убивается, а хранилище объявляется недоступным.
 */
class LinuxSecretStore(
    private val executable: String = DEFAULT_EXECUTABLE,
    private val timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS,
) : SecretStore {

    private val logger = LoggerFactory.getLogger(LinuxSecretStore::class.java)

    override fun availability(): SecretStoreAvailability {
        val outcome = run(listOf(LOOKUP) + location(PROBE))
        // Ноль — рабочий keyring. Ненулевой код с пустым stderr — тоже: так `lookup`
        // отвечает, когда записи нет. Ненулевой код с диагностикой означает, что сервис
        // не ответил, и это уже отказ.
        val answered = outcome is ToolOutcome.Exited &&
            (outcome.code == SUCCESS_EXIT_CODE || outcome.stderr.isBlank())
        if (answered) return SecretStoreAvailability.Available
        return SecretStoreAvailability.Unavailable(failure(outcome).reason)
    }

    override fun get(name: String): String? {
        val outcome = run(listOf(LOOKUP) + location(name))
        if (outcome is ToolOutcome.Exited) {
            if (outcome.code == SUCCESS_EXIT_CODE) return outcome.stdout.trim().takeIf { it.isNotEmpty() }
            // Ненулевой код без диагностики — «такой записи нет»; с диагностикой — отказ.
            if (outcome.stderr.isBlank()) return null
        }
        throw failure(outcome)
    }

    override fun put(name: String, value: String) {
        val label = "$LABEL_PREFIX$name"
        val outcome = run(listOf(STORE, "--label=$label") + location(name), value)
        requireSuccess(outcome, "запись ключа")
    }

    override fun delete(name: String) {
        val outcome = run(listOf(CLEAR) + location(name))
        // Ненулевой код и молчащий stderr означает «такой записи не было»: удалять нечего.
        if (outcome is ToolOutcome.Exited && outcome.code != SUCCESS_EXIT_CODE && outcome.stderr.isBlank()) return
        requireSuccess(outcome, "удаление ключа")
    }

    /** Успех — нулевой код возврата; всё прочее означает недоступность хранилища. */
    private fun requireSuccess(outcome: ToolOutcome, what: String) {
        if (outcome is ToolOutcome.Exited && outcome.code == SUCCESS_EXIT_CODE) {
            logger.debug("secret-tool: $what — успешно")
            return
        }
        throw failure(outcome)
    }

    /**
     * Превращает неудачный исход в типизированный отказ.
     *
     * В журнал идёт код возврата, но **не вывод программы**: в нём может быть значение
     * ключа, а журнал переживает сессию и читается глазами. О наличии диагностики
     * в stderr пишется только фактом — по нему и различаются «сервиса нет» и «ключа нет».
     */
    private fun failure(outcome: ToolOutcome): SecretStoreUnavailableException {
        val (reason, detail) = when (outcome) {
            is ToolOutcome.Exited -> SecretStoreUnavailableReason.KEYRING_UNAVAILABLE to
                "код возврата ${outcome.code}, диагностика: ${if (outcome.stderr.isBlank()) "нет" else "есть"}"

            is ToolOutcome.NotExecuted ->
                SecretStoreUnavailableReason.TOOL_MISSING to "программа $executable не запущена"

            is ToolOutcome.TimedOut ->
                SecretStoreUnavailableReason.KEYRING_UNAVAILABLE to "нет ответа за $timeoutMillis мс"
        }
        logger.warn("Хранилище ключей недоступно ($reason): $detail")
        return SecretStoreUnavailableException(reason)
    }

    /** Атрибуты записи: сервис приложения и идентификатор провайдера. */
    private fun location(name: String): List<String> =
        listOf(SERVICE_ATTRIBUTE, SERVICE, PROVIDER_ATTRIBUTE, name)

    /**
     * Запускает `secret-tool` с таймаутом.
     *
     * Оба потока читаются **параллельно ожиданию** ([StreamReader]): вывод, не влезающий
     * в буфер канала, остановил бы процесс, и вызов оборвался бы таймаутом вместо ответа.
     * Для `lookup` это не гипотетика — значение ключа может быть длинным, а канал
     * вмещает килобайты; цена ошибки — «нет ответа за 5 секунд» на живом хранилище.
     *
     * stderr не смешивается с stdout: по нему различаются «сервиса нет» и «ключа нет».
     *
     * @param stdin значение для команд записи; null — команда ввода не ждёт.
     */
    private fun run(arguments: List<String>, stdin: String? = null): ToolOutcome {
        val process = try {
            ProcessBuilder(listOf(executable) + arguments).start()
        } catch (error: IOException) {
            logger.warn("Программа $executable не запущена: ${error.message}")
            return ToolOutcome.NotExecuted
        }
        val stdout = StreamReader(process.inputStream)
        val stderr = StreamReader(process.errorStream)
        return try {
            writeStdin(process, stdin)
            if (process.waitFor(timeoutMillis, TimeUnit.MILLISECONDS)) {
                ToolOutcome.Exited(process.exitValue(), stdout.text(), stderr.text())
            } else {
                process.destroyForcibly()
                logger.warn("Вызов $executable не ответил за $timeoutMillis мс — процесс убит")
                ToolOutcome.TimedOut
            }
        } catch (error: IOException) {
            // Канал закрылся раньше времени: программа умерла, не дочитав ввод.
            logger.warn("Вызов $executable прерван: ${error.message}")
            process.destroyForcibly()
            ToolOutcome.NotExecuted
        }
    }

    /** Пишет значение в стандартный ввод и закрывает канал: `secret-tool` читает до конца файла. */
    private fun writeStdin(process: Process, stdin: String?) {
        if (stdin == null) {
            process.outputStream.close()
            return
        }
        process.outputStream.use { it.write(stdin.toByteArray(StandardCharsets.UTF_8)) }
    }

    /** Исход одного вызова `secret-tool`. */
    private sealed interface ToolOutcome {

        /** Программа завершилась: код возврата, stdout и stderr (потоки не смешаны). */
        data class Exited(val code: Int, val stdout: String, val stderr: String) : ToolOutcome

        /** Программа не ответила за отведённое время. */
        data object TimedOut : ToolOutcome

        /** Программу не удалось запустить или её канал оборвался. */
        data object NotExecuted : ToolOutcome
    }

    companion object {

        /** Штатное имя программы; в системе с libsecret оно лежит в `PATH`. */
        const val DEFAULT_EXECUTABLE: String = "secret-tool"

        /** Предел ожидания одного вызова: запертый keyring не должен вешать хост. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 5_000

        /** Атрибут, отделяющий записи приложения от чужих в системном хранилище. */
        const val SERVICE: String = "dev.aide.host"

        /** Имя пробной записи: по ответу на неё видно, работает ли keyring. */
        const val PROBE: String = "availability-probe"

        private const val LOOKUP = "lookup"
        private const val STORE = "store"
        private const val CLEAR = "clear"
        private const val SUCCESS_EXIT_CODE = 0
        private const val SERVICE_ATTRIBUTE = "service"
        private const val PROVIDER_ATTRIBUTE = "provider"
        private const val LABEL_PREFIX = "AI Studio: провайдер "
    }
}

/**
 * Читает поток процесса в отдельном потоке, пока идёт ожидание его завершения.
 *
 * Отдельный поток, а не чтение после [Process.waitFor]: заполнившийся буфер канала
 * останавливает пишущий процесс, и вызов обрывался бы таймаутом на исправном хранилище.
 * Поток-демон, чтобы незакрытый процесс не держал JVM хоста.
 */
private class StreamReader(stream: InputStream) {

    private val logger = LoggerFactory.getLogger(StreamReader::class.java)

    private val buffer = ByteArrayOutputStream()

    private val reader = Thread({ read(stream) }, "secret-tool-stream").apply {
        isDaemon = true
        start()
    }

    /** Прочитанный текст; ждёт конца чтения не дольше [JOIN_TIMEOUT_MILLIS]. */
    fun text(): String {
        reader.join(JOIN_TIMEOUT_MILLIS)
        return buffer.toString(StandardCharsets.UTF_8)
    }

    private fun read(stream: InputStream) {
        try {
            stream.use { it.copyTo(buffer) }
        } catch (error: IOException) {
            // Процесс убит по таймауту: читать больше нечего, канал закрылся.
            logger.debug("Чтение потока процесса прервано: ${error.message}")
        }
    }

    private companion object {
        /** Сколько ждать конца чтения после выхода процесса: он уже закрыл каналы. */
        const val JOIN_TIMEOUT_MILLIS = 1_000L
    }
}
