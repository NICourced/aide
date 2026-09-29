plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "dev.aide.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.aide.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-stage0"
    }
    buildFeatures { compose = true }
    compileOptions {
        val javaVersion = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}

dependencies {
    implementation(project(":client-ui"))
    implementation(project(":platform-android"))
    implementation(libs.androidx.activity.compose)
    // Область корутин точка входа создаёт сама, значит типы корутин должны быть
    // видны ей напрямую. Зависимости от `:client-state` нет: `platform-android`
    // объявляет `api(project(":client-state"))`, и типы настроек и соединения
    // приходят из `AndroidClientDependencies` транзитивно.
    implementation(libs.kotlinx.coroutines.core)
    // Как и на десктопе, провайдер SLF4J — дело приложения: без него slf4j-api
    // молча уходит в NOP, и в logcat нет ни попыток подключения, ни их причин —
    // Android-клиент становится неотлаживаемым. Только debug: в release-сборке
    // лишнего логгера быть не должно.
    debugImplementation(libs.slf4j.simple)
}
