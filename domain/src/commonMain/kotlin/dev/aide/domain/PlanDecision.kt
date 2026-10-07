package dev.aide.domain

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Решение пользователя по показанному плану (T-1.2, FR-AGENT-7).
 *
 * Живёт в домене рядом с [RunCommand] по той же причине: тип едет по протоколу, а
 * рантайм агента о протоколе знать не должен. Варианты, а не перечисление, потому что
 * «переделать» несёт комментарий, которого у «подтвердить» нет.
 */
@Serializable
sealed interface PlanDecision {

    /** План принят: работа начинается. */
    @Serializable
    @SerialName("approve")
    data object Approve : PlanDecision

    /**
     * План отклонён с комментарием: планировщик вызывается заново.
     *
     * Комментарий уходит отдельной репликой пользователя, а не подклеивается в исходную
     * задачу: иначе история диалога врала бы о том, что просил человек (T-1.2).
     */
    @Serializable
    @SerialName("replan")
    data class Replan(
        /** Что пользователь просит в плане изменить. */
        val comment: String,
    ) : PlanDecision
}
