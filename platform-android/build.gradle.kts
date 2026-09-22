plugins {
    // Только Android-таргет: jvm-вариант этого модуля не нужен ни одному потребителю,
    // на десктопе роль платформенного связывания играет `platform-desktop`.
    id("aide.kmp-android-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: AndroidClientRuntime возвращает AndroidClientDependencies с типами
            // HostConnection и SettingsStore, значит они нужны androidApp на компиляции.
            api(project(":client-state"))
            implementation(libs.kotlinx.coroutines.core)
            // Композиционный корень клиента собирается на Koin (DI-фреймворк стека).
            implementation(libs.koin.core)
        }
    }
}
