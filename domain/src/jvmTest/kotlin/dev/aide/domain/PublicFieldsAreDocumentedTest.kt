package dev.aide.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Требование T-0.5: у каждого публичного поля есть документирующий комментарий,
 * проверяется сборкой документации. Здесь проверяется наличие KDoc над полем,
 * а сама сборка документации выполняется задачей `:domain:dokkaGenerate`.
 */
class PublicFieldsAreDocumentedTest {

    private val modelFiles = listOf(
        "Task.kt", "AgentRun.kt", "ToolCall.kt", "ChangePacket.kt", "ReviewDecision.kt", "Snapshot.kt",
    )

    @Test
    fun `каждое публичное поле моделей описано KDoc`() {
        val root = File(
            System.getProperty("domainSourcesDir")
                ?: error("Не задано системное свойство domainSourcesDir — проверь блок jvmTest в build.gradle.kts"),
        )
        assertTrue(root.isDirectory, "Каталог исходников не найден: $root")

        val undocumented = mutableListOf<String>()

        modelFiles.forEach { fileName ->
            val file = File(root, "dev/aide/domain/$fileName")
            assertTrue(file.isFile, "Модельный файл не найден: ${file.path}")

            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val isPublicProperty = line.startsWith("    val ") || line.startsWith("    var ")
                if (!isPublicProperty) return@forEachIndexed

                val previousMeaningful = lines.take(index).lastOrNull { it.isNotBlank() }.orEmpty()
                if (!previousMeaningful.trimEnd().endsWith("*/")) {
                    undocumented += "$fileName:${index + 1} → ${line.trim()}"
                }
            }
        }

        assertTrue(
            undocumented.isEmpty(),
            "Поля без документирующего комментария:\n" + undocumented.joinToString("\n"),
        )
    }
}
