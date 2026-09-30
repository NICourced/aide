plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api, а не implementation: ToolPermission и Permission из домена стоят
    // в публичных сигнатурах PermissionResolver, а его потребители (движок прогона,
    // обработчик протокола) видят эти типы без отдельной строки зависимости.
    api(project(":domain"))
    // api, а не implementation: JsonObject стоит в публичной сигнатуре контракта
    // инструмента (AgentTool.argumentsSchema) — схему аргументов читают и реестр,
    // и проверка аргументов у потребителя.
    api(libs.kotlinx.serialization.json)

    // Таймаут вызова (withTimeout) — единственное, что модулю нужно от корутин:
    // инструмент сам ничего не запускает и не порождает.
    implementation(libs.kotlinx.coroutines.core)
    // implementation: логирование нужно точке вызова (отказ инструмента обязан быть
    // виден в журнале хоста), а провайдер логирования приносит процесс хоста — модуль
    // инструментов не решает, чем логировать.
    implementation(libs.slf4j.api)

    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
