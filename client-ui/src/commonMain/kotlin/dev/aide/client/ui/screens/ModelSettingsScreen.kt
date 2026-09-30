package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ModelCheckOutcome
import dev.aide.client.state.ModelConfigError
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.strings.modelCheckResource
import dev.aide.client.ui.strings.modelErrorString
import dev.aide.client.ui.strings.providerTypeResource
import dev.aide.client.ui.strings.secretStatusString
import dev.aide.domain.AgentConfig
import dev.aide.domain.ModelCheckFailure
import dev.aide.domain.ModelProfile
import dev.aide.domain.ModelSecretStatus
import dev.aide.domain.ProviderCatalogEntry
import dev.aide.domain.ProviderProfile
import dev.aide.domain.ProviderType
import org.jetbrains.compose.resources.StringResource

/**
 * Раздел «Модель» в настройках (T-1.56).
 *
 * Раздел, а не отдельный экран: моделей немного, и уводить пользователя с экрана
 * настроек ради двух таблиц незачем. Показываются обе таблицы целиком — провайдеры и
 * модели, — потому что «любое поле заготовки правится вручную» означает именно это:
 * все поля видны и редактируемы, а не спрятаны за диалогом «изменить».
 *
 * Правка идёт в черновике: до нажатия «Сохранить» хост о ней не знает. Добавление
 * заготовки или своего провайдера — исключение: это законченное действие, и оно
 * сохраняется сразу, иначе пользователь добавил бы заготовку и не увидел эффекта.
 * Ключа здесь нет и быть не может: показывается и правится только имя переменной
 * окружения (NFR-8).
 *
 * @param state данные сессии и два действия; собраны в один тип, потому что экран
 *   настроек не должен знать, как устроен доступ к хосту.
 */
@Composable
fun ModelSettingsScreen(state: ModelSettingsState, modifier: Modifier = Modifier) {
    val config = state.config
    var draft by remember(config) { mutableStateOf(config) }
    Column(
        modifier = modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.settingsModelTitle), style = MaterialTheme.typography.titleMedium)
        Text(
            Strings.text(Strings.settingsModelKeyNote),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("model-key-note"),
        )
        ModelErrorLine(error = state.error)
        val current = draft
        if (current == null) {
            // Пока настроек нет, «загрузка» без конца — это молчание: если запрос уже
            // отказал, пользователь обязан увидеть причину, а не бесконечное ожидание.
            if (state.error == null) {
                Text(Strings.text(Strings.settingsModelLoading), modifier = Modifier.testTag("model-loading"))
            }
            return@Column
        }
        DefaultModelLine(current.defaultModel)
        ProvidersSection(
            providers = current.providers,
            secrets = state.secrets,
            callbacks = ProviderSectionCallbacks(
                onEdit = { index, provider -> draft = current.withProvider(index, provider) },
                // Ключ сохраняется сразу, а не вместе с черновиком: это отдельное действие
                // над отдельным хранилищем, и «сохранить ключ» не должно зависеть от того,
                // правил ли пользователь соседнее поле.
                onSetSecret = state.onSetSecret,
                onDeleteSecret = state.onDeleteSecret,
            ),
        )
        ModelsSection(
            models = current.models,
            defaultAlias = current.defaultModel,
            check = state.check,
            callbacks = ModelSectionCallbacks(
                onEdit = { index, model -> draft = current.withModel(index, model) },
                onMakeDefault = { alias -> draft = current.copy(defaultModel = alias) },
                onCheck = state.onCheck,
            ),
        )
        CatalogSection(
            catalog = state.catalog,
            onAdd = { entry ->
                val updated = current.withCatalog(entry)
                draft = updated
                state.onSave(updated)
            },
        )
        CustomProviderSection(
            onAdd = { provider ->
                val updated = current.withProvider(current.providers.size, provider)
                draft = updated
                state.onSave(updated)
            },
        )
        Button(onClick = { draft?.let(state.onSave) }, modifier = Modifier.testTag("model-save")) {
            Text(Strings.text(Strings.settingsModelSave))
        }
    }
}

/**
 * Строка отказа настроек моделей: и неудачная загрузка, и неудачное сохранение.
 *
 * Отказ сохранения виден именно здесь: без него пользователь считал бы, что настройка
 * применена, — черновик-то остался на экране.
 */
@Composable
private fun ModelErrorLine(error: ModelConfigError?) {
    if (error == null) return
    Text(
        modelErrorString(error),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("model-error"),
    )
}

/** Строка выбранной модели: видно, какая модель пойдёт в следующий прогон. */
@Composable
private fun DefaultModelLine(defaultModel: String?) {
    val chosen = defaultModel ?: Strings.text(Strings.settingsModelDefaultNone)
    Text(
        Strings.text(Strings.settingsModelDefault, chosen),
        modifier = Modifier.testTag("model-default"),
        style = MaterialTheme.typography.bodyMedium,
    )
}

/**
 * Действия над таблицей провайдеров: правка полей профиля и работа с ключом (T-1.58).
 *
 * Собраны в один тип по тому же доводу, что и [ModelSectionCallbacks]: каждой строке
 * таблицы нужны все три действия, и три лямбда-параметра в каждой подписи читались бы хуже.
 */
private class ProviderSectionCallbacks(
    val onEdit: (Int, ProviderProfile) -> Unit,
    val onSetSecret: (String, String) -> Unit,
    val onDeleteSecret: (String) -> Unit,
)

/** Таблица провайдеров: протокол, адрес, имя переменной окружения с ключом и сам ключ. */
@Composable
private fun ProvidersSection(
    providers: List<ProviderProfile>,
    secrets: Map<String, ModelSecretStatus>,
    callbacks: ProviderSectionCallbacks,
) {
    Text(Strings.text(Strings.settingsModelProviders), style = MaterialTheme.typography.titleSmall)
    if (providers.isEmpty()) {
        Text(Strings.text(Strings.settingsModelProvidersEmpty), style = MaterialTheme.typography.bodySmall)
    }
    providers.forEachIndexed { index, provider ->
        ProviderFields(
            provider = provider,
            secret = secrets[provider.id],
            onEdit = { callbacks.onEdit(index, it) },
            onSetSecret = callbacks.onSetSecret,
            onDeleteSecret = callbacks.onDeleteSecret,
        )
    }
}

/** Поля одного провайдера; всё правится вручную, включая протокол и адрес. */
@Composable
private fun ProviderFields(
    provider: ProviderProfile,
    secret: ModelSecretStatus?,
    onEdit: (ProviderProfile) -> Unit,
    onSetSecret: (String, String) -> Unit,
    onDeleteSecret: (String) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        LabeledField(
            label = Strings.settingsModelFieldId,
            value = provider.id,
            tag = "provider-id-${provider.id}",
            onValueChange = { onEdit(provider.copy(id = it)) },
        )
        LabeledField(
            label = Strings.settingsModelFieldBaseUrl,
            value = provider.baseUrl,
            tag = "provider-base-url-${provider.id}",
            onValueChange = { onEdit(provider.copy(baseUrl = it)) },
        )
        LabeledField(
            label = Strings.settingsModelFieldKeyEnv,
            value = provider.apiKeyEnv.orEmpty(),
            tag = "provider-key-env-${provider.id}",
            hint = Strings.settingsModelFieldKeyEnvHint,
            onValueChange = { entered -> onEdit(provider.copy(apiKeyEnv = entered.takeIf { it.isNotBlank() })) },
        )
        SecretFields(
            provider = provider,
            secret = secret,
            onSetSecret = onSetSecret,
            onDeleteSecret = onDeleteSecret,
        )
        ProtocolChoice(selected = provider.type, tagPrefix = "provider-type-${provider.id}") { type ->
            onEdit(provider.copy(type = type))
        }
    }
}

/**
 * Ключ провайдера: строка состояния, поле ввода и две кнопки (T-1.58).
 *
 * После сохранения поле очищается: хост значения не возвращает, и показать в нём прежний
 * текст значило бы утверждать, что ключ известен приложению. Пока состояние неизвестно
 * (карты ещё нет), показывается «ключ не задан» — это честнее пустой строки.
 */
@Composable
private fun SecretFields(
    provider: ProviderProfile,
    secret: ModelSecretStatus?,
    onSetSecret: (String, String) -> Unit,
    onDeleteSecret: (String) -> Unit,
) {
    val status = secret ?: ModelSecretStatus.Absent
    var value by remember(provider.id) { mutableStateOf("") }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            secretStatusString(provider, status),
            style = MaterialTheme.typography.bodySmall,
            color = if (status is ModelSecretStatus.StoreUnavailable) {
                MaterialTheme.colorScheme.error
            } else {
                MaterialTheme.colorScheme.onSurfaceVariant
            },
            modifier = Modifier.testTag("provider-secret-status-${provider.id}"),
        )
        SecretField(
            value = value,
            tag = "provider-secret-input-${provider.id}",
            onValueChange = { value = it },
        )
        Button(
            onClick = {
                onSetSecret(provider.id, value)
                value = ""
            },
            enabled = value.isNotBlank(),
            modifier = Modifier.testTag("provider-secret-save-${provider.id}"),
        ) {
            Text(Strings.text(Strings.settingsModelSecretSave))
        }
        Button(
            onClick = { onDeleteSecret(provider.id) },
            modifier = Modifier.testTag("provider-secret-delete-${provider.id}"),
        ) {
            Text(Strings.text(Strings.settingsModelSecretDelete))
        }
    }
}

/** Выбор протокола: перечисление, а не вендор; список расширяется без правок кода (О-2). */
@Composable
private fun ProtocolChoice(selected: ProviderType, tagPrefix: String, onSelect: (ProviderType) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Strings.text(Strings.settingsModelFieldType), style = MaterialTheme.typography.bodySmall)
        ProviderType.entries.forEach { type ->
            Button(
                onClick = { onSelect(type) },
                enabled = type != selected,
                modifier = Modifier.fillMaxWidth().testTag("$tagPrefix-${type.name}"),
            ) {
                Text(Strings.text(providerTypeResource(type)))
            }
        }
    }
}

/**
 * Действия над таблицей моделей: правка поля, выбор основной и проверка доступа.
 *
 * Собраны в один тип, а не разнесены по параметрам: все три нужны каждой строке таблицы,
 * и пять однотипных лямбда-параметров читались бы хуже, чем один именованный набор.
 */
private class ModelSectionCallbacks(
    val onEdit: (Int, ModelProfile) -> Unit,
    val onMakeDefault: (String) -> Unit,
    val onCheck: (String) -> Unit,
)

/** Действия над одной моделью: те же три, но уже привязанные к её позиции и алиасу. */
private class ModelCallbacks(
    val onEdit: (ModelProfile) -> Unit,
    val onMakeDefault: () -> Unit,
    val onCheck: () -> Unit,
)

/** Таблица моделей: контекст, предел вывода, поддержка инструментов и ставки. */
@Composable
private fun ModelsSection(
    models: List<ModelProfile>,
    defaultAlias: String?,
    check: ModelCheckOutcome?,
    callbacks: ModelSectionCallbacks,
) {
    Text(Strings.text(Strings.settingsModelModels), style = MaterialTheme.typography.titleSmall)
    if (models.isEmpty()) {
        Text(Strings.text(Strings.settingsModelModelsEmpty), style = MaterialTheme.typography.bodySmall)
    }
    models.forEachIndexed { index, model ->
        ModelFields(
            model = model,
            isDefault = model.alias == defaultAlias,
            check = check?.takeIf { it.alias == model.alias },
            callbacks = ModelCallbacks(
                onEdit = { edited -> callbacks.onEdit(index, edited) },
                onMakeDefault = { callbacks.onMakeDefault(model.alias) },
                onCheck = { callbacks.onCheck(model.alias) },
            ),
        )
    }
}

/** Поля одной модели: идентификаторы, размеры и ставки за миллион токенов. */
@Composable
private fun ModelFields(
    model: ModelProfile,
    isDefault: Boolean,
    check: ModelCheckOutcome?,
    callbacks: ModelCallbacks,
) {
    val onEdit = callbacks.onEdit
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
        Text(
            model.title,
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.testTag("model-title-${model.alias}"),
        )
        LabeledField(
            label = Strings.settingsModelFieldAlias,
            value = model.alias,
            tag = "model-alias-${model.alias}",
            onValueChange = { onEdit(model.copy(alias = it)) },
        )
        LabeledField(
            label = Strings.settingsModelFieldModel,
            value = model.model,
            tag = "model-id-${model.alias}",
            onValueChange = { onEdit(model.copy(model = it)) },
        )
        LabeledField(
            label = Strings.settingsModelFieldDisplayName,
            value = model.displayName,
            tag = "model-display-${model.alias}",
            onValueChange = { onEdit(model.copy(displayName = it)) },
        )
        ModelNumbers(model = model, onEdit = onEdit)
        ToggleField(
            label = Strings.settingsModelFieldToolUse,
            value = model.toolUse,
            tag = "model-tool-use-${model.alias}",
        ) { onEdit(model.copy(toolUse = it)) }
        ModelActionButtons(alias = model.alias, isDefault = isDefault, callbacks = callbacks)
        if (check != null) CheckResultLine(check)
    }
}

/** Числовые поля модели: контекст, предел вывода и обе ставки. */
@Composable
private fun ModelNumbers(model: ModelProfile, onEdit: (ModelProfile) -> Unit) {
    NumberField(
        label = Strings.settingsModelFieldContext,
        value = model.contextWindow.toLong(),
        tag = "model-context-${model.alias}",
    ) { entered -> entered?.let { onEdit(model.copy(contextWindow = it.toInt())) } }
    NumberField(
        label = Strings.settingsModelFieldMaxOutput,
        value = model.maxOutputTokens.toLong(),
        tag = "model-max-output-${model.alias}",
    ) { entered -> entered?.let { onEdit(model.copy(maxOutputTokens = it.toInt())) } }
    NumberField(
        label = Strings.settingsModelFieldPriceIn,
        value = model.pricePerMillionInMicros,
        tag = "model-price-in-${model.alias}",
    ) { onEdit(model.copy(pricePerMillionInMicros = it)) }
    NumberField(
        label = Strings.settingsModelFieldPriceOut,
        value = model.pricePerMillionOutMicros,
        tag = "model-price-out-${model.alias}",
    ) { onEdit(model.copy(pricePerMillionOutMicros = it)) }
}

/** Кнопки модели: выбрать основной и проверить доступ. */
@Composable
private fun ModelActionButtons(alias: String, isDefault: Boolean, callbacks: ModelCallbacks) {
    Button(
        onClick = callbacks.onMakeDefault,
        enabled = !isDefault,
        modifier = Modifier.testTag("model-make-default-$alias"),
    ) {
        Text(Strings.text(Strings.settingsModelMakeDefault))
    }
    Button(onClick = callbacks.onCheck, modifier = Modifier.testTag("model-check-$alias")) {
        Text(Strings.text(Strings.settingsModelCheck))
    }
}

/** Заготовки: добавление одним действием; после добавления правится любое поле. */
@Composable
private fun CatalogSection(catalog: List<ProviderCatalogEntry>, onAdd: (ProviderCatalogEntry) -> Unit) {
    Text(Strings.text(Strings.settingsModelCatalog), style = MaterialTheme.typography.titleSmall)
    catalog.forEach { entry ->
        Button(
            onClick = { onAdd(entry) },
            modifier = Modifier.fillMaxWidth().testTag("catalog-add-${entry.provider.id}"),
        ) {
            Text(entry.provider.id)
        }
    }
}

/** Свой провайдер: произвольные адрес, протокол и имя переменной окружения с ключом. */
@Composable
private fun CustomProviderSection(onAdd: (ProviderProfile) -> Unit) {
    var id by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var apiKeyEnv by remember { mutableStateOf("") }
    var type by remember { mutableStateOf(ProviderType.OPENAI_COMPATIBLE) }

    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Strings.text(Strings.settingsModelCustom), style = MaterialTheme.typography.titleSmall)
        LabeledField(Strings.settingsModelFieldId, id, "custom-provider-id") { id = it }
        LabeledField(Strings.settingsModelFieldBaseUrl, baseUrl, "custom-provider-base-url") { baseUrl = it }
        LabeledField(Strings.settingsModelFieldKeyEnv, apiKeyEnv, "custom-provider-key-env") { apiKeyEnv = it }
        ProtocolChoice(selected = type, tagPrefix = "custom-provider-type") { type = it }
        Button(
            onClick = { onAdd(ProviderProfile(id, type, baseUrl, apiKeyEnv.takeIf { it.isNotBlank() })) },
            enabled = id.isNotBlank() && baseUrl.isNotBlank(),
            modifier = Modifier.testTag("custom-provider-add"),
        ) {
            Text(Strings.text(Strings.settingsModelAdd))
        }
    }
}

/**
 * Строка результата проверки: имя отсутствующей переменной показывается как есть.
 *
 * Отказ «ключ не задан» называет **оба** имени — переменную окружения и провайдера:
 * ключ можно задать и здесь, в разделе «Модель», и это видно из подсказки (T-1.58).
 */
@Composable
private fun CheckResultLine(check: ModelCheckOutcome) {
    val failure = check.failure
    val text = when (failure) {
        is ModelCheckFailure.MissingKey -> {
            val provider = failure.provider
            if (provider != null) {
                Strings.text(Strings.settingsModelCheckMissingKeyOrStore, failure.variable, provider)
            } else {
                Strings.text(modelCheckResource(failure), failure.variable)
            }
        }

        else -> Strings.text(modelCheckResource(failure))
    }
    Text(
        text,
        color = if (check.ok) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.bodySmall,
        modifier = Modifier.testTag("model-check-result"),
    )
}

/**
 * Числовое поле.
 *
 * Показано текстом, а не числом: пользователь правит значение посимвольно, и промежуточное
 * «1» в «10000» не должно превращаться в сохранённое `10`. Пустая строка и мусор дают
 * `null` — «цена неизвестна», а не ноль (FR-COST-5).
 */
@Composable
private fun NumberField(label: StringResource, value: Long?, tag: String, onChange: (Long?) -> Unit) {
    var text by remember { mutableStateOf(value?.toString().orEmpty()) }
    LabeledField(label, text, tag) { entered ->
        text = entered
        onChange(entered.toLongOrNull())
    }
}

/** Переключатель «да/нет» кнопкой: он показывает значение, а нажатие его меняет. */
@Composable
private fun ToggleField(label: StringResource, value: Boolean, tag: String, onChange: (Boolean) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(Strings.text(label), style = MaterialTheme.typography.bodySmall)
        Button(onClick = { onChange(!value) }, modifier = Modifier.testTag(tag)) {
            Text(Strings.text(if (value) Strings.settingsModelYes else Strings.settingsModelNo))
        }
    }
}

/**
 * Поле с подписью: единственный вид ввода в разделе, поэтому вынесен отдельно.
 */
@Composable
private fun LabeledField(
    label: StringResource,
    value: String,
    tag: String,
    hint: StringResource? = null,
    onValueChange: (String) -> Unit,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(Strings.text(label)) },
        placeholder = hint?.let { resource -> { Text(Strings.text(resource)) } },
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/**
 * Поле секрета: значение скрыто точками.
 *
 * Отдельный composable, а не параметр «скрывать ли ввод» у [LabeledField]: подпись у него
 * одна и та же, а скрытие — не оформление, а свойство поля, которое не должно включаться
 * где-то ещё по невнимательности.
 */
@Composable
private fun SecretField(value: String, tag: String, onValueChange: (String) -> Unit) {
    OutlinedTextField(
        value = value,
        onValueChange = onValueChange,
        label = { Text(Strings.text(Strings.settingsModelSecretLabel)) },
        visualTransformation = PasswordVisualTransformation(),
        singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag(tag),
    )
}

/** Добавляет заготовку: провайдер и его модели, которых ещё нет в таблицах. */
private fun AgentConfig.withCatalog(entry: ProviderCatalogEntry): AgentConfig = copy(
    providers = providers.replacedAt(entry.provider.id, entry.provider) { it.id },
    models = entry.models.fold(models) { acc, model -> acc.replacedAt(model.alias, model) { it.alias } },
)

/** Заменяет провайдера по позиции или добавляет, если позиции ещё нет. */
private fun AgentConfig.withProvider(index: Int, provider: ProviderProfile): AgentConfig =
    copy(providers = providers.replacedAt(index, provider))

/** Заменяет модель по позиции или добавляет, если позиции ещё нет. */
private fun AgentConfig.withModel(index: Int, model: ModelProfile): AgentConfig =
    copy(models = models.replacedAt(index, model))

/** Замена по идентификатору: повторное добавление той же заготовки не удваивает таблицу. */
private fun <T> List<T>.replacedAt(id: String, value: T, key: (T) -> String): List<T> =
    if (any { key(it) == id }) map { if (key(it) == id) value else it } else this + value

/** Замена по позиции: позиция за концом списка означает добавление. */
private fun <T> List<T>.replacedAt(index: Int, value: T): List<T> =
    if (index in indices) {
        mapIndexed { position, existing -> if (position == index) value else existing }
    } else {
        this + value
    }
