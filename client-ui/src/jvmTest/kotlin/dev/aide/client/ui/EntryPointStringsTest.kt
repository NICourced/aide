package dev.aide.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NFR-13 для точек входа: имя приложения и заголовок окна — тоже строки интерфейса.
 *
 * Проверяются места, до которых `NoLiteralUiStringsTest` не достаёт: заголовок
 * окна десктопа и `android:label` в манифесте. Имя приложения обязано приходить
 * из ресурсов, иначе переименование продукта потребует правок в коде.
 */
class EntryPointStringsTest {

    private val repoRoot = File(
        System.getProperty("repoRootDir")
            ?: error("Не задано системное свойство repoRootDir — проверь блок jvmTest в build.gradle.kts"),
    )

    private val literalInTitle = Regex("""title\s*=\s*"""")

    @Test
    fun `заголовок окна десктопа берётся из ресурсов`() {
        val desktopMain = File(repoRoot, "desktopApp/src/main")
        assertTrue(desktopMain.isDirectory, "Каталог не найден: $desktopMain")

        val scanned = desktopMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(
            scanned.isNotEmpty(),
            "В $desktopMain нет ни одного .kt-файла — проверь системное свойство repoRootDir",
        )

        val offenders = scanned.filter { literalInTitle.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot).path }

        assertTrue(
            offenders.isEmpty(),
            "Заголовок окна должен приходить из Strings, а не из литерала (NFR-13). Найдено:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `android label ссылается на строковый ресурс`() {
        val manifest = File(repoRoot, "androidApp/src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "Файл не найден: $manifest")

        val label = Regex("""android:label="([^"]*)"""").find(manifest.readText())?.groupValues?.get(1)
        assertTrue(label != null, "В манифесте нет android:label: имя приложения не задано явно")
        assertTrue(
            label.startsWith("@string/"),
            "android:label должен ссылаться на строковый ресурс, а не на литерал: $label",
        )
    }
}
