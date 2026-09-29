plugins { `kotlin-dsl` }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
    // Чистая логика правила границ (`ModuleBoundaries`) тестируется без Gradle,
    // поэтому тесты гоняются здесь, в included-сборке: `./gradlew -p build-logic test`.
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
