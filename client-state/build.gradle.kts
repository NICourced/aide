plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: HostClient и HostConnection принимают и возвращают
            // типы протокола (ClientMessage, HostMessage, WorkspaceId), а KtorHostConnection
            // и HostConnection держат в публичных сигнатурах HttpClient, CoroutineScope,
            // StateFlow и SharedFlow. Без api потребитель клиента — client-ui и platform-* —
            // не соберётся: implementation-зависимости не попадают на его compile classpath.
            api(project(":protocol"))
            api(libs.ktor.client.core)
            api(libs.kotlinx.coroutines.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.slf4j.api)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
