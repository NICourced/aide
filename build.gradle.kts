plugins {
    // Правило границ модулей (T-0.4): конвенция регистрирует задачу `verifyModuleBoundaries`
    // в защищаемых модулях и вешает её на `check`. Живёт в `build-logic`, потому что
    // корневой скрипт не видит классы included-сборки и не может импортировать задачу напрямую.
    id("aide.module-boundaries")
    alias(libs.plugins.kotlinMultiplatform) apply false
    alias(libs.plugins.kotlinJvm) apply false
    // Kotlin для AGP-модуля приложения: применяется в `androidApp` в задаче 2.
    alias(libs.plugins.kotlinAndroid) apply false
    alias(libs.plugins.kotlinSerialization) apply false
    alias(libs.plugins.androidApplication) apply false
    alias(libs.plugins.androidLibrary) apply false
    alias(libs.plugins.composeMultiplatform) apply false
    alias(libs.plugins.composeCompiler) apply false
    alias(libs.plugins.sqldelight) apply false
    alias(libs.plugins.detekt) apply false
}

// Линтер применяется ко всем модулям из корня, а не в каждом build.gradle.kts: правило
// и его настройки должны быть одни на весь репозиторий, иначе новый модуль останется
// без линта просто потому, что автор о нём не вспомнил.
subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        // Конфиг ниже дополняет набор правил detekt по умолчанию, а не заменяет его:
        // в файле перечислены только отклонения от умолчаний, поэтому правило,
        // появившееся в новой версии линтера, начинает работать без правки конфига.
        buildUponDefaultConfig = true
    }

    // Источники задачи `detekt` задаются здесь явно. У JVM- и Android-модулей detekt
    // ищет код в `src/main/kotlin` и без настройки находит его, а у KMP-модулей код
    // лежит в `src/commonMain/kotlin`, куда поиск по умолчанию не заходит: задача
    // молча уходит в NO-SOURCE, и линт на общем коде не выполняется вообще.
    // Обход по всему `src` накрывает и KMP-набор таргетов, и обычные модули.
    tasks.named("detekt", io.gitlab.arturbosch.detekt.Detekt::class.java).configure {
        setSource(fileTree("src") { include("**/*.kt", "**/*.kts") })
    }
}
