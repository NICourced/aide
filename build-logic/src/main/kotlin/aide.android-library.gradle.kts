import aide.build.CatalogVersions
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

/**
 * namespace выводится из имени каталога модуля, поэтому имя — часть контракта:
 * `client-ui` → `dev.aide.client.ui`. Проверка падает на этапе конфигурации
 * с понятным сообщением, а не превращается в namespace, разошедшийся с пакетом исходников.
 */
val moduleName = project.name
require(moduleName.matches(Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*"))) {
    "Имя модуля '$moduleName' не подходит для namespace: нужен kebab-case из строчных " +
        "латинских букв и цифр, например `client-ui`. Переименовать каталог модуля " +
        "или задать namespace явно."
}

val compileSdkVersion = CatalogVersions.int(project, "androidCompileSdk")
val minSdkVersion = CatalogVersions.int(project, "androidMinSdk")
val targetJavaVersion = JavaVersion.toVersion(CatalogVersions.int(project, "jvmTarget"))

extensions.configure<LibraryExtension> {
    // namespace уникален и выводится из имени модуля: `client-ui` → `dev.aide.client.ui`,
    // `platform-android` → `dev.aide.platform.android`.
    namespace = "dev.aide.${moduleName.replace('-', '.')}"
    compileSdk = compileSdkVersion
    defaultConfig { minSdk = minSdkVersion }
    compileOptions {
        sourceCompatibility = targetJavaVersion
        targetCompatibility = targetJavaVersion
    }
}
