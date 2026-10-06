package dev.aide.tools.schema

import kotlin.test.Test
import kotlin.test.assertContains
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * T-1.9: проверка элементов массива по объявленному `items`.
 *
 * Без неё схема `run_command` принимала бы `["ls", 42]`, и число уехало бы в процесс:
 * `type: array` проверял только то, что пришёл массив. Проверка узкая — ровно наш
 * `items`, а не общий JSON Schema.
 */
class ArgumentsSchemaTest {

    @Test
    fun `массив с нестроковым элементом отклоняется`() {
        val complaint = ArgumentsSchema.check(SCHEMA, commandArguments("ls", 42))

        assertNotNull(complaint, "элемент-число обязан быть отклонён")
        assertContains(complaint, "элемент массива", message = "модель обязана понять, что не так: $complaint")
        assertContains(complaint, "число")
    }

    @Test
    fun `массив строк проходит`() {
        val complaint = ArgumentsSchema.check(SCHEMA, commandArguments("git", "status"))

        assertNull(complaint, "массив строк обязан приниматься: $complaint")
    }

    /** Аргументы со списком команды, где элементы заданного типа подмешиваются к строкам. */
    private fun commandArguments(vararg elements: Any): JsonObject = buildJsonObject {
        putJsonArray("command") {
            elements.forEach { element ->
                when (element) {
                    is String -> add(element)
                    is Number -> add(element)
                    else -> error("в тесте нет другого типа, кроме строки и числа")
                }
            }
        }
    }

    private companion object {

        /** Схема `run_command`: список команды из строк — то, ради чего проверка и добавлена. */
        val SCHEMA: JsonObject = buildJsonObject {
            put("type", "object")
            putJsonObject("properties") {
                putJsonObject("command") {
                    put("type", "array")
                    putJsonObject("items") { put("type", "string") }
                }
            }
            putJsonArray("required") { add("command") }
        }
    }
}
