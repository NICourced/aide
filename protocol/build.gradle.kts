plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: сообщения протокола несут доменные типы
            // в публичных сигнатурах (payload'ы, HostEvent), поэтому клиентским модулям
            // домен нужен на компиляции без отдельной строки у каждого.
            api(project(":domain"))
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
        }
    }
}
