plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api, а не implementation: доменные типы (AgentRun, Task, RunState, PlanStep, Cost,
    // ProviderProfile, AgentConfig) стоят в публичных сигнатурах движка, его портов и
    // реестра провайдеров, поэтому потребитель — host-core — видит их без отдельной
    // строки зависимости.
    api(project(":domain"))
    // api, а не implementation: HttpClient стоит в публичной сигнатуре адаптера провайдера
    // (OpenAiCompatibleClient) и фабрики клиентов (ProviderClients), поэтому host-core
    // собирает граф, не добавляя Ktor к себе отдельной строкой. Движка HTTP-клиента здесь
    // нет намеренно: CIO подключает host-core — хост-процесс, а не рантайм агента.
    api(libs.ktor.client.core)

    implementation(libs.kotlinx.coroutines.core)
    // План приходит от модели JSON-ом, разбирается он здесь; тело запроса к провайдеру
    // и разбор ответа — тоже JSON. Плагин сериализации не нужен: `@Serializable`
    // в модуле нет, всё разбирается рантаймным `Json.parseToJsonElement`.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
    // MockEngine: адаптеры проверяются на записанных запросах и ответах, сети в
    // автоматических тестах нет (О-11).
    testImplementation(libs.ktor.client.mock)
}

tasks.test { useJUnitPlatform() }
