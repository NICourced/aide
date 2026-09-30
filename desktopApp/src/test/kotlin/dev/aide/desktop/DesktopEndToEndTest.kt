package dev.aide.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.agent.llm.LlmClient
import dev.aide.agent.llm.LlmRequest
import dev.aide.agent.llm.LlmResponse
import dev.aide.agent.provider.ConfiguredModel
import dev.aide.agent.provider.AgentModels
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStoreAt
import dev.aide.client.ui.App
import dev.aide.domain.Cost
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.Task
import dev.aide.host.EmbeddedHost
import dev.aide.host.store.DatabaseFactory
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Сквозная проверка десктопа (T-0.15, T-1.1, T-1.18): приложение открывает настоящий
 * репозиторий через настоящее соединение WebSocket с настоящим хостом и показывает ветку,
 * дерево и содержимое файла; задача, поставленная из интерфейса, проходит состояния прогона
 * до завершения, а её ветка `ai/<task-id>` появляется в шапке; после остановки хоста
 * показывается состояние «нет связи» с данными из кэша.
 *
 * Проверка идёт по тому же композиционному корню, что и у настоящего приложения
 * ([dev.aide.client.ui.App]), на настоящем хосте из `host-core`, поэтому «глазами»
 * здесь проверять нечего: на экране оказывается ровно то, что утверждает тест.
 * Адрес хоста выдаётся `EmbeddedHost` на свободном порту, настройки и база пишутся
 * во временные файлы — на настройки пользователя и на его репозитории тест не влияет.
 * Модель скриптованная: настоящее провайдера в CI нет и не будет (О-2).
 */
@OptIn(ExperimentalTestApi::class)
class DesktopEndToEndTest {

    private fun run(vararg command: String, dir: Path) {
        val process = ProcessBuilder(*command).directory(dir.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.readBytes().decodeToString()
        check(process.waitFor() == 0) { "Команда ${command.toList()} не выполнилась: $output" }
    }

    private fun fixture(): Path {
        val dir = Files.createTempDirectory("aide-e2e")
        run("git", "init", "-b", "master", dir = dir)
        run("git", "config", "user.email", "dev@aide.local", dir = dir)
        run("git", "config", "user.name", "Dev", dir = dir)
        Files.createDirectories(dir.resolve("src"))
        Files.writeString(dir.resolve("src/Login.kt"), "fun login() = Unit\n")
        run("git", "add", ".", dir = dir)
        run("git", "commit", "-m", "первый коммит", dir = dir)
        Files.writeString(dir.resolve("src/Login.kt"), "fun login() = \"token\"\n")
        return dir
    }

    @Test
    fun `десктоп открывает репозиторий при запуске и показывает ветку, дерево и файл`() = runComposeUiTest {
        val repo = fixture()
        val host = EmbeddedHost.open(databasePath = tempDatabase())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = KtorHostConnection(endpoint = host.endpoint, scope = scope)
        val settingsFile = Files.createTempFile("aide-settings", ".properties")
        val settings = SettingsStore(createKeyValueStoreAt(settingsFile))
        // Путь сохранён в настройках: приложение обязано открыть его само (T-0.15),
        // а не ждать, пока пользователь зайдёт в настройки и нажмёт «Открыть».
        settings.repositoryPath = repo.toString()

        try {
            connection.start()
            setContent { App(connection = connection, settings = settings, scope = scope) }

            waitUntil(timeoutMillis = 15_000) { connection.state.value is ConnectionState.Connected }
            println("E2E: соединение установлено ${connection.state.value}")

            // Никаких действий в настройках: дерево и ветка должны появиться сами.
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Ветка: master").fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("tree-row-src/Login.kt").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Ветка: master").assertIsDisplayed()
            onNodeWithText("Репозиторий: $repo").assertIsDisplayed()
            onNodeWithTag("tree-row-src/Login.kt").assertIsDisplayed()
            println("E2E: репозиторий открыт при запуске, дерево показано")

            onNodeWithTag("tree-row-src/Login.kt").performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Файл: src/Login.kt").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Файл: src/Login.kt").assertIsDisplayed()
            onNodeWithText("fun login() = \"token\"\n").assertIsDisplayed()
            println("E2E: содержимое файла показано")

            host.close()
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText("Нет связи с хостом").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Нет связи с хостом").assertIsDisplayed()
            onNodeWithText("fun login() = \"token\"\n").assertIsDisplayed()
            println("E2E: состояние «нет связи» показано с кэшем")
        } finally {
            runBlocking { connection.stop() }
            host.close()
            scope.cancel()
        }
    }

    @Test
    fun `задача, поставленная из интерфейса, доходит до состояния завершён`() = runComposeUiTest {
        val repo = fixture()
        val database = tempDatabase()
        val host = EmbeddedHost.open(
            databasePath = database,
            models = object : AgentModels {
                override fun current(): Result<ConfiguredModel> =
                    Result.success(ConfiguredModel(SCRIPTED_ALIAS, ScriptedModel()))

                // Проверка доступа скриптованной модели не поддержана: транспорта у неё нет.
                override suspend fun check(alias: String): ModelCheckFailure? = ModelCheckFailure.Unsupported
            },
        )
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = KtorHostConnection(endpoint = host.endpoint, scope = scope)
        val settings = SettingsStore(createKeyValueStoreAt(Files.createTempFile("aide-settings-agent", ".properties")))
        // Путь сохранён в настройках: приложение открывает репозиторий само (T-0.15),
        // а прогон без открытого репозитория не начинается — ему негде взять ветку (T-1.18).
        settings.repositoryPath = repo.toString()

        try {
            connection.start()
            setContent { App(connection = connection, settings = settings, scope = scope) }
            waitUntil(timeoutMillis = 15_000) { connection.state.value is ConnectionState.Connected }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Ветка: master").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Агент").performClick()
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("task-input").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("task-input").performTextInput("Почини сборку")
            onNodeWithTag("post-task").performClick()

            // Состояние приходит событием RunStateChanged; строка состояния обязана его показать.
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText("Завершён").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Завершён").assertIsDisplayed()
            println("E2E: задача поставлена и прогон дошёл до завершения")

            // Шапка показывает ветку задачи: имя ветки знает только хост, поэтому оно
            // читается из базы хоста, а не выводится тестом по соглашению (T-1.18).
            val posted = taskFrom(database)
            assertEquals("ai/${posted.id.value}", posted.branch, "задаче записана ветка ai/<task-id>")
            waitUntil(timeoutMillis = 20_000) {
                onAllNodesWithText("Ветка: ${posted.branch}").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Ветка: ${posted.branch}").assertIsDisplayed()
            println("E2E: шапка показывает ветку задачи ${posted.branch}")
        } finally {
            runBlocking { connection.stop() }
            host.close()
            scope.cancel()
            repo.toFile().deleteRecursively()
        }
    }

    @Test
    fun `раздел модель в настройках приходит с хоста и принимает заготовку`() = runComposeUiTest {
        val host = EmbeddedHost.open(databasePath = tempDatabase())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = KtorHostConnection(endpoint = host.endpoint, scope = scope)
        val settingsFile = Files.createTempFile("aide-settings-model", ".properties")
        val settings = SettingsStore(createKeyValueStoreAt(settingsFile))

        try {
            connection.start()
            setContent { App(connection = connection, settings = settings, scope = scope) }
            waitUntil(timeoutMillis = 15_000) { connection.state.value is ConnectionState.Connected }

            onNodeWithText("Настройки").performClick()

            // Каталог заготовок едет с хоста: без связи раздела с конфигурацией хоста
            // этих кнопок не было бы вовсе.
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("catalog-add-openai").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("model-key-note").performScrollTo().assertExists()

            onNodeWithTag("catalog-add-openai").performScrollTo().performClick()

            // Добавленная заготовка появилась в таблице провайдеров и сохранена на хосте.
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("provider-base-url-openai").fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithTag("provider-base-url-openai").performScrollTo().assertIsDisplayed()
            println("E2E: раздел «Модель» подключён к хосту и принимает заготовку")
        } finally {
            runBlocking { connection.stop() }
            host.close()
            scope.cancel()
        }
    }

    @Test
    fun `без сохранённого пути приложение предлагает выбрать репозиторий`() = runComposeUiTest {
        val host = EmbeddedHost.open(databasePath = tempDatabase())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = KtorHostConnection(endpoint = host.endpoint, scope = scope)
        val settingsFile = Files.createTempFile("aide-settings-empty", ".properties")
        val settings = SettingsStore(createKeyValueStoreAt(settingsFile))

        try {
            connection.start()
            setContent { App(connection = connection, settings = settings, scope = scope) }

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Репозиторий не выбран. Укажите путь к нему в настройках.")
                    .fetchSemanticsNodes().isNotEmpty()
            }
            onNodeWithText("Репозиторий не выбран. Укажите путь к нему в настройках.").assertIsDisplayed()
        } finally {
            runBlocking { connection.stop() }
            host.close()
            scope.cancel()
        }
    }

    /**
     * База во временном файле: без явного пути хост открыл бы базу приложения
     * в домашнем каталоге, и тест писал бы в данные пользователя.
     */
    private fun tempDatabase(): Path = Files.createTempFile("aide-host", ".db")

    /**
     * Задача, записанная хостом; имя ветки задачи знает только хост (T-1.18).
     *
     * База открывается вторым подключением на чтение: соглашение `ai/<task-id>` в тесте
     * не повторяется, иначе он проверял бы свою же строку, а не то, что записал хост.
     */
    private fun taskFrom(database: Path): Task {
        val store = DatabaseFactory.open(database)
        return try {
            store.tasks.all().single()
        } finally {
            store.close()
        }
    }

    /**
     * Скриптованная модель: первый ответ — план, дальше — «шаг выполнен».
     *
     * Формат плана — часть контракта `PlannerPrompt`; тест задаёт его строкой, поэтому
     * сквозной прогон не зависит от настоящего провайдера и сети (О-2, О-11).
     */
    private class ScriptedModel : LlmClient {
        private var calls = 0

        override suspend fun complete(request: LlmRequest): LlmResponse {
            calls += 1
            val text = if (calls == 1) {
                """{"steps":[{"summary":"Прочитать логи"},{"summary":"Исправить сборку"}]}"""
            } else {
                "шаг выполнен"
            }
            return LlmResponse.Text(text = text, cost = Cost(), elapsedMillis = 1)
        }
    }

    private companion object {
        /** Алиас, под которым скриптованная модель попадает в прогон. */
        const val SCRIPTED_ALIAS = "test/scripted"
    }
}
