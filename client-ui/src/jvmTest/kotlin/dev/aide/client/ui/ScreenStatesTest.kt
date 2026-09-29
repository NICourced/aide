package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.screens.FileContentScreen
import dev.aide.client.ui.screens.RepoTreeScreen
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test

/**
 * Пять состояний § 6.1 проверяются на самой узкой поддерживаемой ширине — 360 dp.
 * Ширина задаётся явно, иначе тест проверял бы ширину окна сборочной машины.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenStatesTest {

    private val workspaceId = WorkspaceId("ws-1")

    private val tree = FileTreePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        entries = listOf(
            FileTreeEntry("src", isDirectory = true),
            FileTreeEntry("src/Login.kt", isDirectory = false, sizeBytes = 12),
        ),
        truncated = false,
    )

    private fun narrow(content: @Composable () -> Unit): @Composable () -> Unit = {
        Box(modifier = Modifier.width(360.dp)) { content() }
    }

    @Test
    fun `состояние загрузки показывает скелетон, а не пустой экран`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Loading, onFileClick = {}, onRetry = {}) })

        onNodeWithTag("skeleton-row-0").assertIsDisplayed()
    }

    @Test
    fun `пустое дерево объясняет, что делать`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Empty, onFileClick = {}, onRetry = {}) })

        onNodeWithText("В репозитории нет коммитов и файлов. Создайте первый коммит — дерево появится.")
            .assertIsDisplayed()
    }

    @Test
    fun `ошибка показывает что случилось и кнопку повтора`() = runComposeUiTest {
        setContent(
            narrow {
                RepoTreeScreen(
                    state = ScreenState.Failed(ScreenState.ErrorKind.PATH_MISSING, "Путь не существует: /nope"),
                    onFileClick = {},
                    onRetry = {},
                )
            },
        )

        onNodeWithText("Путь не существует: /nope").assertIsDisplayed()
        onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun `нет связи помечает кэш и всё равно показывает дерево`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Offline(tree), onFileClick = {}, onRetry = {}) })

        onNodeWithText("Нет связи с хостом").assertIsDisplayed()
        onNodeWithTag("tree-row-src/Login.kt").assertIsDisplayed()
    }

    @Test
    fun `нет прав называет путь и причину`() = runComposeUiTest {
        setContent(
            narrow {
                RepoTreeScreen(
                    state = ScreenState.NoPermission("/etc/passwd", "вне корня воркспейса"),
                    onFileClick = {},
                    onRetry = {},
                )
            },
        )

        onNodeWithText("Нет доступа").assertIsDisplayed()
        onNodeWithText("Доступ к «/etc/passwd» закрыт: вне корня воркспейса").assertIsDisplayed()
    }

    @Test
    fun `загруженное содержимое файла показывается`() = runComposeUiTest {
        val content = FileContentPayload(workspaceId, "src/Login.kt", "fun login() = Unit\n", 19, false, "kotlin")
        setContent(narrow { FileContentScreen(state = ScreenState.Loaded(content), onRetry = {}) })

        onNodeWithTag("file-content").assertIsDisplayed()
        onNodeWithText("Файл: src/Login.kt").assertIsDisplayed()
    }

    @Test
    fun `обрезанный файл помечается`() = runComposeUiTest {
        val content = FileContentPayload(workspaceId, "big.txt", "aaa", 600_000, truncated = true, language = null)
        setContent(narrow { FileContentScreen(state = ScreenState.Loaded(content), onRetry = {}) })

        onNodeWithText("Файл показан не целиком: превышен предел показа").assertIsDisplayed()
    }

    @Test
    fun `экран файла показывает загрузку`() = runComposeUiTest {
        setContent(narrow { FileContentScreen(state = ScreenState.Loading, onRetry = {}) })

        onNodeWithTag("skeleton-row-0").assertIsDisplayed()
    }

    @Test
    fun `экран файла показывает ошибку с повтором`() = runComposeUiTest {
        setContent(
            narrow {
                val error = ScreenState.Failed(
                    ScreenState.ErrorKind.OTHER,
                    "Воркспейс закрыт, откройте репозиторий заново",
                )
                FileContentScreen(state = error, onRetry = {})
            },
        )

        onNodeWithText("Воркспейс закрыт, откройте репозиторий заново").assertIsDisplayed()
        onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun `экран файла в офлайне показывает кэш и плашку`() = runComposeUiTest {
        val content = FileContentPayload(workspaceId, "src/Login.kt", "fun login() = Unit\n", 19, false, "kotlin")
        setContent(narrow { FileContentScreen(state = ScreenState.Offline(content), onRetry = {}) })

        onNodeWithText("Нет связи с хостом").assertIsDisplayed()
        onNodeWithTag("file-content").assertIsDisplayed()
    }

    @Test
    fun `экран файла без прав называет путь`() = runComposeUiTest {
        setContent(
            narrow {
                val denied = ScreenState.NoPermission("secret.txt", "нет прав на чтение файла")
                FileContentScreen(state = denied, onRetry = {})
            },
        )

        onNodeWithText("Нет доступа").assertIsDisplayed()
        onNodeWithText("Доступ к «secret.txt» закрыт: нет прав на чтение файла").assertIsDisplayed()
    }
}
