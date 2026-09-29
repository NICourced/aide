import org.gradle.api.tasks.testing.Test

plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    // Документация домена собирается в CI (T-0.5): «у каждого публичного поля есть
    // документирующий комментарий, проверяется сборкой документации».
    alias(libs.plugins.dokka)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: kotlinx.datetime.Instant и @Serializable входят
            // в публичные сигнатуры моделей домена (Task.createdAt, AgentRun.startedAt,
            // ReviewDecision.decidedAt), поэтому без api потребитель получит
            // "Cannot access class 'kotlinx.datetime.Instant'".
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            // Round-trip тесты моделей (T-0.5) гоняют тот же бинарный формат, что поедет
            // по сети в задаче 8, поэтому проверяется именно CBOR, а не JSON.
            implementation(libs.kotlinx.serialization.cbor)
        }
    }
}

// PublicFieldsAreDocumentedTest читает исходники моделей файловой системой. Путь передаётся
// системным свойством как абсолютный (layout, а не рабочий каталог задачи): при запуске из
// IDE и из корня сборки рабочий каталог разный, и относительный путь там уже ломался.
tasks.named<Test>("jvmTest") {
    systemProperty("domainSourcesDir", layout.projectDirectory.dir("src/commonMain/kotlin").asFile.path)
}
