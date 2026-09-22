package aide.build

import org.gradle.api.Project
import org.gradle.api.artifacts.VersionCatalogsExtension

/**
 * Версии Android для конвенций берутся из каталога корневой сборки.
 *
 * Каталог самого `build-logic` для этого не годится: он описывает зависимости
 * included-сборки. Значения `compileSdk`, `minSdk` и версия Java объявлены один
 * раз — в `gradle/libs.versions.toml`.
 */
object CatalogVersions {

    /** Возвращает версию из каталога `libs` как число; падает, если версии там нет. */
    fun int(project: Project, name: String): Int {
        val catalog = project.extensions.getByType(VersionCatalogsExtension::class.java).named("libs")
        val version = catalog.findVersion(name).orElseThrow {
            IllegalStateException("В gradle/libs.versions.toml нет версии '$name'")
        }
        return version.requiredVersion.toInt()
    }
}
