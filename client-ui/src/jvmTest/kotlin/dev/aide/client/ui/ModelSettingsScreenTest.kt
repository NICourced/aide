package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import androidx.compose.ui.test.runComposeUiTest
import dev.aide.client.state.ModelCheckOutcome
import dev.aide.client.ui.screens.ModelSettingsScreen
import dev.aide.client.ui.screens.ModelSettingsState
import dev.aide.client.state.ModelConfigError
import dev.aide.domain.AgentConfig
import dev.aide.domain.AgentConfigRejection
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ModelSecretRejection
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import dev.aide.domain.SecretStoreUnavailableReason
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * T-1.56: раздел «Модель» в настройках на состояниях § 6.1 — загрузка, пустая
 * конфигурация, заполненная; отдельно — добавление заготовки и своего провайдера,
 * правка поля и отказ проверки.
 *
 * Тексты сюда приходят из ресурсов (NFR-13), поэтому проверяются их точные значения:
 * литералы в общих экранах запрещены и проверяются отдельным тестом.
 */
@OptIn(ExperimentalTestApi::class)
class ModelSettingsScreenTest {

    private var saved: AgentConfig? = null
    private var checked: String? = null
    private val secretWrites = mutableListOf<Pair<String, String>>()
    private val secretDeletes = mutableListOf<String>()

    private fun state(
        config: AgentConfig?,
        catalog: List<ProviderCatalogEntry> = emptyList(),
        secrets: Map<String, ModelSecretStatus> = emptyMap(),
        check: ModelCheckOutcome? = null,
        error: ModelConfigError? = null,
    ): ModelSettingsState = ModelSettingsState(
        config = config,
        catalog = catalog,
        secrets = secrets,
        check = check,
        error = error,
        onSave = { saved = it },
        onCheck = { checked = it },
        onSetSecret = { providerId, value -> secretWrites += providerId to value },
        onDeleteSecret = { providerId -> secretDeletes += providerId },
    )

    /**
     * Раздел внутри прокручиваемого родителя — так же, как он живёт в настройках.
     * Без прокрутки нижние поля оказались бы за границей окна, и щелчок по ним не дошёл бы
     * до обработчика: тест проверял бы границы окна, а не экран.
     */
    private fun screen(state: ModelSettingsState): @Composable () -> Unit = {
        Box(modifier = Modifier.verticalScroll(rememberScrollState())) {
            ModelSettingsScreen(state = state)
        }
    }

    /** Точный текст предупреждения: раздел обязан говорить, где хранится ключ. */
    private val keyNote =
        "Ключ в файле настроек не хранится. Он сохраняется в защищённом хранилище платформы хоста " +
            "(Linux — libsecret, Windows — DPAPI), а если ключа там нет — берётся из переменной " +
            "окружения хоста по имени."

    @Test
    fun `загруженная конфигурация показывает провайдера, модели и выбранную по умолчанию`() = runComposeUiTest {
        setContent(screen(state(config())))

        onNodeWithText("Модель").assertIsDisplayed()
        onNodeWithText("Модель по умолчанию: stub/model").assertIsDisplayed()
        onNodeWithTag("provider-base-url-stub").assertExists()
        onNodeWithTag("provider-key-env-stub").assertExists()
        onNodeWithTag("model-alias-stub/model").assertExists()
        onNodeWithTag("model-context-stub/model").assertExists()
        onNodeWithText(keyNote).assertIsDisplayed()
    }

    @Test
    fun `пока настройки не загружены, показывается загрузка, а не пустой экран`() = runComposeUiTest {
        setContent(screen(state(config = null)))

        onNodeWithTag("model-loading").assertIsDisplayed()
        onNodeWithText("Провайдеров пока нет — добавьте заготовку или своего провайдера.").assertDoesNotExist()
    }

    @Test
    fun `пустая конфигурация объясняет, что делать`() = runComposeUiTest {
        setContent(screen(state(config = AgentConfig())))

        onNodeWithText("Провайдеров пока нет — добавьте заготовку или своего провайдера.").assertIsDisplayed()
        onNodeWithText("Моделей пока нет.").assertIsDisplayed()
        onNodeWithText("Модель по умолчанию: не выбрана").assertIsDisplayed()
    }

    @Test
    fun `заготовка добавляется одним действием и сохраняется сразу`() = runComposeUiTest {
        val entry = catalogEntry()

        setContent(screen(state(config = AgentConfig(), catalog = listOf(entry))))
        onNodeWithTag("catalog-add-ollama").performScrollTo().performClick()

        val result = assertNotNull(saved, "добавление заготовки обязано сохраниться без второго действия")
        assertEquals(listOf("ollama"), result.providers.map { it.id })
        assertEquals(listOf("ollama/llama"), result.models.map { it.alias })
    }

    @Test
    fun `заготовка, добавленная дважды, не удваивает таблицу`() = runComposeUiTest {
        setContent(screen(state(config = AgentConfig(), catalog = listOf(catalogEntry()))))

        onNodeWithTag("catalog-add-ollama").performScrollTo().performClick()
        val first = assertNotNull(saved)
        setContent(screen(state(config = first, catalog = listOf(catalogEntry()))))
        onNodeWithTag("catalog-add-ollama").performScrollTo().performClick()

        assertEquals(1, assertNotNull(saved).providers.size, "повторное добавление не создаёт второго провайдера")
    }

    @Test
    fun `свой провайдер с произвольным адресом и именем переменной добавляется`() = runComposeUiTest {
        setContent(screen(state(config = AgentConfig())))

        onNodeWithTag("custom-provider-id").performScrollTo().performTextInput("my-gateway")
        onNodeWithTag("custom-provider-base-url").performScrollTo().performTextInput("https://gateway.local/v1")
        onNodeWithTag("custom-provider-key-env").performScrollTo().performTextInput("MY_GATEWAY_KEY")
        onNodeWithTag("custom-provider-add").performScrollTo().performClick()

        val provider = assertNotNull(saved).providers.single()
        assertEquals("my-gateway", provider.id)
        assertEquals("https://gateway.local/v1", provider.baseUrl)
        assertEquals("MY_GATEWAY_KEY", provider.apiKeyEnv)
        assertEquals(ProviderType.OPENAI_COMPATIBLE, provider.type)
    }

    @Test
    fun `кнопка добавления своего провайдера молчит, пока не заполнены обязательные поля`() = runComposeUiTest {
        setContent(screen(state(config = AgentConfig())))

        onNodeWithTag("custom-provider-id").performScrollTo().performTextInput("my-gateway")
        onNodeWithTag("custom-provider-add").performScrollTo().performClick()

        assertEquals(null, saved, "без адреса провайдер не добавляется: он был бы нерабочим")
    }

    @Test
    fun `поле заготовки правится вручную и сохраняется`() = runComposeUiTest {
        setContent(screen(state(config = config())))

        // Любое поле правится: здесь — размер контекста и ставка ввода.
        onNodeWithTag("model-context-stub/model").performScrollTo().performTextReplacement("4096")
        onNodeWithTag("model-price-in-stub/model").performScrollTo().performTextReplacement("750000")
        onNodeWithTag("model-save").performScrollTo().performClick()

        val model = assertNotNull(saved).models.first { it.alias == "stub/model" }
        assertEquals(4_096, model.contextWindow)
        assertEquals(750_000L, model.pricePerMillionInMicros)
    }

    @Test
    fun `протокол провайдера и адрес тоже правятся`() = runComposeUiTest {
        setContent(screen(state(config = config())))

        onNodeWithTag("provider-base-url-stub").performScrollTo().performTextReplacement("https://proxy.local/v1")
        onNodeWithTag("provider-type-stub-ANTHROPIC").performScrollTo().performClick()
        onNodeWithTag("model-save").performScrollTo().performClick()

        val provider = assertNotNull(saved).providers.single()
        assertEquals("https://proxy.local/v1", provider.baseUrl)
        assertEquals(ProviderType.ANTHROPIC, provider.type)
    }

    @Test
    fun `выбор модели по умолчанию сохраняется`() = runComposeUiTest {
        setContent(screen(state(config = config())))

        onNodeWithTag("model-make-default-stub/second").performScrollTo().performClick()
        onNodeWithTag("model-save").performScrollTo().performClick()

        assertEquals("stub/second", assertNotNull(saved).defaultModel)
    }

    @Test
    fun `неудачная загрузка показывается вместо бесконечного ожидания`() = runComposeUiTest {
        setContent(screen(state(config = null, error = ModelConfigError.Unreachable)))

        onNodeWithText("Не удалось связаться с хостом: настройки моделей не загружены или не сохранены")
            .assertIsDisplayed()
        onNodeWithTag("model-loading").assertDoesNotExist()
    }

    @Test
    fun `неудачное сохранение видно, и правка остаётся на экране`() = runComposeUiTest {
        val error = ModelConfigError.Rejected(AgentConfigRejection.DuplicateModelAlias("stub/model"))

        setContent(screen(state(config = config(), error = error)))

        onNodeWithText("Алиас модели повторяется: stub/model").assertIsDisplayed()
        onNodeWithTag("model-alias-stub/model").assertExists()
    }

    @Test
    fun `причина отказа конфигурации показывается с именами полей`() = runComposeUiTest {
        setContent(
            screen(
                state(
                    config = config(),
                    error = ModelConfigError.Rejected(
                        AgentConfigRejection.UnknownProvider(alias = "stub/second", provider = "нет-такого"),
                    ),
                ),
            ),
        )

        onNodeWithText("У модели stub/second не найден провайдер «нет-такого»").assertIsDisplayed()
    }

    @Test
    fun `пустой адрес и неположительный размер показываются словами`() = runComposeUiTest {
        setContent(
            screen(
                state(
                    config = config(),
                    error = ModelConfigError.Rejected(AgentConfigRejection.EmptyBaseUrl("stub")),
                ),
            ),
        )
        onNodeWithText("У провайдера «stub» пустой базовый адрес").assertIsDisplayed()
    }

    @Test
    fun `имя модели берётся из displayName, а если оно пусто — из идентификатора`() = runComposeUiTest {
        val withoutName = config().let { current ->
            current.copy(models = listOf(current.models.first().copy(displayName = "")))
        }

        setContent(screen(state(config = withoutName)))

        // Имя для показа — идентификатор сервера: пустое displayName означает «зови как сервер».
        onNodeWithTag("model-title-stub/model").assertTextEquals("stub-model")
    }

    @Test
    fun `проверка модели запускается по кнопке`() = runComposeUiTest {
        setContent(screen(state(config = config())))

        onNodeWithTag("model-check-stub/second").performScrollTo().performClick()

        assertEquals("stub/second", checked)
    }

    @Test
    fun `отказ проверки показывается с именем переменной окружения`() = runComposeUiTest {
        val check = ModelCheckOutcome("stub/model", ModelCheckFailure.MissingKey("STUB_KEY"))

        setContent(screen(state(config(), check = check)))

        onNodeWithText("Нет переменной окружения STUB_KEY — задайте её и перезапустите хост")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `подтверждённая проверка показывается словами об успехе`() = runComposeUiTest {
        val check = ModelCheckOutcome("stub/model", failure = null)

        setContent(screen(state(config(), check = check)))

        assertTrue(check.ok)
        onNodeWithText("Доступ подтверждён").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `ключ задан показывается состоянием хранилища`() = runComposeUiTest {
        setContent(screen(state(config(), secrets = mapOf("stub" to ModelSecretStatus.InStore))))

        onNodeWithTag("provider-secret-status-stub").performScrollTo().assertIsDisplayed()
        onNodeWithText("Ключ задан в защищённом хранилище").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `ключ из окружения показывается с именем переменной`() = runComposeUiTest {
        setContent(screen(state(config(), secrets = mapOf("stub" to ModelSecretStatus.FromEnv))))

        onNodeWithText("Ключ берётся из переменной окружения STUB_KEY").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun `недоступное хранилище показывается с причиной`() = runComposeUiTest {
        // Причина — словами, а не кодом перечисления: пользователю нужно понять, почему
        // ключ нельзя сохранить, а не прочитать `TOOL_MISSING` (NFR-13).
        val secrets = mapOf(
            "stub" to ModelSecretStatus.StoreUnavailable(SecretStoreUnavailableReason.TOOL_MISSING),
        )

        setContent(screen(state(config(), secrets = secrets)))

        onNodeWithText("Хранилище ключей недоступно: на хосте не найдена программа secret-tool (libsecret)")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `сохранение ключа передаёт значение и очищает поле`() = runComposeUiTest {
        // Значение хост не возвращает, поэтому поле обязано опустеть: показывать в нём
        // прежний текст значило бы утверждать, что приложение знает ключ.
        setContent(screen(state(config())))

        onNodeWithTag("provider-secret-input-stub").performScrollTo().performTextInput("живой-ключ")
        onNodeWithTag("provider-secret-save-stub").performScrollTo().performClick()

        assertEquals(listOf("stub" to "живой-ключ"), secretWrites)
        assertEquals("", onNodeWithTag("provider-secret-input-stub").editableText())
    }

    @Test
    fun `кнопка сохранения молчит, пока ключ не введён`() = runComposeUiTest {
        setContent(screen(state(config())))

        onNodeWithTag("provider-secret-save-stub").performScrollTo().performClick()

        assertTrue(secretWrites.isEmpty(), "пустое значение отправлять нечего: это отказ, а не удаление")
    }

    @Test
    fun `удаление ключа вызывается кнопкой`() = runComposeUiTest {
        setContent(screen(state(config(), secrets = mapOf("stub" to ModelSecretStatus.InStore))))

        onNodeWithTag("provider-secret-delete-stub").performScrollTo().performClick()

        assertEquals(listOf("stub"), secretDeletes)
    }

    @Test
    fun `отказ записи ключа показывается словами`() = runComposeUiTest {
        val error = ModelConfigError.SecretRejected(ModelSecretRejection.UnknownProvider("нет-такого"))

        setContent(screen(state(config(), error = error)))

        onNodeWithText("Провайдер «нет-такого» не найден в настройках — сохраните конфигурацию и повторите")
            .performScrollTo()
            .assertIsDisplayed()
    }

    @Test
    fun `отказ проверки без ключа называет и переменную, и провайдера`() = runComposeUiTest {
        // Ключ можно задать двумя способами, и в отказе обязаны быть оба имени (T-1.58).
        val check = ModelCheckOutcome("stub/model", ModelCheckFailure.MissingKey("STUB_KEY", "stub"))

        setContent(screen(state(config(), check = check)))

        val expected = "Ключ не задан: задайте переменную STUB_KEY или сохраните ключ " +
            "провайдера «stub» в разделе «Модель»"
        onNodeWithText(expected).performScrollTo().assertIsDisplayed()
    }

    /** Текст, который реально введён в поле: так проверяется очистка поля после сохранения. */
    private fun SemanticsNodeInteraction.editableText(): String =
        fetchSemanticsNode().config[SemanticsProperties.EditableText].text

    /** Конфигурация с одним провайдером и двумя моделями: одна из них — по умолчанию. */
    private fun config(): AgentConfig = AgentConfig(
        defaultModel = "stub/model",
        providers = listOf(
            ProviderProfile(
                id = "stub",
                type = ProviderType.OPENAI_COMPATIBLE,
                baseUrl = "http://127.0.0.1:9/v1",
                apiKeyEnv = "STUB_KEY",
            ),
        ),
        models = listOf(
            ModelProfile(
                alias = "stub/model",
                provider = "stub",
                model = "stub-model",
                displayName = "Заглушка",
                contextWindow = 8_192,
                maxOutputTokens = 512,
                toolUse = true,
                pricePerMillionInMicros = 1_000_000,
                pricePerMillionOutMicros = 2_000_000,
            ),
            ModelProfile(
                alias = "stub/second",
                provider = "stub",
                model = "stub-model-2",
                displayName = "Заглушка 2",
                contextWindow = 4_096,
                maxOutputTokens = 256,
                toolUse = false,
            ),
        ),
    )

    /** Заготовка локального провайдера: у неё нет имени переменной с ключом. */
    private fun catalogEntry(): ProviderCatalogEntry = ProviderCatalogEntry(
        provider = ProviderProfile(
            id = "ollama",
            type = ProviderType.OPENAI_COMPATIBLE,
            baseUrl = "http://127.0.0.1:11434/v1",
            apiKeyEnv = null,
        ),
        models = listOf(
            ModelProfile(
                alias = "ollama/llama",
                provider = "ollama",
                model = "llama3.1",
                contextWindow = 131_072,
                maxOutputTokens = 8_192,
                toolUse = true,
                pricePerMillionInMicros = 0,
                pricePerMillionOutMicros = 0,
            ),
        ),
    )
}
