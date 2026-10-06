package dev.aide.agent

import dev.aide.agent.tools.StepTools
import dev.aide.domain.PlanStep
import dev.aide.domain.RunId
import dev.aide.tools.CommitStepTool

/**
 * Фиксация изменяющего шага коммитом в ветке задачи (T-1.11).
 *
 * Отдельный класс от [AgentRunEngine] по той же причине, что [RunSnapshots] и
 * [TaskBranchPlacement]: движок отвечает за цикл и переходы состояния, а вызов
 * инструмента — за журнал, таймаут и замер времени. Сборка аргументов здесь одна
 * на все ветки: разложи её по движку, и следующий вызов внутреннего инструмента
 * собрал бы их иначе.
 *
 * Вызов идёт через ту же точку вызова, что и вызовы модели (`StepTools.invokeInternal`):
 * так коммит попадает в журнал (FR-AGENT-8) и измеряется по времени (О-3). Ветка и
 * сообщение — единственное, что знает движок; автора задаёт git-слой, а хеш коммита
 * в прогоне не хранится — история уже в git (решение 10).
 *
 * Сбой коммита прогон не роняет и не прячется: отказ возвращается результатом и остаётся
 * видимым в журнале. Ронять из-за него готовый шаг не за что, а молчать о нём нельзя:
 * тогда расхождение «шаг прошёл, коммита нет» обнаружилось бы только в истории
 * репозитория, и уже задним числом.
 */
internal class StepCommitWriter(private val tools: StepTools) {

    /** Фиксирует изменения шага [step] в ветке [branch] прогона [runId]. */
    suspend fun commit(runId: RunId, branch: String, step: PlanStep) {
        tools.invokeInternal(
            runId = runId,
            name = CommitStepTool.TOOL_NAME,
            arguments = CommitStepTool.arguments(branch, stepCommitMessage(step)),
        )
    }
}

/** Сообщение коммита шага: номер шага с единицы, как в задании модели, и описание из плана (решение 6). */
private fun stepCommitMessage(step: PlanStep): String = "Шаг ${step.index + 1}: ${step.summary}"
