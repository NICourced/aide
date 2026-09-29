package dev.aide.desktop

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStoreAt
import dev.aide.client.ui.App
import dev.aide.host.EmbeddedHost
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlin.test.Test

/**
 * Сквозная проверка десктопа (T-0.15): приложение открывает настоящий репозиторий через
 * настоящее соединение WebSocket с настоящим хостом и показывает ветку, дерево и
 * содержимое файла, а после остановки хоста — состояние «нет связи» с данными из кэша.
 *
 * Проверка идёт по тому же композиционному корню, что и у настоящего приложения
 * ([dev.aide.client.ui.App]), на настоящем хосте из `host-core`, поэтому «глазами»
 * здесь проверять нечего: на экране оказывается ровно то, что утверждает тест.
 * Адрес хоста выдаётся `EmbeddedHost` на свободном порту, настройки пишутся в
 * временный файл — на настройки пользователя и на его репозитории тест не влияет.
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
    fun `десктоп открывает репозиторий и показывает ветку, дерево и файл`() = runComposeUiTest {
        val repo = fixture()
        val host = EmbeddedHost.open()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val connection = KtorHostConnection(endpoint = host.endpoint, scope = scope)
        val settingsFile = Files.createTempFile("aide-settings", ".properties")
        val settings = SettingsStore(createKeyValueStoreAt(settingsFile))

        try {
            connection.start()
            setContent { App(connection = connection, settings = settings, scope = scope) }

            waitUntil(timeoutMillis = 15_000) { connection.state.value is ConnectionState.Connected }
            println("E2E: соединение установлено ${connection.state.value}")

            onNodeWithText("Настройки").performClick()
            waitUntil(timeoutMillis = 5_000) {
                onAllNodesWithTag("settings-repository-path").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithTag("settings-repository-path").performTextInput(repo.toString())
            onNodeWithTag("settings-open").performClick()

            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithText("Ветка: master").fetchSemanticsNodes().isNotEmpty()
            }
            waitUntil(timeoutMillis = 15_000) {
                onAllNodesWithTag("tree-row-src/Login.kt").fetchSemanticsNodes().isNotEmpty()
            }

            onNodeWithText("Ветка: master").assertIsDisplayed()
            onNodeWithText("Репозиторий: $repo").assertIsDisplayed()
            onNodeWithTag("tree-row-src/Login.kt").assertIsDisplayed()
            println("E2E: дерево показано")

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
}
