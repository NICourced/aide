package dev.aide.tools.sandbox

import dev.aide.tools.ToolsWorkspace
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * T-1.10: команда проекта определяется по маркерам, а не моделью (решение 1).
 *
 * Проверяется наблюдаемый факт: у каждой раскладки файлов проекта получается своя
 * команда, а у незнакомого проекта команды нет вовсе — тогда инструмент откажет,
 * вместо того чтобы запустить угаданное.
 */
class ProjectCommandsTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `исполняемая обёртка gradlew задаёт команду через точку`() {
        executable("gradlew", GRADLEW_STUB)

        val command = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))

        assertEquals(listOf("./gradlew", "test"), command.command)
        assertTrue(command.reportGlobs.contains("build/test-results/*/*.xml"), "${command.reportGlobs}")
    }

    @Test
    fun `маркер gradle без обёртки даёт системный gradle`() {
        workspace.write("build.gradle.kts", "")

        val command = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))

        assertEquals(listOf("gradle", "test"), command.command)
    }

    @Test
    fun `groovy-маркер build gradle тоже распознаётся`() {
        workspace.write("build.gradle", "plugins {}\n")

        val command = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))

        assertEquals(listOf("gradle", "test"), command.command)
    }

    @Test
    fun `неисполняемая обёртка не считается исполняемой`() {
        workspace.write("gradlew", GRADLEW_STUB)
        workspace.write("build.gradle.kts", "")

        val command = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))

        assertEquals(listOf("gradle", "test"), command.command, "неисполняемый gradlew запустить нельзя")
    }

    @Test
    fun `pom без gradle даёт maven`() {
        workspace.write("pom.xml", "<project/>")

        val command = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))

        assertEquals(listOf("mvn", "test"), command.command)
        assertTrue(command.reportGlobs.contains("target/surefire-reports/*.xml"), "${command.reportGlobs}")
    }

    @Test
    fun `незнакомый проект команды не даёт`() {
        workspace.write("package.json", "{}")

        assertNull(ProjectCommands.detect(workspace.root, ProjectTool.TESTS))
        assertNull(ProjectCommands.detect(workspace.root, ProjectTool.LINT))
    }

    @Test
    fun `линтер есть у gradle и нет у maven`() {
        executable("gradlew", GRADLEW_STUB)
        val lint = assertNotNull(ProjectCommands.detect(workspace.root, ProjectTool.LINT))
        assertEquals(listOf("./gradlew", "detekt"), lint.command)
        assertTrue(lint.reportGlobs.contains("build/reports/detekt/detekt.xml"), "${lint.reportGlobs}")

        val maven = ToolsWorkspace()
        try {
            maven.write("pom.xml", "<project/>")
            assertNull(ProjectCommands.detect(maven.root, ProjectTool.LINT), "у maven строки линтера в таблице нет")
        } finally {
            maven.close()
        }
    }

    /** Создаёт исполняемый файл-заглушку: без бита запуска `./gradlew` не считается обёрткой. */
    private fun executable(name: String, body: String) {
        val file = workspace.write(name, body).toFile()
        assertTrue(file.setExecutable(true), "не удалось выставить бит запуска на $name")
    }

    private companion object {
        const val GRADLEW_STUB: String = "#!/bin/sh\nexit 0\n"
    }
}
