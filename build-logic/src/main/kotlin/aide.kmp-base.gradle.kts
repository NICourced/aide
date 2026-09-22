import aide.build.CatalogVersions
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    // Первой идёт Android-конвенция: она приносит AGP, без которого `androidTarget()` не настроить.
    // Конвенции лежат в этом же `build-logic`, поэтому вторая находится по идентификатору.
    id("aide.android-library")
    id("org.jetbrains.kotlin.multiplatform")
}

val jvmTarget = CatalogVersions.int(project, "jvmTarget")

/**
 * Общая часть KMP-модулей: тулчейн, Android-настройки (через `aide.android-library`)
 * и тестовый source set. Набор таргетов сюда не входит намеренно — его задают
 * конвенции-наследники, потому что у модулей он разный.
 *
 * Применять эту конвенцию напрямую нельзя: KMP-модуль без таргетов не собирается.
 * Она — только основа для `aide.kmp-library` и `aide.kmp-android-library`.
 */
extensions.configure<KotlinMultiplatformExtension> {
    jvmToolchain(jvmTarget)
    sourceSets {
        commonTest.dependencies {
            // В KMP-модулях тестовая зависимость объявляется так: алиас `libs.kotlin.test`
            // закреплён за JVM-модулями, чтобы не появилось двух идиом для одной зависимости.
            implementation(kotlin("test"))
        }
    }
}
