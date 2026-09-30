plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

// Хранилище метаданных хоста (T-0.16): SQLite через SQLDelight.
// packageName — пакет DB-класса; пакет классов запросов задаётся ещё и путём
// .sq-файла внутри srcDirs (dev/aide/host/store → dev.aide.host.store).
sqldelight {
    databases {
        create("HostDatabase") {
            packageName.set("dev.aide.host.store.db")
            srcDirs.setFrom("src/main/sqldelight")
            // Файл схемы текущей версии коммитится вместе с кодом как эталон.
            // Каталог лежит вне srcDirs: плагин пишет выход внутрь srcDirs, и тогда
            // выход schema-задачи попадает внутрь входов задачи генерации интерфейса —
            // Gradle 8 валит сборку. Встроенная проверка миграций (verifyMigrations)
            // по умолчанию выключена: миграцию на непустой базе версии 1 проверяет
            // MigrationTest.
            schemaOutputDirectory.set(file("src/main/sqldelight-schema"))
        }
    }
}

dependencies {
    implementation(project(":domain"))
    // api, а не implementation: WorkspaceBoundaryAdapter реализует публичный порт
    // host-tools и держит его тип в своей сигнатуре, поэтому потребитель адаптера
    // должен видеть dev.aide.tools.limits без отдельной строки зависимости.
    api(project(":host-tools"))
    // api, а не implementation: HostApp.open и EmbeddedHost.open держат в публичных
    // сигнатурах dev.aide.agent.LlmClient и dev.aide.agent.RunPlanner, поэтому
    // потребитель хоста (desktopApp, тесты) видит их без отдельной строки зависимости.
    api(project(":host-agent"))
    // api, а не implementation: ClientMessageHandler, ClientSession и ProtocolServer
    // держат в публичных сигнатурах типы протокола (ClientMessage, HostMessage,
    // ProtocolVersion, HostMode, RequestId). Домен приходит транзитивно.
    api(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    // Полный доменный объект хранится в колонке payload в CBOR: домен остаётся
    // единственным описанием объекта, а индексируемые поля вынесены в колонки.
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
    // SQLite: runtime нужен сгенерированному коду, sqlite-driver — драйвер JDBC.
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.jdbc)
    implementation(libs.slf4j.api)
    // Композиционный корень хоста (задача 13) собирает граф на Koin — DI-фреймворк стека.
    implementation(libs.koin.core)
    // Чтение состояния git (ветка, изменения, история) — задача 12.
    implementation(libs.jgit)

    testImplementation(libs.kotlin.test)
    // Клиент нужен интеграционным тестам транспорта. Правило границ проверяет
    // только main-наборы, поэтому ребро host-core → client-state в тестах легально.
    testImplementation(project(":client-state"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform() }
