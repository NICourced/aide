package dev.aide.agent

import dev.aide.domain.RunCommand
import dev.aide.domain.RunId
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel

/**
 * Управление одним прогоном (T-1.1).
 *
 * Пауза и продолжение доставляются каналом: движок замечает их в точках проверки
 * между шагами и не прерывает вызов модели — шаг не переделывается (решение 5).
 * Стоп отменяет корутину прогона: иначе зависший вызов провайдера не остановить
 * вовсе, а отмена хоста и отмена по кнопке обязаны различаться.
 */
class RunControl {

    private val commands = Channel<RunCommand>(Channel.UNLIMITED)

    /**
     * Корутина, выполняющая прогон; стоп отменяет именно её.
     *
     * `@Volatile` не для красоты: поле пишет воркер (в своей корутине), а читает поток
     * запроса Ktor, обрабатывающий команду пользователя. Без видимости `cancel()`
     * мог бы не увидеть только что записанную ссылку и не прервать зависший вызов
     * провайдера — ровно тот случай, ради которого стоп и отменяет корутину.
     */
    @Volatile
    private var runJob: Job? = null

    /** true, если пользователь остановил прогон; отличает стоп от отмены хоста. */
    @Volatile
    var stopRequested: Boolean = false
        private set

    /** Привязывает корутину прогона; вызывается движком после её запуска. */
    fun attach(job: Job) {
        runJob = job
    }

    /** Кладёт команду. Стоп действует немедленно, пауза и продолжение — в точке проверки. */
    fun request(command: RunCommand) {
        when (command) {
            RunCommand.STOP -> {
                stopRequested = true
                runJob?.cancel()
            }

            RunCommand.PAUSE, RunCommand.RESUME -> commands.trySend(command)
        }
    }

    /** Забирает ожидающую команду, снимая её; null, если команд нет. */
    fun take(): RunCommand? = commands.tryReceive().getOrNull()

    /** Ждёт команду из состояния паузы. */
    suspend fun awaitCommand(): RunCommand = commands.receive()
}

/** Управление прогоном, которого нет: команда не выполняется молча, а называется ошибкой. */
class UnknownRunException(val runId: RunId) : IllegalArgumentException("Неизвестный прогон: ${runId.value}")
