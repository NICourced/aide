package dev.aide.tools

import dev.aide.domain.RunId
import dev.aide.domain.SnapshotRef
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolCallId
import dev.aide.domain.ToolOutcome
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.permission.PermissionDecision
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.permission.ToolKind
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
 *   через эту точку вызова, и гарантию надо ставить там же, где права и журнал.
 */
class ToolInvoker(
    private val registry: ToolRegistry,
    private val permissions: PermissionResolver,
    private val recorder: ToolCallRecorder,
    private val snapshots: ChangeSnapshots,
    private val clock: () -> Instant = Clock.System::now,
) {

    private val logger = LoggerFactory.getLogger(ToolInvoker::class.java)

    /**
     * Вызывает инструмент и записывает вызов в журнал — с любым исходом.
     *
     * @param arguments аргументы строкой, как их прислала модель: формат у протоколов
     *   разный, и разбирает их здесь тот, кто проверяет их по схеме.
     */
    suspend fun invoke(runId: RunId, tool: String, arguments: String, context: ToolContext): ToolResult {
        val startedNanos = System.nanoTime()
        val attempt = attempt(runId, tool, arguments, context)
        val elapsedMillis = (System.nanoTime() - startedNanos) / NANOS_PER_MILLI
        // Идентификатор вызова генерирует хост, а не берёт у провайдера: у того он
        // уникален только внутри диалога, а журнал — таблица на весь хост, и совпавший
        // идентификатор двух провайдеров потерял бы одну запись.
        recorder.record(
            ToolCall(
                id = ToolCallId(UUID.randomUUID().toString()),
                runId = runId,
                tool = tool,
                arguments = arguments,
                result = attempt.result.text,
                outcome = attempt.result.outcome,
                durationMillis = elapsedMillis,
                cost = attempt.result.cost,
                requiredApproval = attempt.requiredApproval,
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
    private suspend fun attempt(runId: RunId, tool: String, arguments: String, context: ToolContext): Attempt {
        val definition = registry.find(tool)
        val parsed = if (definition == null) null else parseArguments(arguments)
        return when {
            definition == null -> refused("инструмента «$tool» агент не знает", ToolOutcome.FAILURE)
            parsed == null -> refused("аргументы вызова «$tool» не являются JSON-объектом", ToolOutcome.FAILURE)
            else -> decide(runId, definition, parsed, context)
        }
    }

    /** Решение по проверенному вызову: схема, затем права. */
    private suspend fun decide(
        runId: RunId,
        definition: AgentTool,
        arguments: JsonObject,
        context: ToolContext,
    ): Attempt {
        val complaint = ArgumentsSchema.check(definition.argumentsSchema, arguments)
        return if (complaint != null) {
            refused("${definition.name}: $complaint", ToolOutcome.FAILURE)
        } else {
            permitted(runId, definition, arguments, context)
        }
    }

    /** Вызов, аргументы которого приняты; остаётся спросить права и выполнить. */
    private suspend fun permitted(
        runId: RunId,
        definition: AgentTool,
        arguments: JsonObject,
        context: ToolContext,
    ): Attempt =
        when (val decision = permissions.resolve(definition.name, definition.kind, definition.declaredPermission)) {
            PermissionDecision.Allow -> guarded(runId, definition, arguments, context)

            is PermissionDecision.Deny -> refused(
                "вызов «${definition.name}» запрещён настройками (${decision.reason.name})",
                ToolOutcome.DENIED,
            )

            // Подтверждения спрашивает диалог (T-1.13), а не точка вызова: пока диалога
            // нет, «нужно спросить у пользователя» — это отказ, и в журнале он помечен
            // как требовавший подтверждения.
            PermissionDecision.Ask -> refused(
                "вызов «${definition.name}» требует подтверждения пользователя, а спросить его пока нечем",
                ToolOutcome.DENIED,
                requiredApproval = true,
            )
        }

    /**
     * Вызов, которому разрешено выполняться; изменяющий обязан получить точку отката (T-1.8).
     *
     * Нет коммита или ссылку не записать — изменения не будет: вернуть агенту отказ и
     * оставить репозиторий как был. Почему не «продолжить без снапшота»: чтение без точки
     * отката ничего не стоит, а запись без неё теряет работу безвозвратно, и NFR-SAFE-2
     * требует, чтобы точка была **до** изменения. Отказ при этом не исключение, а результат:
     * прогон из-за него не ломается (О-9).
     */
    private suspend fun guarded(
        runId: RunId,
        definition: AgentTool,
        arguments: JsonObject,
        context: ToolContext,
    ): Attempt =
        if (definition.kind != ToolKind.WRITE) {
            execute(definition, arguments, context)
        } else {
            when (val snapshot = snapshots.beforeChange(runId)) {
                is ChangeSnapshot.Taken -> withSnapshot(execute(definition, arguments, context), snapshot.ref)

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

    private fun parseArguments(arguments: String): JsonObject? =
        runCatching { Json.parseToJsonElement(arguments) }.getOrNull() as? JsonObject

    private fun refused(text: String, outcome: ToolOutcome, requiredApproval: Boolean = false): Attempt =
        Attempt(ToolResult(text = text, outcome = outcome), requiredApproval)
}

/** Исход попытки и то, спрашивали ли подтверждение: и то и другое едет в журнал. */
private data class Attempt(val result: ToolResult, val requiredApproval: Boolean = false)

/**
 * Тот же результат, но с точкой отката (T-1.8).
 *
 * Ссылка прикладывается ко всякому исходу выполненного изменяющего вызова, а не только
 * к успеху: снапшот уже создан и обязан быть виден прогону, иначе вытеснение сочтёт его
 * брошенным. Точка вызова остаётся единственным местом, знающим про снапшот, — инструмент
 * о нём не подозревает.
 */
private fun withSnapshot(attempt: Attempt, ref: SnapshotRef): Attempt =
    Attempt(attempt.result.copy(snapshotRef = ref), attempt.requiredApproval)

