package dev.aide.client.ui

import dev.aide.client.ui.strings.modelCheckResource
import dev.aide.domain.ModelCheckFailure
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * T-1.56: каждый отказ проверки модели показывается своим текстом.
 *
 * Разбор `ModelCheckFailure` исчерпывающий, и это проверяется здесь: если ветку заменить
 * на общий `else`, разных причин станет меньше, чем причин вовсе, — а пользователь по
 * общему «проверка не удалась» не поймёт, что ему править (NFR-13).
 */
class ModelCheckTextTest {

    private val allFailures: List<ModelCheckFailure?> = listOf(
        null,
        ModelCheckFailure.NotConfigured,
        ModelCheckFailure.UnknownModel("stub/model"),
        ModelCheckFailure.MissingKey("STUB_KEY"),
        ModelCheckFailure.Unsupported,
        ModelCheckFailure.Unauthorized,
        ModelCheckFailure.RateLimited,
        ModelCheckFailure.RequestFailed("таймаут"),
        ModelCheckFailure.ResponseUnreadable("не JSON"),
    )

    @Test
    fun `каждый отказ проверки показывается своим текстом`() {
        val resources = allFailures.map { modelCheckResource(it) }

        assertEquals(
            allFailures.size,
            resources.toSet().size,
            "отказов ${allFailures.size}, а разных текстов ${resources.toSet().size}: " +
                "часть причин показывается одинаково",
        )
    }

    @Test
    fun `отсутствие ключа и неверный ключ показываются по-разному`() {
        // Самая дорогая путаница: «задайте переменную» против «ключ отвергнут» — это
        // разные действия пользователя, и одинаковый текст увёл бы его не туда.
        assertTrue(
            modelCheckResource(ModelCheckFailure.MissingKey("K")) !=
                modelCheckResource(ModelCheckFailure.Unauthorized),
        )
    }
}
