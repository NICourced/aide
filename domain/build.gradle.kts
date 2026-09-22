plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: kotlinx.datetime.Instant и @Serializable входят
            // в публичные сигнатуры моделей домена (Task.createdAt, AgentRun.startedAt,
            // ReviewDecision.decidedAt), поэтому без api потребитель получит
            // "Cannot access class 'kotlinx.datetime.Instant'".
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.datetime)
        }
    }
}
