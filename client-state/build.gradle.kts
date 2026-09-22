plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: HostClient и HostConnection принимают и возвращают
            // типы протокола (ClientMessage, WorkspaceId, HostMessage), поэтому потребителю
            // клиента — client-ui и platform-* — протокол нужен видимым.
            api(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
