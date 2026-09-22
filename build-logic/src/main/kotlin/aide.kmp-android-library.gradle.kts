import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("aide.kmp-base")
}

/**
 * KMP-библиотека только с Android-таргетом.
 *
 * `platform-android` собирается в androidApp, и jvm-вариант ему не нужен: на десктопе
 * работает `platform-desktop`. Лишний таргет — это лишняя компиляция, лишний артефакт
 * и место, где может разойтись поведение платформ.
 */
extensions.configure<KotlinMultiplatformExtension> {
    androidTarget()
}
