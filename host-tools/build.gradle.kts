plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api, а не implementation: ToolPermission и Permission из домена стоят
    // в публичных сигнатурах PermissionResolver, а его потребители (движок прогона,
    // обработчик протокола) видят эти типы без отдельной строки зависимости.
    api(project(":domain"))

    testImplementation(libs.kotlin.test)
}

tasks.test { useJUnitPlatform() }
