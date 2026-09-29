@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.gradle.api.tasks.testing.Test

plugins {
    id("aide.kmp-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: тип client-state стоит в публичной подписи модуля — в параметре App, —
            // поэтому без api потребитель не соберётся: implementation-зависимости
            // не попадают на его compile classpath.
            api(project(":client-state"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // api: поля публичного объекта Strings имеют тип StringResource, поэтому
            // заголовок окна в desktopApp видит этот тип только через api — иначе
            // Cannot access class 'StringResource'.
            api(compose.components.resources)
        }
        commonTest.dependencies {
            // Тест SharedUiHasNoPlatformBranchingTest читает исходники commonMain
            // файловой системой, для этого и нужен okio.
            implementation(libs.okio)
        }
        jvmTest.dependencies {
            // ScreenStatesTest — настоящий UI-тест (runComposeUiTest), а не проверка
            // состояний вручную: compose.uiTest даёт сам API теста.
            implementation(compose.uiTest)
            // Проверено снятием: без рантайма Skiko текущей ОС тесты падают на
            // `NoClassDefFoundError: Could not initialize class org.jetbrains.skia.Surface`
            // внутри SkikoComposeUiTest — то есть compose.uiTest сам нативную библиотеку
            // не приносит, её даёт только compose.desktop.currentOs.
            implementation(compose.desktop.currentOs)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}

// Строки интерфейса объявляются ресурсами, а не литералами (NFR-13). Класс доступа
// генерируется в собственный пакет: имя `dev.aide.client.ui.resources.Res` не совпадает
// ни с чем в проекте, и импорт в Strings.kt читается однозначно.
compose.resources {
    publicResClass = false
    packageOfResClass = "dev.aide.client.ui.resources"
    generateResClass = always
}

// NoLocalityBranchingTest и NoLiteralUiStringsTest читают исходники файловой системой
// (T-0.13, T-0.14). Пути передаются системными свойствами как абсолютные: при запуске
// из IDE и из корня сборки рабочий каталог разный. Тестовый набор `jvmTest` наследует
// `kotlin("test")` из `commonTest`.
tasks.named<Test>("jvmTest") {
    systemProperty("clientUiSourcesDir", layout.projectDirectory.dir("src").asFile.path)
    // EntryPointStringsTest живёт здесь, а сканирует заголовок окна desktopApp и манифест
    // androidApp — до соседних модулей путь к `src` этого модуля не достаёт.
    systemProperty("repoRootDir", rootProject.layout.projectDirectory.asFile.path)
}
