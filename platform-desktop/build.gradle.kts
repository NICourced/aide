plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api: DesktopRuntime отдаёт наружу настройки, соединение и область корутин,
    // а поднимает EmbeddedHost — типы обоих модулей нужны точке входа desktopApp.
    api(project(":client-state"))
    api(project(":host-core"))
    implementation(libs.kotlinx.coroutines.core)
    // Композиционный корень десктопа собирается на Koin (DI-фреймворк стека).
    implementation(libs.koin.core)
}
