package dev.aide.tools

import dev.aide.domain.ApprovalDecision
import dev.aide.domain.AutonomyMode
import dev.aide.domain.RiskLevel
import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.TestReport
import dev.aide.domain.TestState
import dev.aide.domain.ToolApprovalRequest
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolKind
import dev.aide.domain.ToolOutcome
import dev.aide.tools.file.ProposableTool
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.PermissionDecision
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.ports.AutonomyPorts
import dev.aide.tools.ports.ChangeSnapshot
import dev.aide.tools.ports.ChangeSnapshots
import dev.aide.tools.ports.ToolCallRecorder
import dev.aide.tools.schema.ArgumentsSchema
import java.util.UUID
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withTimeout
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import org.slf4j.LoggerFactory

/** Наносекунд в миллисекунде: длительность вызова пишется в миллисекундах. */
private const val NANOS_PER_MILLI: Long = 1_000_000

/**
 * Единственная точка вызова инструмента (О-3, T-1.7).
 *
 * Через неё проходят все решения, которые обязаны быть одинаковыми для любого
 * инструмента: проверка аргументов по схеме, разрешение [PermissionResolver],
 * предел времени, замер длительности и запись в журнал. Разложи́ их по инструментам —
 * и следующий инструмент (запись, терминал, тесты) забыл бы одно из них, а заметить
 * это было бы нечем.
 *
 * Отказ — результат, а не исключение: и запрет настройками, и выход за границу
 * воркспейса возвращаются модели как `ToolOutcome.DENIED`, и прогон из-за отказа
 * не ломается. Модель видит отказ, объясняет его себе и пробует иначе.
 *
 * @param clock источник времени для отметки в журнале; подменяется в тестах.
 * @param snapshots точка отката перед изменяющим вызовом (T-1.8): она здесь, а не
 *   в инструменте, потому что без неё изменять нельзя — а изменять можно только
 *   через эту точку, и гарантию надо ставить там же, где права и журнал.
 * @param autonomy доверенная оценка риска и очередь неприменённых предложений (T-1.15).
 */
class ToolInvoker(
    private val registry: ToolRegistry,
    private val permissions: PermissionResolver,
    private val recorder: ToolCallRecorder,
    private val snapshots: ChangeSnapshots,
    private val clock: () -> Instant = Clock.System::now,
    private val autonomy: AutonomyPorts = AutonomyPorts(),
) {

    private val logger = LoggerFactory.getLogger(ToolInvoker::class.java)
    private val proposalExecutor = ProposalExecutor(autonomy.risk, autonomy.proposals, clock)

    /**
     * Вызывает инструмент, предложенный моделью, и пишет вызов в журнал — с любым исходом.
     *
     * [mode] — режим автономности конкретного прогона, зафиксированный на старте.
     * Переключение настройки во время прогона его не меняет (FR-AGENT-5, T-1.15).
     *
     * Внутренний инструмент ([ToolVisibility.INTERNAL]) отсюда исполнить нельзя: он адресован
     * движку, а не модели, и «не показывать определение» ещё не значит «нельзя позвать по
     * имени» — движок исполняет его без прав и без снапшота, так что модельный путь обошёл бы
     * и права, и «один коммит на шаг». Поэтому модельный путь отвечает отказом, и отказ
     * остаётся в журнале: попытка вызова — такое же событие прозрачности (FR-AGENT-8).
     *
     * @param arguments аргументы строкой, как их прислала модель: формат у протоколов
     *   разный, и разбирает их здесь тот, кто проверяет их по схеме.
     * @param mode режим автономности прогона; по умолчанию самый консервативный.
     */
    suspend fun invoke(
        runId: RunId,
        tool: String,
        arguments: String,
        context: ToolContext,
        mode: AutonomyMode = AutonomyMode.ASK_BEFORE_CHANGES,
    ): ToolResult {
        val callId = ToolCallId(UUID.randomUUID().toString())
        return journaled(runId, callId, tool, arguments) {
            attempt(CallFrame(runId, callId, tool, arguments, context), Origin.MODEL, mode)
        }
    }

    /**
     * Вызывает инструмент от имени хоста (T-1.11): так движок прогона зовёт `commit_step`.
     *
     * Внутренний инструмент исполняется здесь без прав и без точки отката (О-4): изменяющее
     * действие уже одобрено, а снапшот принадлежит изменению и поставлен перед ним (T-1.8).
     * Обычный инструмент, позванный этим путём, проходит общий порядок — права и снапшот
     * не обходятся: облегчён вход только для внутренних, а не для любого, кого назовёт хост.
     */
    suspend fun invokeInternal(runId: RunId, tool: String, arguments: String, context: ToolContext): ToolResult {
        val callId = ToolCallId(UUID.randomUUID().toString())
        return journaled(runId, callId, tool, arguments) {
            attempt(
                CallFrame(runId, callId, tool, arguments, context),
                Origin.INTERNAL,
                AutonomyMode.ASK_BEFORE_CHANGES,
            )
        }
    }

    /** Замеряет длительность, выполняет попытку и записывает её в журнал — с любым исходом. */
    private suspend fun journaled(
        runId: RunId,
        callId: ToolCallId,
        tool: String,
        arguments: String,
        body: suspend () -> Attempt,
    ): ToolResult {
        val startedNanos = System.nanoTime()
        val attempt = body()
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        // Идентификатор вызова генерирует хост до выполнения: по нему адресуется
        // подтверждение (T-1.13), а журнал — таблица на весь хост, и совпавший
        // идентификатор двух вызовов потерял бы одну запись.
        recorder.record(
            ToolCall(
                id = callId,
                runId = runId,
                tool = tool,
                arguments = arguments,
                result = attempt.result.text,
                outcome = attempt.result.outcome,
                durationMillis = elapsedMillis,
                cost = attempt.result.cost,
                requiredApproval = attempt.requiredApproval,
                approval = attempt.approval,
                at = clock(),
            ),
        )
        return attempt.result
    }

    /**
     * Проверяет вызов и выполняет его.
     *
     * Порядок обязателен: сначала схема (дешёвая проверка, и модель поправится сама),
     * потом права. Спрашивать разрешение на вызов с негодными аргументами значило бы
     * показывать пользователю диалог о том, чего модель не собиралась делать.
     */
    private suspend fun attempt(
        frame: CallFrame,
        origin: Origin,
        mode: AutonomyMode,
    ): Attempt {
        val definition = registry.find(frame.tool)
        val parsed = if (definition == null) null else parseArguments(frame.toolArguments)
        return when {
            definition == null -> refused("инструмента «${frame.tool}» агент не знает", ToolOutcome.FAILURE)
            parsed == null -> refused("аргументы вызова «${frame.tool}» не являются JSON-объектом", ToolOutcome.FAILURE)
            else -> decide(frame, definition, parsed, origin, mode)
        }
    }

    /**
     * Решение по проверенному вызову: схема, затем права — кроме внутренних инструментов.
     *
     * Внутренние ([ToolVisibility.INTERNAL], T-1.11) не проходят ни права, ни точку отката.
     * Причина: пользователь одобряет изменение (`write_file`), а не бухгалтерию хоста, и
     * второй вопрос за то же действие — ровно то, что запрещает О-4 («подтверждение не
     * дублируется»). Точка отката принадлежит изменению и уже поставлена перед записью
     * (T-1.8), а ставить её второй раз значило бы вытеснять осмысленные снапшоты ради дублей.
     *
     * Облегчённый вход — только для вызова от имени хоста ([Origin.INTERNAL]): из модельного
     * пути внутренний инструмент недоступен вовсе, иначе по имени `commit_step` модель
     * обошла бы и права, и правило «один коммит на изменяющий шаг» (решение 1). Вызов при
     * этом идёт через эту точку — ради журнала, таймаута и замера времени (О-3).
     */
    private suspend fun decide(
        frame: CallFrame,
        definition: AgentTool,
        arguments: JsonObject,
        origin: Origin,
        mode: AutonomyMode,
    ): Attempt {
        val complaint = ArgumentsSchema.check(definition.argumentsSchema, arguments)
        return when {
            complaint != null -> refused("${definition.name}: $complaint", ToolOutcome.FAILURE)
            definition.visibility == ToolVisibility.INTERNAL && origin == Origin.MODEL -> refused(
                "инструмент «${definition.name}» внутренний: модель его вызывать не может",
                ToolOutcome.FAILURE,
            )
            definition.visibility == ToolVisibility.INTERNAL -> execute(definition, arguments, frame.context)
            else -> permitted(frame, definition, arguments, mode)
        }
    }

    /** Вызов, аргументы которого приняты; остаётся спросить права и выполнить. */
    private suspend fun permitted(
        frame: CallFrame,
        definition: AgentTool,
        arguments: JsonObject,
        mode: AutonomyMode,
    ): Attempt {
        val permission = permissions.resolve(definition.name, definition.kind, definition.declaredPermission)
        val needsRisk = permission !is PermissionDecision.Deny &&
            definition.changesRepository &&
            (mode == AutonomyMode.SUGGEST_ONLY || mode == AutonomyMode.AUTO_APPLY_SAFE)
        val riskLevel = if (needsRisk) {
            proposalExecutor.assess(frame.runId, definition.name, frame.toolArguments, frame.context)
        } else {
            RiskLevel.NORMAL
        }
        val call = AutonomyCall(
            changesRepository = definition.changesRepository,
            executionOnly = definition.kind == ToolKind.WRITE && !definition.changesRepository,
            isProposable = definition is ProposableTool,
            risk = riskLevel,
        )
        return when (val outcome = AutonomyDecision.decide(mode, permission, call)) {
            AutonomyOutcome.Execute -> guarded(frame, definition, arguments)
            AutonomyOutcome.Ask -> requestApproval(frame, definition, arguments)
            AutonomyOutcome.Propose -> Attempt(
                proposalExecutor.propose(
                    tool = definition as ProposableTool,
                    input = ProposalInput(
                        runId = frame.runId,
                        callId = frame.callId,
                        toolName = definition.name,
                        arguments = arguments,
                        context = frame.context,
                        timeoutMillis = definition.timeoutMillis,
                    ),
                    riskLevel = riskLevel,
                ),
            )
            is AutonomyOutcome.Deny -> refused(outcome.reason, ToolOutcome.DENIED)
        }
    }

    /**
     * Спрашивает у пользователя разрешение на вызов (T-1.13).
     *
     * «Разрешить один раз» не запоминается нигде; «на сессию» запоминает реализация порта;
     * «запретить» — отказ, в журнале `approval = DENY`. Отмена ожидания прерывает корутину,
     * вызов не выполняется, и прогон завершается как остановленный.
     */
    private suspend fun requestApproval(
        frame: CallFrame,
        definition: AgentTool,
        arguments: JsonObject,
    ): Attempt {
        val request = ToolApprovalRequest(
            callId = frame.callId,
            runId = frame.runId,
            tool = definition.name,
            arguments = frame.toolArguments,
            kind = definition.kind,
            changesRepository = definition.changesRepository,
        )
        return when (val decision = autonomy.approval.request(frame.runId, request)) {
            ApprovalDecision.ALLOW_ONCE, ApprovalDecision.ALLOW_SESSION, ApprovalDecision.ALLOW_ALWAYS ->
                guarded(frame, definition, arguments).copy(requiredApproval = true, approval = decision)

            ApprovalDecision.DENY -> refused(
                "вызов «${definition.name}» запрещён пользователем",
                ToolOutcome.DENIED,
                requiredApproval = true,
                approval = ApprovalDecision.DENY,
            )
        }
    }

    /**
     * Вызов, которому разрешено выполняться; изменяющий репозиторий обязан получить точку отката (T-1.8).
     *
     * Признак — [AgentTool.changesRepository], а не «пишущая ось прав»: тесты и линтер
     * исполняются по оси записи (О-4), но файлов не меняют, и точка отката им не нужна.
     * Иначе каждый прогон тестов ставил бы снапшот, вытесняя осмысленные (T-1.19).
     *
     * Нет коммита или ссылку не записать — изменения не будет: вернуть агенту отказ и
     * оставить репозиторий как был. Почему не «продолжить без снапшота»: чтение без точки
     * отката ничего не стоит, а запись без неё теряет работу безвозвратно, и NFR-SAFE-2
     * требует, чтобы точка была **до** изменения. Отказ при этом не исключение, а результат:
     * прогон из-за него не ломается (О-9).
     */
    private suspend fun guarded(
        frame: CallFrame,
        definition: AgentTool,
        arguments: JsonObject,
    ): Attempt =
        if (!definition.changesRepository) {
            execute(definition, arguments, frame.context)
        } else {
            when (val snapshot = snapshots.beforeChange(frame.runId)) {
                is ChangeSnapshot.Taken -> withSnapshot(execute(definition, arguments, frame.context), snapshot.ref)

                ChangeSnapshot.NoHead -> refused(
                    "вызов «${definition.name}» не выполнен: в репозитории нет коммита, " +
                        "а без точки отката менять файлы нельзя",
                    ToolOutcome.DENIED,
                )

                is ChangeSnapshot.Failed -> refused(
                    "вызов «${definition.name}» не выполнен: точку отката поставить не удалось " +
                        "(${snapshot.reason})",
                    ToolOutcome.DENIED,
                )
            }
        }

    /** Неудача инструмента или отказ: прогон от этого не ломается (решение 3, О-4). */
    @Suppress("TooGenericExceptionCaught")
    private suspend fun execute(definition: AgentTool, arguments: JsonObject, context: ToolContext): Attempt = try {
        Attempt(withTimeout(definition.timeoutMillis) { definition.execute(arguments, context) })
    } catch (error: TimeoutCancellationException) {
        logger.warn("Инструмент «${definition.name}» не уложился в ${definition.timeoutMillis} мс", error)
        refused(
            "инструмент «${definition.name}» не уложился в лимит времени ${definition.timeoutMillis} мс",
            ToolOutcome.TIMEOUT,
            testReport = timeoutReport(definition),
        )
    } catch (error: HardLimitViolation) {
        // Жёсткий предел — это отказ, а не сбой: инструмент не «сломался», ему не разрешено
        // выходить за воркспейс, и решать, что делать дальше, будет модель (§ 10.1).
        refused("вызов отклонён жёстким пределом (${error.reason.name}): ${error.detail}", ToolOutcome.DENIED)
    } catch (error: Exception) {
        // Отмена корутины проходит насквозь: стоп прогона — не ошибка инструмента.
        currentCoroutineContext().ensureActive()
        logger.warn("Инструмент «${definition.name}» завершился ошибкой", error)
        refused("инструмент «${definition.name}» завершился ошибкой: ${error.message}", ToolOutcome.FAILURE)
    }
}

/**
 * Что нужно знать о вызове на каждом этапе обработки.
 *
 * Параметры собраны в один объект, чтобы не тащить пять аргументов через каждую
 * функцию точки вызова: detekt считает параметры, а поля data class — нет.
 */
private data class CallFrame(
    val runId: RunId,
    val callId: ToolCallId,
    val tool: String,
    val toolArguments: String,
    val context: ToolContext,
)

/** Разбирает аргументы модели как JSON-объект; null, если не похоже. */
private fun parseArguments(arguments: String): JsonObject? =
    runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonObject

/**
 * Неудача инструмента или отказ: прогон от этого не ломается (решение 3, О-4).
 *
 * Вынесена из [ToolInvoker], чтобы класс не перешагивал порог `TooManyFunctions`.
 */
private fun refused(
    text: String,
    outcome: ToolOutcome,
    requiredApproval: Boolean = false,
    approval: ApprovalDecision? = null,
    testReport: TestReport? = null,
): Attempt = Attempt(
    result = ToolResult(text = text, outcome = outcome, testReport = testReport),
    requiredApproval = requiredApproval,
    approval = approval,
)

/**
 * Отчёт, который несёт исход таймаута (T-1.10).
 *
 * `TestState.TIMEOUT` иначе недостижим: инструмент на таймауте не успевает вернуть
 * ничего, а состояние прогона пишется только из результата. Отчёт порождает тот
 * инструмент, что объявил это ([AgentTool.producesTestReport]) — у остальных таймаут
 * остаётся просто таймаутом, и выдумывать им отчёт незачем.
 */
private fun timeoutReport(definition: AgentTool): TestReport? =
    if (definition.producesTestReport) TestReport(state = TestState.TIMEOUT) else null

/** Исход попытки, признак подтверждения и само решение: всё едет в журнал. */
private data class Attempt(
    val result: ToolResult,
    val requiredApproval: Boolean = false,
    val approval: ApprovalDecision? = null,
)

/**
 * Кто просит вызов — модель или хост (T-1.11).
 *
 * Различие нужно ровно в одном месте: внутренний инструмент исполняется только по просьбе
 * хоста. Сложи оба пути в один — и модель, назвав `commit_step`, обошла бы и права, и
 * «один коммит на изменяющий шаг», потому что внутренний инструмент их не спрашивает.
 */
private enum class Origin { MODEL, INTERNAL }

/**
 * Тот же результат, но с точкой отката (T-1.8).
 *
 * Ссылка прикладывается ко всякому исходу выполненного изменяющего вызова, а не только
 * к успеху: снапшот уже создан и обязан быть виден прогону, иначе вытеснение сочтёт его
 * брошенным. Точка вызова остаётся единственным местом, знающим про снапшот, — инструмент
 * о нём не подозревает.
 */
private fun withSnapshot(attempt: Attempt, ref: SnapshotRef): Attempt =
    Attempt(
        result = attempt.result.copy(snapshotRef = ref),
        requiredApproval = attempt.requiredApproval,
        approval = attempt.approval,
    )
