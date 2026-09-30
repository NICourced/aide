package dev.aide.host.config

import dev.aide.domain.AgentConfig
import dev.aide.domain.ModelProfile
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.concurrent.thread
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-1.56: файл конфигурации моделей рядом с базой хоста.
 *
 * Проверяется то, что делает хост, а не формат JSON: чтение отсутствующего и битого файла,
 * переживание перезапуска, атомарность записи и — отдельно — отсутствие ключа в файле.
 */
class AgentConfigStoreTest {

    private val config = AgentConfig(
        defaultModel = "deepseek/deepseek-chat",
        providers = listOf(
            ProviderProfile(
                id = "deepseek",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "https://api.deepseek.com/v1",
                apiKeyEnv = KEY_VARIABLE,
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = "deepseek/deepseek-chat",
                provider = "deepseek",
                model = "deepseek-chat",
                contextWindow = 64_000,
                maxOutputTokens = 8_192,
                toolUse = true,
                pricePerMillionInMicros = 270_000,
                pricePerMillionOutMicros = 1_100_000,
            ),
        ),
    )

    private fun tempPath(): Path = Files.createTempDirectory("aide-config").resolve("agent-config.json")

    /** Временные файлы рядом с настройками: их после записи быть не должно. */
    private fun temporaryFiles(directory: Path): List<String> =
        Files.list(directory).use { files ->
            files.map { it.fileName.toString() }.filter { it.endsWith(".tmp") }.toList()
        }

    @Test
    fun `сохранённая конфигурация читается после перезапуска`() {
        val path = tempPath()
        AgentConfigStore(path).save(config)

        // Новый экземпляр на том же пути — это и есть «перезапуск хоста»: состояние живёт
        // в файле, а не в памяти хранилища.
        val afterRestart = AgentConfigStore(path).load()

        assertEquals(config, afterRestart)
        assertEquals("deepseek/deepseek-chat", afterRestart.defaultModel)
    }

    @Test
    fun `запись создаёт каталог, если его нет`() {
        val path = Files.createTempDirectory("aide-config-absent").resolve("nested").resolve("agent-config.json")

        AgentConfigStore(path).save(config)

        assertTrue(Files.isRegularFile(path), "файл настроек обязан появиться вместе с каталогом")
    }

    @Test
    fun `отсутствие файла даёт пустую конфигурацию`() {
        assertEquals(AgentConfig(), AgentConfigStore(tempPath()).load())
    }

    @Test
    fun `пустой файл даёт пустую конфигурацию`() {
        val path = tempPath()
        path.writeText("")

        assertEquals(AgentConfig(), AgentConfigStore(path).load())
    }

    @Test
    fun `битый файл даёт пустую конфигурацию, а не падение хоста`() {
        // Хост обязан подняться даже с испорченным файлом: иначе пользователь не смог бы
        // зайти в настройки и починить то, из-за чего хост не стартует.
        val path = tempPath()
        path.writeText("{ это не json")

        assertEquals(AgentConfig(), AgentConfigStore(path).load())
    }

    @Test
    fun `успешная запись заменяет прежнюю конфигурацию целиком`() {
        val path = tempPath()
        val store = AgentConfigStore(path)

        store.save(config)
        val changed = config.copy(defaultModel = "local/llama", providers = config.providers.take(0))
        store.save(changed)

        assertEquals(changed, store.load(), "файл должен содержать новую конфигурацию, а не смесь")
        assertEquals(emptyList(), temporaryFiles(path.parent), "временных файлов остаться не должно")
    }

    @Test
    fun `сорванная запись не оставляет временного файла и не трогает цель`() {
        // Атомарность не в том, что «мы пишем во временный файл», а в том, что после срыва
        // не остаётся мусора: иначе каталог настроек зарастает файлами, а следующая запись
        // идёт рядом с ними.
        val directory = Files.createTempDirectory("aide-config-busy")
        val path = directory.resolve("agent-config.json")
        // Цель занята непустым каталогом: переименование поверх обязано сорваться.
        Files.createDirectories(path.resolve("busy"))

        val store = AgentConfigStore(path)
        assertFailsWith<Exception> { store.save(config) }

        assertEquals(emptyList(), temporaryFiles(directory), "временный файл обязан быть убран при срыве")
        assertTrue(Files.isDirectory(path.resolve("busy")), "сорванная запись не должна трогать цель")
        assertEquals(AgentConfig(), store.load(), "читать по этому пути нечего — это каталог")
    }

    @Test
    fun `одновременные сохранения дают разбираемый файл, а не смесь`() {
        // Класс дефекта «общее состояние без защиты»: два сохранения с разных клиентов
        // (телефон и десктоп, повтор после реконнекта) не должны перемешивать файл —
        // иначе настройки теряются, а `load()` возвращает пустую конфигурацию.
        val directory = Files.createTempDirectory("aide-config-racing")
        val path = directory.resolve("agent-config.json")
        val store = AgentConfigStore(path)
        val variants = (0 until THREADS).map { index ->
            config.copy(defaultModel = "model/$index", providers = config.providers.take(index + 1))
        }

        val failures = CopyOnWriteArrayList<Throwable>()
        val workers = variants.map { variant ->
            thread {
                repeat(WRITES_PER_THREAD) {
                    runCatching { store.save(variant) }.onFailure { failures += it }
                }
            }
        }
        workers.forEach { it.join() }

        assertEquals(
            emptyList(),
            failures.map { it::class.simpleName }.distinct(),
            "одновременное сохранение не должно падать: клиент увидел бы потерю настроек",
        )
        val loaded = store.load()
        assertTrue(
            variants.any { it == loaded },
            "файл обязан содержать одну из сохранённых конфигураций целиком, а не смесь: $loaded",
        )
        assertEquals(emptyList(), temporaryFiles(directory), "временных файлов остаться не должно")
    }

    @Test
    fun `в файле настроек есть имя переменной окружения и нет самого ключа`() {
        // Ключ существует в окружении тестового процесса: без этого проверка «ключа нет
        // в файле» проходила бы по пустоте — сравнивать было бы не с чем.
        val secret = System.getenv(KEY_VARIABLE)
        assertTrue(!secret.isNullOrBlank(), "переменная $KEY_VARIABLE обязана быть задана тестовой задачей")

        val path = tempPath()
        AgentConfigStore(path).save(config)
        val text = path.readText()

        assertTrue(text.contains(KEY_VARIABLE), "имя переменной — единственное, что хранится: $text")
        assertFalse(text.contains(secret), "ключа не должно быть в файле настроек ни в каком виде")
        KEY_SUSPECTS.forEach { key ->
            assertFalse(text.contains("\"$key\""), "поля «$key» в файле быть не должно: $text")
        }
    }

    @Test
    fun `путь по умолчанию лежит рядом с базой хоста`() {
        val database = Files.createTempDirectory("aide-db").resolve("host.db")

        assertEquals(database.resolveSibling(AgentConfigStore.FILE_NAME), AgentConfigStore.defaultPath(database))
    }

    private companion object {
        /** Имя переменной окружения; значение задаёт тестовая задача `:host-core:test`. */
        const val KEY_VARIABLE = "AIDE_TEST_MODEL_KEY"

        /** Сколько потоков пишут одновременно. */
        const val THREADS = 8

        /** Сколько раз каждый поток сохраняет: без повторов гонка не успевает проявиться. */
        const val WRITES_PER_THREAD = 25

        /** Имена полей, которыми ключ мог бы просочиться в файл, если бы его туда писали. */
        val KEY_SUSPECTS = listOf("apiKey", "token", "secret", "password")
    }
}
