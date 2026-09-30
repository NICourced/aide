pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

plugins {
    // Резолвер тулчейнов: конвенции требуют JDK 17 (`jvmToolchain`), и там, где его нет
    // в системе — на CI-образе или на машине разработчика, — Gradle должен уметь его скачать,
    // а не падать с "No matching toolchains found for requested specification".
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Репозитории объявлены ровно один раз — здесь. Модуль не может добавить свой:
    // иначе зависимость может приехать из неизвестного источника, и ревью этого не увидит.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "aide"

include(
    ":domain",
    ":protocol",
    ":host-core",
    ":host-tools",
    ":client-state",
    ":client-ui",
    ":platform-android",
    ":platform-desktop",
    ":androidApp",
    ":desktopApp",
)
