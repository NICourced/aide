plugins {
    id("aide.kmp-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            // api, а не implementation: App(connection: HostConnection) экспортирует тип
            // из client-state наружу, и без api его пришлось бы дублировать у каждой
            // точки входа (так это и было до правки).
            api(project(":client-state"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            implementation(compose.components.resources)
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
