import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension

plugins {
    id("aide.kmp-base")
}

/**
 * KMP-библиотека для клиента: jvm-таргет для десктопа и androidTarget для Android.
 *
 * Таргеты перечислены здесь, а не в `aide.kmp-base`: в T-0.9 к iOS/macOS придут
 * не все модули, и правка общей конвенции добавила бы их сразу всем. Модуль,
 * которому нужен другой набор, применяет другую конвенцию.
 */
extensions.configure<KotlinMultiplatformExtension> {
    jvm()
    androidTarget()
}
