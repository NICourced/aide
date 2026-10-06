package dev.aide.tools.schema

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.longOrNull

// Имена типов JSON Schema: объявлены на уровне файла, потому что ими пользуются
// и проверка типов внутри объекта, и подпись отказа рядом с ней.
private const val TYPE_STRING: String = "string"
private const val TYPE_BOOLEAN: String = "boolean"
private const val TYPE_NUMBER: String = "number"
private const val TYPE_INTEGER: String = "integer"
private const val TYPE_OBJECT: String = "object"
private const val TYPE_ARRAY: String = "array"

/**
 * Проверка аргументов вызова по схеме инструмента (решение 2, T-1.7).
 *
 * Модель может вернуть не то, что описано в схеме, и разбирать это внутри инструмента
 * значило бы повторять одну проверку трижды. Поэтому проверка идёт до вызова, в одной
 * точке, и её отказ — обычный результат для модели: та поправится сама.
 *
 * Поддержан ровно тот объём JSON Schema, который объявляют инструменты этапа 1:
 * `type: object`, `properties` с типом поля, `required` и `items` у массива (T-1.9).
 * Незнакомое ключевое слово схемы (`description`, `enum`, что угодно ещё) не делает
 * вызов негодным: схемы пишем мы же, и запрещать в них лишнее значило бы ломать вызов
 * на описании поля.
 */
object ArgumentsSchema {

    private const val KEY_PROPERTIES: String = "properties"
    private const val KEY_REQUIRED: String = "required"
    private val EMPTY_OBJECT: JsonObject = JsonObject(emptyMap())

    /**
     * Проверяет аргументы.
     *
     * @return null, если вызов можно выполнять; иначе — текст отказа для модели, из
     *   которого видно, что именно не так и какие аргументы допустимы.
     */
    fun check(schema: JsonObject, arguments: JsonObject): String? {
        val properties = schema.properties()
        val required = schema.requiredNames()
        return missingArgument(required, arguments)
            ?: unknownArgument(properties, arguments)
            ?: argumentOfWrongType(properties, arguments)
    }

    /** Обязательного аргумента нет: модель чаще всего просто забыла его. */
    private fun missingArgument(required: List<String>, arguments: JsonObject): String? {
        val missing = required.firstOrNull { it !in arguments } ?: return null
        return "не передан обязательный аргумент «$missing»; обязательные: ${required.joinToString(", ")}"
    }

    /** Аргумент не объявлен: почти всегда это опечатка в имени, и назвать его полезно. */
    private fun unknownArgument(properties: Map<String, JsonObject>, arguments: JsonObject): String? {
        val unknown = arguments.keys.firstOrNull { it !in properties } ?: return null
        return "неизвестный аргумент «$unknown»; допустимы: ${properties.keys.joinToString(", ")}"
    }

    /**
     * Тип аргумента не тот, что объявлен; у массива проверяются ещё и элементы (T-1.9).
     *
     * Элементы — потому что `command` у `run_command` объявлен массивом строк: без этой
     * проверки `["ls", 42]` уехало бы в процесс, а не вернулось модели отказом. Проверка
     * узкая — ровно объявленный нами `items`, а не общий JSON Schema.
     */
    private fun argumentOfWrongType(properties: Map<String, JsonObject>, arguments: JsonObject): String? =
        arguments.entries.firstNotNullOfOrNull { (name, value) -> complaint(name, properties[name], value) }

    /** Претензия к одному аргументу: сначала его собственный тип, потом тип элементов. */
    private fun complaint(name: String, property: JsonObject?, value: JsonElement): String? {
        val expected = property?.typeName()
        if (!matches(expected, value)) {
            return "аргумент «$name» должен быть ${word(expected)}, а пришёл ${describe(value)}"
        }
        return arrayElementComplaint(name, property, value)
    }

    /** Отказ по элементу массива; null — либо не массив, либо `items` не объявлены. */
    private fun arrayElementComplaint(name: String, property: JsonObject?, value: JsonElement): String? {
        val array = value as? JsonArray ?: return null
        val itemType = property?.itemsTypeName()
        val wrong = itemType?.let { type -> array.firstOrNull { !matches(type, it) } }
        return wrong?.let {
            "аргумент «$name»: элемент массива должен быть ${word(itemType)}, а пришёл ${describe(it)}"
        }
    }

    /** Незнакомый тип в схеме считается «проверять нечем»: отказ здесь был бы ложным. */
    private fun matches(type: String?, value: JsonElement): Boolean = when (type) {
        null -> true
        TYPE_STRING -> value.primitive()?.isString == true
        TYPE_BOOLEAN -> value.primitive()?.takeIf { !it.isString }?.booleanOrNull != null
        TYPE_NUMBER -> value.primitive()?.takeIf { !it.isString }?.doubleOrNull != null
        TYPE_INTEGER -> value.primitive()?.takeIf { !it.isString }?.longOrNull != null
        TYPE_OBJECT -> value is JsonObject
        TYPE_ARRAY -> value is JsonArray
        else -> true
    }

    private fun JsonObject.properties(): Map<String, JsonObject> =
        (this[KEY_PROPERTIES] as? JsonObject)
            ?.mapValues { (_, value) -> (value as? JsonObject) ?: EMPTY_OBJECT }
            .orEmpty()

    private fun JsonObject.requiredNames(): List<String> =
        (this[KEY_REQUIRED] as? JsonArray)
            ?.mapNotNull { it.primitive()?.takeIf { p -> p.isString }?.content }
            .orEmpty()

    private fun JsonElement.primitive(): JsonPrimitive? = this as? JsonPrimitive
}

private const val KEY_TYPE: String = "type"
private const val KEY_ITEMS: String = "items"

private fun JsonObject.typeName(): String? =
    (this[KEY_TYPE] as? JsonPrimitive)?.takeIf { it.isString }?.content

/** Тип элементов массива, если он объявлен: у `command` это `string`. */
private fun JsonObject.itemsTypeName(): String? = (this[KEY_ITEMS] as? JsonObject)?.typeName()

/** Слово для ожидаемого типа — то, что модель прочитает первой. */
private fun word(type: String?): String = when (type) {
    TYPE_STRING -> "строкой"
    TYPE_BOOLEAN -> "логическим значением"
    TYPE_NUMBER -> "числом"
    TYPE_INTEGER -> "целым числом"
    TYPE_OBJECT -> "объектом"
    TYPE_ARRAY -> "массивом"
    else -> "значением другого типа"
}

/** Чем оказался аргумент на самом деле: по этому модель поймёт, что она послала. */
private fun describe(value: JsonElement): String = when {
    value is JsonArray -> "массив"
    value is JsonObject -> "объект"
    value !is JsonPrimitive -> "значение неизвестного типа"
    value.isString -> "строка"
    value.booleanOrNull != null -> "логическое значение"
    value.doubleOrNull != null -> "число"
    else -> "значение неизвестного типа"
}
