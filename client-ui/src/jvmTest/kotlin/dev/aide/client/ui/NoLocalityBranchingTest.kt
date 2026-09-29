package dev.aide.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-0.13: клиент не различает локальный хост и удалённый.
 *
 * Проверяется статически: в исходниках `client-ui` не должно быть признаков
 * локальности. Критерий требует нуля совпадений в обоих клиентских модулях,
 * поэтому тот же тест есть в `client-state`.
 */
class NoLocalityBranchingTest {

    private val sourcesRoot = File(
        System.getProperty(SOURCES_DIR_PROPERTY)
            ?: error("Не задано системное свойство $SOURCES_DIR_PROPERTY — проверь блок jvmTest в build.gradle.kts"),
    )

    /** Признаки того, что код знает о локальности хоста. */
    private val forbidden = listOf(
        "isLocalHost",
        "isRemoteHost",
        "HostMode.LOCAL",
        "HostMode.REMOTE",
        "localHost",
        "remoteHost",
        "embeddedHost",
        "EmbeddedHost",
        "if (local)",
    )

    @Test
    fun `в клиентском коде нет признаков локальности хоста`() {
        assertTrue(sourcesRoot.isDirectory, "Каталог исходников не найден: $sourcesRoot")

        var scanned = 0
        val offenders = mutableListOf<String>()
        sourcesRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.isTestSource() }
            .forEach { file ->
                scanned += 1
                val text = file.readText()
                forbidden.forEach { needle ->
                    if (text.contains(needle)) offenders += "${file.relativeTo(sourcesRoot)}: $needle"
                }
            }

        assertTrue(scanned > 0, "В $sourcesRoot не найдено ни одного .kt — проверка шла бы по пустоте")
        assertTrue(
            offenders.isEmpty(),
            "Клиент не должен ветвиться по признаку локальности хоста (§ 3.3). Найдено:\n" +
                offenders.joinToString("\n"),
        )
    }

    /**
     * Тестовые наборы исключаются: запрещённые подстроки перечислены в самом этом тесте,
     * и без исключения он находил бы себя. Сравнение идёт по `invariantSeparatorsPath`:
     * на Windows `path` содержит обратные слэши, и фильтр по «/jvmTest/» не сработал бы.
     */
    private fun File.isTestSource(): Boolean =
        invariantSeparatorsPath.contains("/$JVM_TEST_SET/") ||
            invariantSeparatorsPath.contains("/$COMMON_TEST_SET/")

    private companion object {
        /** Системное свойство с путём к `src` клиента; задаётся в build.gradle.kts модуля. */
        const val SOURCES_DIR_PROPERTY = "clientUiSourcesDir"

        /** Наборы с тестовым кодом: статическая проверка смотрит только продуктовый код. */
        const val JVM_TEST_SET = "jvmTest"
        const val COMMON_TEST_SET = "commonTest"
    }
}
