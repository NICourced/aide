package dev.aide.tools.file

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * Строковый аргумент вызова.
 *
 * Отсутствие поля или другой тип дают пустую строку, а не исключение: к моменту вызова
 * аргументы уже проверены по схеме (решение 2), и повторять здесь проверку значило бы
 * держать её в двух местах. Пустая строка при этом — обычный отказ инструмента:
 * он объясняет модели, чего не хватает, вместо «инструмент завершился ошибкой».
 */
internal fun JsonObject.stringArgument(name: String): String =
    (this[name] as? JsonPrimitive)?.takeIf { it.isString }?.content.orEmpty()
