package dev.aide.agent.prompt

import dev.aide.agent.testTask
import dev.aide.domain.StepStatus
import dev.aide.domain.TaskStatus
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** T-1.1: разбор плана из ответа модели — чистый JSON, JSON в обрамлении, мусор. */
class PlannerPromptTest {

    @Test
    fun `чистый JSON разбирается в шаги плана`() {
        val steps = PlannerPrompt.parse("""{"steps":[{"summary":"Прочитать файл"},{"summary":"Изменить функцию"}]}""")

        assertEquals(listOf("Прочитать файл", "Изменить функцию"), steps.map { it.summary })
        assertEquals(listOf(0, 1), steps.map { it.index })
        assertTrue(steps.all { it.status == StepStatus.PENDING }, "план до начала работы — все шаги PENDING")
    }

    @Test
    fun `JSON в markdown и в тексте вокруг разбирается`() {
        val fenced = "Вот план:\n```json\n{\"steps\":[{\"summary\":\"Шаг раз\"}]}\n```\nГотово."
        assertEquals(listOf("Шаг раз"), PlannerPrompt.parse(fenced).map { it.summary })

        val prose = "План: {\"steps\":[{\"summary\":\"Шаг два\"}]} — приступаю."
        assertEquals(listOf("Шаг два"), PlannerPrompt.parse(prose).map { it.summary })
    }

    @Test
    fun `мусор даёт внятный отказ, а не пустой план`() {
        assertFailsWith<PlanFormatException> { PlannerPrompt.parse("не буду ничего делать") }
        assertFailsWith<PlanFormatException> { PlannerPrompt.parse("""{"steps":[]}""") }
        assertFailsWith<PlanFormatException> { PlannerPrompt.parse("""{"steps":[{}]}""") }
        assertFailsWith<PlanFormatException> { PlannerPrompt.parse("""{"steps":"не массив"}""") }
    }

    @Test
    fun `запрос на планирование несёт постановку задачи`() {
        val task = testTask("t-1", TaskStatus.QUEUED).copy(prompt = "Почини сборку")
        val request = PlannerPrompt.request(task)

        assertTrue(request.system.isNotBlank(), "системная часть задаёт роль агента и формат ответа")
        assertTrue(request.messages.any { it.contains("Почини сборку") })
    }
}
