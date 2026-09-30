plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api, а не implementation: доменные типы (AgentRun, Task, RunState, PlanStep, Cost)
    // стоят в публичных сигнатурах движка и его портов, поэтому потребитель — host-core —
    // видит их без отдельной строки зависимости.
    api(project(":domain"))

    implementation(libs.kotlinx.coroutines.core)
    // План приходит от модели JSON-ом, и разбирается он здесь: формат плана — часть
    // контракта движка, а не хоста. Плагин сериализации не нужен: `@Serializable`
    // в модуле нет, план разбирается рантаймным `Json.parseToJsonElement`.
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
