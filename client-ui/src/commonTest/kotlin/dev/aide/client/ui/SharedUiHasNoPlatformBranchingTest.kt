package dev.aide.client.ui

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

class SharedUiHasNoPlatformBranchingTest {

    private val forbidden = listOf(
        "expect fun platformName",
        "System.getProperty(\"os.name\")",
        "android.os.Build",
        "isLinux",
        "isWindows",
        "isMacOs",
    )

    /**
     * Задача `jvmTest` запускается с рабочим каталогом модуля, поэтому исходники лежат
     * рядом (`src/commonMain`); второй вариант — запас на запуск из корня репозитория.
     */
    private val sourceRoot = listOf("src/commonMain", "client-ui/src/commonMain")
        .map { it.toPath() }
        .firstOrNull(FileSystem.SYSTEM::exists)
        ?: error("Не найден каталог с исходниками commonMain ни от каталога модуля, ни от корня сборки")

    @Test
    fun `общие экраны не содержат ветвлений по платформе`() {
        val root = sourceRoot
        val offenders = mutableListOf<String>()
        FileSystem.SYSTEM.listRecursively(root).forEach { path ->
            if (!path.name.endsWith(".kt")) return@forEach
            val text = FileSystem.SYSTEM.read(path) { readUtf8() }
            forbidden.forEach { needle ->
                if (text.contains(needle)) offenders += "${path.name}: $needle"
            }
        }
        assertTrue(offenders.isEmpty(), "Платформенные ветвления в общем UI: $offenders")
    }
}
