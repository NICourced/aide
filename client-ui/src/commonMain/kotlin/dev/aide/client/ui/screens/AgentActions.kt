package dev.aide.client.ui.screens

import dev.aide.domain.PlanDecision
import dev.aide.domain.RunCommand
import dev.aide.domain.RunId

/**
 * Действия экрана агента: постановка задачи, журнал вызовов, решение по плану и управление прогоном.
 *
 * Один объект, а не три параметра: у `AgentScreen` уже пять параметров, и шестой перешагнул
 * бы предел `LongParameterList`, который проект ослаблять не разрешает. Так же устроено
 * состояние экрана настроек моделей (`ModelSettingsState`).
 */
class AgentActions(
    /** Поставить задачу в очередь хоста. */
    val postTask: (String) -> Unit,
    /** Открыть журнал вызовов (T-1.3). */
    val openLog: () -> Unit = {},
    /** Решение по показанному плану: подтвердить или переделать (T-1.2). */
    val decidePlan: (RunId, PlanDecision) -> Unit = { _, _ -> },
    /**
     * Пауза, продолжение или остановка прогона (T-1.4).
     *
     * Одно действие на три команды, а не три лямбды: команда — значение перечисления,
     * и на хосте её принимает тот же `controlRun(runId, command)`. Три отдельные лямбды
     * разошлись бы с ним и завели лишние точки перевода нажатия в команду.
     */
    val controlRun: (RunId, RunCommand) -> Unit = { _, _ -> },
)
