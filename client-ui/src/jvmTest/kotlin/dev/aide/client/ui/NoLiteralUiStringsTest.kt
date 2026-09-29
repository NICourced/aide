package dev.aide.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NFR-13: все строки интерфейса — в ресурсах, в коде нет литералов UI.
 *
 * Проверяются вызовы `Text(` и `contentDescription =` со строковым литералом
 * в общих экранах. Литералы в тестах и в `strings.xml`, разумеется, разрешены.
 */
class NoLiteralUiStringsTest {

    private val root = File(
        System.getProperty("clientUiSourcesDir")
            ?: error("Не задано системное свойство clientUiSourcesDir — проверь блок jvmTest в build.gradle.kts"),
    )

    private val literalInText = Regex("""\bText\(\s*"""")
    private val literalInDescription = Regex("""contentDescription\s*=\s*"""")

    @Test
    fun `в общих экранах нет строковых литералов интерфейса`() {
        val commonMain = File(root, "commonMain")
        assertTrue(commonMain.isDirectory, "Каталог не найден: $commonMain")

        val offenders = commonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.invariantSeparatorsPath.contains("/$STRINGS_DIRECTORY/") }
            .flatMap { file ->
                val text = file.readText()
                buildList {
                    literalInText.findAll(text).forEach { add("${file.name}: Text(\"…\")") }
                    literalInDescription.findAll(text).forEach { add("${file.name}: contentDescription = \"…\"") }
                }.asSequence()
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Строки UI должны быть в composeResources (NFR-13). Найдены литералы:\n" +
                offenders.joinToString("\n"),
        )
    }

    private companion object {
        /** Сам доступ к строкам держит литералы ресурсов — он и есть их объявление. */
        const val STRINGS_DIRECTORY = "strings"
    }
}
