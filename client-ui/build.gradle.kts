plugins {
    id("aide.kmp-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            // api: типы client-state появляются в публичных подписях модуля — в параметрах
            // App, — поэтому точки входа должны видеть их без дублирования этой зависимости.
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
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
