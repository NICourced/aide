package dev.aide.agent.prompt

import dev.aide.agent.llm.LlmRole
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

        // Без комментария запрос прежний: система и одна реплика с задачей (T-1.2).
        assertEquals(2, request.messages.size, "лишних реплик у первого плана нет: ${request.messages}")

        // Системная часть — первая реплика диалога (T-1.7): у chat completions она едет
        // в общем списке, у Anthropic — отдельным полем, и место в списке задаёт её хост.
        assertEquals(LlmRole.SYSTEM, request.messages.first().role)
        assertTrue(request.messages.first().content.isNotBlank(), "системная часть задаёт роль агента и формат ответа")
        assertTrue(
            request.messages.any { it.role == LlmRole.USER && it.content.contains("Почини сборку") },
            "постановка задачи обязана уехать моделью: ${request.messages}",
        )
    }

    @Test
    fun `комментарий уезжает отдельной репликой, а не подклеивается в задачу`() {
        val task = testTask("t-1", TaskStatus.QUEUED).copy(prompt = "Почини сборку")

        val request = PlannerPrompt.request(task, "разбей на два шага")

        assertEquals(3, request.messages.size, "система, задача и комментарий: ${request.messages}")
        assertEquals("Почини сборку", request.messages[1].content, "исходная задача не переписана")
        assertEquals(LlmRole.USER, request.messages[2].role)
        assertEquals("разбей на два шага", request.messages[2].content)
    }

    @Test
    fun `пустой и пробельный комментарий не добавляют реплику`() {
        val task = testTask("t-1", TaskStatus.QUEUED).copy(prompt = "Почини сборку")

        // Пустая реплика ничего не уточняет, и планировщик вызвался бы впустую (T-1.2).
        assertEquals(2, PlannerPrompt.request(task, "").messages.size)
        assertEquals(2, PlannerPrompt.request(task, "   ").messages.size)
    }
}
