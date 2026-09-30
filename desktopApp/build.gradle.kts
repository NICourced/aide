@file:OptIn(org.jetbrains.compose.ExperimentalComposeLibrary::class)

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    implementation(project(":client-ui"))
    implementation(project(":platform-desktop"))
    // Точка входа импортирует EmbeddedHost и KtorHostConnection напрямую, поэтому модули
    // объявлены явно. Иначе они приходили бы только через `api` у platform-desktop и
    // client-ui: смена видимости в любом из них ломала бы сборку десктопа по чужой причине.
    implementation(project(":client-state"))
    implementation(project(":host-core"))
    implementation(compose.desktop.currentOs)
    // Без провайдера SLF4J логи хоста не выводятся вообще: slf4j-api подключается как
    // implementation, и в приложении оставался NOP-логгер — ни «Хост слушает», ни «режим
    // LOCAL» в выводе не появлялись (T-0.13 требует обратного). Выбор провайдера — дело
    // приложения, поэтому он здесь, а не в host-core.
    runtimeOnly(libs.slf4j.simple)
    // Сквозная проверка десктопа (T-0.15) — настоящий UI-тест на том же `App`, что и
    // у приложения, поэтому нужен API тестов Compose. `kotlin("test")` здесь не приходит
    // из конвенции: она добавляет его только KMP-модулям, а этот модуль — обычный JVM.
    testImplementation(libs.kotlin.test)
    testImplementation(compose.uiTest)
}

compose.desktop {
    application {
        mainClass = "dev.aide.desktop.MainKt"
        nativeDistributions {
            // Форматы упаковки. Собирается только тот, что соответствует текущей ОС:
            // jpackage не умеет кросс-сборку, поэтому AppImage и .deb получаются на Linux,
            // а .msi и .exe — на Windows (см. AGENTS.md, раздел «Окружение»).
            targetFormats(
                TargetFormat.AppImage,
                TargetFormat.Deb,
                TargetFormat.Msi,
                TargetFormat.Exe,
            )
            // Только латиница: jpackage на Windows падает на не-ASCII в описании и
            // издателе — проверено на CI (после добавления русского описания сборка
            // установщика перестала проходить, а ошибка ушла в файл, которого в логе нет).
            packageName = "AIStudio"
            packageVersion = "0.1.0"
            vendor = "AIStudio"
            // Модули JDK, которые jpackage не находит сам: он видит только то, что связано
            // в байткоде, а обращение к драйверу SQLite идёт через JDBC-справочник служб,
            // к TLS — через криптопровайдер, а к `sun.misc.Unsafe` — рефлексией. Без них
            // приложение собирается, но падает при первом обращении к базе или к провайдеру
            // модели, причём уже на машине пользователя.
            modules("java.instrument", "java.sql", "jdk.unsupported", "jdk.crypto.ec")
        }
    }
}
