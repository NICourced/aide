plugins {
    id("aide.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: App(connection: HostConnection) экспортирует тип
            // из client-state наружу, и без api его пришлось бы дублировать у каждой
            // точки входа (так это и было до правки).
            api(project(":client-state"))
        }
    }
}
