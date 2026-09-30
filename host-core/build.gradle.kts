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
    // Адаптер провайдера работает на HttpClient из host-agent, а движок HTTP-клиента
    // выбирает хост-процесс: CIO приносит именно host-core, чтобы рантайм агента не
    // зависел от конкретного транспорта.
    implementation(libs.ktor.client.cio)
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
    // DPAPI для защищённого хранилища ключей на Windows (T-1.58). Почему JNA, а не
    // самодельная криптография и не обёртка над PowerShell — в KDoc DpapiSecretCipher:
    // коротко, это вызов системного шифрования в процессе хоста, без передачи секрета
    // через командную строку или временный файл. Зависимость платформенная (JVM-only)
    // и нужна одному классу — выбор хранилища делает SecretStoreFactory по платформе.
    implementation(libs.jna.platform)

    testImplementation(libs.kotlin.test)
    // MockEngine: сборка HTTP-клиента провайдеров и адаптер проверяются на записанных
    // ответах, сети в автоматических тестах нет (О-11).
    testImplementation(libs.ktor.client.mock)
    // Клиент нужен интеграционным тестам транспорта. Правило границ проверяет
    // только main-наборы, поэтому ребро host-core → client-state в тестах легально.
    testImplementation(project(":client-state"))
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}

tasks.test {
    useJUnitPlatform()
    // Ключ модели тесты подкладывают переменной окружения процесса, а не файлом (T-1.56):
    // в файле конфигурации ключа нет ни в каком виде, и прочитать его можно только отсюда.
    // Значение задаётся здесь одно на модуль, а тест берёт его из окружения и доказывает,
    // что оно действительно ушло провайдеру и не попало ни в файл настроек, ни в журнал.
    environment("AIDE_TEST_MODEL_KEY", "aide-test-key-2f6a1c")
}
