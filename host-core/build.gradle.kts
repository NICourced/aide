plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    implementation(project(":domain"))
    // api, а не implementation: ClientMessageHandler, ClientSession и ProtocolServer
    // держат в публичных сигнатурах типы протокола (ClientMessage, HostMessage,
    // ProtocolVersion, HostMode, RequestId). Домен приходит транзитивно.
    api(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.api)
    // Композиционный корень хоста (задача 13) собирает граф на Koin — DI-фреймворк стека.
    implementation(libs.koin.core)
    // Чтение состояния git (ветка, изменения, история) — задача 12.
    implementation(libs.jgit)

    testImplementation(libs.kotlin.test)
    // Клиент нужен интеграционным тестам транспорта. Правило границ проверяет
    // только main-наборы, поэтому ребро host-core → client-state в тестах легально.
    testImplementation(project(":client-state"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform() }
