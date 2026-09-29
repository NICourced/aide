package dev.aide.domain.risk

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Контракт FR-AGENT-6 / § 5.3.2: уровень риска вычисляется только правилами, LLM может
 * добавить пояснение, но не понизить уровень. Проверяется структурно — по исходнику
 * [RiskEvaluator]: во входе не должно быть полей-подсказок, а у `evaluate` не должно быть
 * третьего параметра.
 *
 * Рефлексия (`KClass.members`) здесь не используется: она требует `kotlin-reflect` и
 * недоступна в `commonTest` KMP-модуля. Чтение исходника файловой системой — уже принятый
 * в проекте приём (`PublicFieldsAreDocumentedTest`); путь приходит системным свойством
 * `domainSourcesDir`, которое выставляет `build.gradle.kts`.
 */
class RiskEvaluatorContractTest {

    private val text: String by lazy {
        val root = File(
            System.getProperty("domainSourcesDir")
                ?: error("Не задано системное свойство domainSourcesDir — проверь блок jvmTest в build.gradle.kts"),
        )
        val file = File(root, "dev/aide/domain/risk/RiskEvaluator.kt")
        assertTrue(file.isFile, "Исходник RiskEvaluator.kt не найден: ${file.path}")
        file.readText()
    }

    @Test
    fun `во входе вычисления нет полей, которые заполняет LLM`() {
        val fields = Regex("""\bval\s+(\w+)\s*:""")
            .findAll(argumentsOf("data class Input("))
            .map { match -> match.groupValues[1] }
            .toList()
        assertTrue(fields.isNotEmpty(), "Не удалось разобрать поля Input — тест больше не проверяет контракт")

        val suspicious = fields.filter { isLlmSuspicious(it) }
        assertTrue(
            suspicious.isEmpty(),
            "Риск вычисляется только правилами § 5.3.2 (FR-AGENT-6), но во входе появились поля: $suspicious",
        )
    }

    @Test
    fun `у evaluate только два параметра и ни одного с подсказкой модели`() {
        val params = Regex("""(\w+)\s*:""")
            .findAll(argumentsOf("fun evaluate("))
            .map { match -> match.groupValues[1] }
            .toList()
        assertEquals(
            listOf("changes", "volumeThresholdLines"),
            params,
            "Подпись evaluate изменилась: подсказка модели не должна влиять на риск (FR-AGENT-6)",
        )
    }

    /** Текст внутри скобок объявления [declaration], найденный по балансу круглых скобок. */
    private fun argumentsOf(declaration: String): String {
        val start = text.indexOf(declaration)
        assertTrue(start >= 0, "В RiskEvaluator.kt не найдено объявление: $declaration")

        val open = text.indexOf('(', start)
        var depth = 0
        for (index in open until text.length) {
            when (text[index]) {
                '(' -> depth++
                ')' -> {
                    depth--
                    if (depth == 0) return text.substring(open + 1, index)
                }
            }
        }
        error("Не найден закрывающий ')' для объявления: $declaration")
    }

    private fun isLlmSuspicious(name: String): Boolean {
        val lower = name.lowercase()
        return listOf("suggest", "llm", "model", "advice", "hint", "confidence").any { lower.contains(it) }
    }
}
