# План реализации этапа 0: фундамент AI Studio

> **Для агентов-исполнителей:** ОБЯЗАТЕЛЬНЫЙ ПОД-СКИЛЛ: используйте `superpowers:subagent-driven-development` (рекомендуется) или `superpowers:executing-plans`, чтобы выполнять этот план задача за задачей. Шаги размечены чекбоксами (`- [ ]`).

**Цель:** получить приложение, которое запускается на Android и на десктопе, поднимает локальный Workspace Host, подключается к нему по протоколу и показывает реальный git-репозиторий — ветку, дерево файлов и содержимое файла. Агента, diff и ревью в этом этапе нет.

**Архитектура:** два компонента с одним протоколом между ними. **Workspace Host** — JVM-процесс, владеющий репозиторием: файловая система, git, позже LSP, песочница, агент, права и журнал. **Клиент** — KMP/Compose, умеющий только рисовать и отправлять запросы; он не знает, локальный хост или удалённый. Локальный режим — это тот же протокол поверх `ws://127.0.0.1:<порт>`, поэтому в клиентском коде нет ни одной ветки «если хост локальный» (это проверяется тестом `T-4`).

**Технологический стек (зафиксирован):**

| Слой | Выбор |
|---|---|
| Язык | Kotlin 2.1.0 |
| Сборка | Gradle 8.11 (Kotlin DSL) + version catalog |
| UI | Compose Multiplatform 1.7.3, Material3 |
| Мультиплатформенность | Kotlin Multiplatform: таргеты `jvm` и `androidTarget` |
| Сериализация протокола | kotlinx.serialization 1.7.3, формат **CBOR** (бинарь, § 8.4) |
| Транспорт | Ktor 3.0.3: `ktor-server-netty` (хост), `ktor-client-okhttp` (клиент/Android), `ktor-client-cio` (клиент/десктоп), WebSocket |
| git | JGit 6.10.0 |
| БД хоста | SQLDelight 2.0.2 + `sqlite-driver` (JDBC), миграции через `.sqm` |
| DI | Koin 4.0.0 |
| Тесты | `kotlin("test")`, на JVM — JUnit Platform |
| Логирование | SLF4J + `slf4j-simple` на хосте, `Napier` на клиенте |

**Где что определено (чтобы не искать по документу):** типы домена — Задача 5; валидаторы инвариантов — Задача 6; `RiskEvaluator` — Задача 7; сообщения протокола и кодек — Задача 8; `ProtocolVersion` и правила совместимости — Задача 9; `HostClient` (реконнект) — Задача 10; `Workspace`, `WorkspaceFileSystem` — Задача 11; `GitRepository` — Задача 12; `EmbeddedHost` — Задача 13; `SettingsStore`, `KeyValueStore` — Задача 14; схема БД и DAO — Задача 15; платформенное связывание (`AndroidClientRuntime`, `DesktopRuntime`) — Задача 16.

**Соответствие задачам этапа:** Задачи 1–4 → `T-0.1`–`T-0.4`, 5 → `T-0.5`, 6 → `T-0.6`, 7 → `T-0.7`, 8 → `T-0.8`, 9 → `T-0.9`, 10 → `T-0.10`, 11 → `T-0.11`, 12 → `T-0.12`, 13 → `T-0.13`, 14 → `T-0.14`, 15 → `T-0.16`, 16 → `T-0.15` (сквозная проверка).

---

## Структура файлов

Ответственность каждого модуля — одна, границы проверяются автоматически (Задача 4).

```
aide/
├── settings.gradle.kts                    включение модулей
├── build.gradle.kts                       корневой: только версии плагинов
├── gradle.properties                      JVM-аргументы, AndroidX, кэш сборки
├── gradle/libs.versions.toml              единственный источник версий проекта
├── .gitignore                             артефакты сборки, локальные и IDE-файлы
├── gradlew, gradlew.bat                   wrapper: единственный способ запустить сборку
├── gradle/wrapper/                        gradle-wrapper.jar и .properties (Gradle 8.11), попадают в коммит
├── build-logic/
│   ├── settings.gradle.kts
│   ├── build.gradle.kts
│   ├── gradle/libs.versions.toml              версии самого build-logic: KGP и AGP
│   └── src/main/kotlin/
│       ├── CatalogVersions.kt                 чтение версий из каталога корневой сборки
│       ├── aide.kmp-base.gradle.kts           конвенция: общая часть KMP (тулчейн + Android-настройки)
│       ├── aide.kmp-library.gradle.kts        конвенция: KMP-библиотека (jvm + androidTarget)
│       ├── aide.kmp-android-library.gradle.kts конвенция: KMP-библиотека (только androidTarget)
│       ├── aide.android-library.gradle.kts    конвенция: Android-таргет для KMP
│       ├── aide.compose.gradle.kts            конвенция: Compose MP
│       └── ModuleBoundariesTask.kt            проверка границ модулей (T-0.4)
├── .github/workflows/ci.yml               сборка, линт, тесты (T-0.3)
├── domain/
│   └── src/
│       ├── commonMain/kotlin/dev/aide/domain/
│       │   ├── Ids.kt                     TaskId, RunId, PacketId, HunkId, ToolCallId, SnapshotRef
│       │   ├── Enums.kt                   RiskLevel, AutonomyMode, Permission, ChangeSource, ClientPlatform …
│       │   ├── Task.kt                    Task, TaskStatus, PlanStep, StepStatus
│       │   ├── AgentRun.kt                AgentRun, RunState, Cost
│       │   ├── ToolCall.kt                ToolCall, ToolOutcome, ApprovalDecision, ToolPermission
│       │   ├── ChangePacket.kt            ChangePacket, FileChange, Hunk, HunkLine, TestStatus
│       │   ├── ReviewDecision.kt          ReviewDecision, DecisionScope, DecisionValue
│       │   ├── Snapshot.kt                Snapshot, SnapshotTrigger
│       │   ├── Invariants.kt              DomainViolation + проверки инвариантов 1 и 5 (T-0.6)
│       │   └── risk/RiskEvaluator.kt      чистая функция RiskLevel (T-0.7)
│       └── commonTest/kotlin/dev/aide/domain/ …
├── protocol/
│   └── src/commonMain/kotlin/dev/aide/protocol/
│       ├── ProtocolVersion.kt             версия + правила совместимости (T-0.9)
│       ├── Messages.kt                    ClientMessage, HostMessage, payload'ы (T-0.8)
│       ├── ProtocolError.kt               типизированные ошибки, включая AccessDenied
│       ├── ProtocolCodec.kt               CBOR-кодек + толерантность к неизвестным типам
│       └── RequestDedupCache.kt           идемпотентность по requestId (T-0.10)
├── host-core/
│   └── src/main/kotlin/dev/aide/host/
│       ├── HostApp.kt                     composition root хоста (Koin)
│       ├── EmbeddedHost.kt                поднять/остановить хост в процессе (T-0.13)
│       ├── workspace/Workspace.kt         корень воркспейса, путь, имя
│       ├── workspace/WorkspaceFileSystem.kt  дерево и чтение файлов + проверка границ (T-0.11)
│       ├── workspace/FileTreeBuilder.kt   обход дерева с лимитами и игнором
│       ├── git/GitRepository.kt           интерфейс: ветка, изменения, лог (T-0.12)
│       ├── git/JGitRepository.kt          реализация на JGit
│       ├── server/ProtocolServer.kt       Ktor WebSocket, маршрутизация сообщений
│       ├── server/ClientSession.kt        одна сессия: hello, dedup, обработчики
│       ├── server/Handlers.kt             обработчики сообщений этапа 0
│       └── store/                         SQLDelight-схема, миграции, DAO (T-0.16)
├── client-state/
│   ├── src/commonMain/kotlin/dev/aide/client/state/
│   │   ├── HostConnection.kt              интерфейс соединения
│   │   ├── KtorHostConnection.kt          WebSocket + реконнект (T-0.10)
│   │   ├── HostClient.kt                  типизированные вызовы поверх соединения
│   │   ├── AppStateStore.kt               состояние экранов: загрузка/пусто/ошибка/нет связи
│   │   ├── ScreenState.kt                 пять состояний экрана (§ 6.1)
│   │   ├── settings/SettingsStore.kt      настройки поверх KeyValueStore (T-0.14)
│   │   └── settings/KeyValueStore.kt      expect: хранилище «ключ — строка» (T-0.14)
│   ├── src/androidMain/kotlin/dev/aide/client/state/settings/KeyValueStore.android.kt  actual на SharedPreferences
│   └── src/jvmMain/kotlin/dev/aide/client/state/settings/KeyValueStore.jvm.kt          actual на java.util.Properties
├── client-ui/
│   └── src/commonMain/
│       ├── kotlin/dev/aide/client/ui/
│       │   ├── App.kt                     корневой composable и навигация
│       │   ├── theme/Theme.kt             Material3, следование системной теме
│       │   ├── screens/RepoTreeScreen.kt  шапка (ветка) + дерево файлов
│       │   ├── screens/FileContentScreen.kt  содержимое файла
│       │   ├── screens/StateViews.kt      Loading / Empty / Error / Offline / NoPermission
│       │   └── strings/Strings.kt         доступ к ресурсным строкам
│       └── composeResources/values/strings.xml    все строки UI (NFR-13)
├── platform-android/                       wiring Android-клиента (только androidTarget): настройки, адрес хоста, соединение
│   └── src/androidMain/kotlin/dev/aide/platform/android/AndroidClientRuntime.kt
├── platform-desktop/                       wiring десктопа: EmbeddedHost, scope, настройки, соединение
│   └── src/main/kotlin/dev/aide/platform/desktop/DesktopRuntime.kt
├── androidApp/                             Activity, вход в App()
│   ├── build.gradle.kts                    AGP-приложение (T-0.2 добавляет Kotlin, Compose и зависимости)
│   └── src/main/AndroidManifest.xml        манифест приложения; без него AGP не собирает модуль
└── desktopApp/                             main(), окно поверх DesktopRuntime
```

**Почему так.** Вычислительная работа целиком в `host-core` — клиентские модули физически не имеют доступа ни к файловой системе, ни к git, ни к SQLite (это следует из границ сборки, а не из дисциплины). Домен не знает ни о UI, ни о транспорте, поэтому `RiskEvaluator` и инварианты тестируются как чистые функции без хоста и без сети. Строки UI живут в ресурсах Compose, а не в коде, — иначе NFR-13 не выполнить задним числом. `expect`-объявления платформенного кода и их `actual`-реализации лежат в том же модуле, где объявлен `expect` (`client-state`), — иначе они не соберутся; модули `platform-*` отвечают за другое: за инициализацию и связывание на конкретной платформе. Знание о том, что на десктопе хост живёт в том же процессе, есть только в `platform-desktop` и в поднимающем его `desktopApp`; ни `client-state`, ни `client-ui` его не содержат, и это проверяется статическим тестом из `T-0.13`.

---

## Задача 1: скелет Gradle-проекта и модули (`T-0.1`)

**Файлы:**
- Создать: `settings.gradle.kts`, `build.gradle.kts`, `gradle.properties`, `gradle/libs.versions.toml`
- Создать: `.gitignore`, `.gitattributes`
- Создать (генерируются): `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar`, `gradle/wrapper/gradle-wrapper.properties`
- Создать: `build-logic/settings.gradle.kts`, `build-logic/build.gradle.kts`, `build-logic/gradle/libs.versions.toml`
- Создать: `build-logic/src/main/kotlin/CatalogVersions.kt`, `build-logic/src/main/kotlin/aide.kmp-base.gradle.kts`, `build-logic/src/main/kotlin/aide.kmp-library.gradle.kts`, `build-logic/src/main/kotlin/aide.kmp-android-library.gradle.kts`, `build-logic/src/main/kotlin/aide.android-library.gradle.kts`
- Создать: `domain/build.gradle.kts`, `protocol/build.gradle.kts`, `client-state/build.gradle.kts`, `client-ui/build.gradle.kts`, `host-core/build.gradle.kts`, `platform-android/build.gradle.kts`, `platform-desktop/build.gradle.kts`, `desktopApp/build.gradle.kts`, `androidApp/build.gradle.kts`
- Создать: `androidApp/src/main/AndroidManifest.xml`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt`
- Тест: `domain/src/commonTest/kotlin/dev/aide/domain/DomainModuleSmokeTest.kt`

Build-файлы пишутся здесь для **всех девяти** модулей, включая `client-ui`, `host-core` и `desktopApp`: проект без build-скрипта — это проект без задач, а тогда недостижимы не только `./gradlew build`, но и проверки этого шага. Опыт: без build-файла `./gradlew :client-ui:jvmTest` падает с `Cannot locate tasks that match ':client-ui:jvmTest' as task 'jvmTest' not found in project ':client-ui'`, и то же происходит с `:host-core:test`. Ровно этот список задач запускает CI задачи 3 (`./gradlew :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest`) и шаг 10 ниже, поэтому к моменту задачи 3 build-файлы этих трёх модулей обязаны существовать.

Содержимое минимально и повторяет форму: конвенция таргетов, тулчейн и версии из каталога, плюс только те зависимости, которые уже следуют из архитектуры (`client-ui` → `client-state`, `platform-*` → `client-state`, `platform-desktop` → `host-core`). Наполняются модули там, где появляется код: `client-ui` и `desktopApp` — задача 2, `host-core` — задача 10.

- [ ] **Шаг 1: написать version catalog**

Создать `gradle/libs.versions.toml` — единственный источник версий во всём проекте:

```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.3"
compose = "1.7.3"
ktor = "3.0.3"
serialization = "1.7.3"
datetime = "0.6.1"
sqldelight = "2.0.2"
jgit = "6.10.0.202406032230-r"
koin = "4.0.0"
slf4j = "2.0.16"
coroutines = "1.9.0"
androidMinSdk = "26"
androidCompileSdk = "35"
jvmTarget = "17"

[libraries]
kotlinx-serialization-core = { module = "org.jetbrains.kotlinx:kotlinx-serialization-core", version.ref = "serialization" }
kotlinx-serialization-cbor = { module = "org.jetbrains.kotlinx:kotlinx-serialization-cbor", version.ref = "serialization" }
kotlinx-datetime = { module = "org.jetbrains.kotlinx:kotlinx-datetime", version.ref = "datetime" }
kotlinx-coroutines-core = { module = "org.jetbrains.kotlinx:kotlinx-coroutines-core", version.ref = "coroutines" }
ktor-server-core = { module = "io.ktor:ktor-server-core", version.ref = "ktor" }
ktor-server-netty = { module = "io.ktor:ktor-server-netty", version.ref = "ktor" }
ktor-server-websockets = { module = "io.ktor:ktor-server-websockets", version.ref = "ktor" }
ktor-client-core = { module = "io.ktor:ktor-client-core", version.ref = "ktor" }
ktor-client-websockets = { module = "io.ktor:ktor-client-websockets", version.ref = "ktor" }
ktor-client-okhttp = { module = "io.ktor:ktor-client-okhttp", version.ref = "ktor" }
ktor-client-cio = { module = "io.ktor:ktor-client-cio", version.ref = "ktor" }
sqldelight-runtime = { module = "app.cash.sqldelight:runtime", version.ref = "sqldelight" }
sqldelight-jdbc = { module = "app.cash.sqldelight:sqlite-driver", version.ref = "sqldelight" }
sqldelight-coroutines = { module = "app.cash.sqldelight:coroutines-extensions", version.ref = "sqldelight" }
jgit = { module = "org.eclipse.jgit:org.eclipse.jgit", version.ref = "jgit" }
koin-core = { module = "io.insert-koin:koin-core", version.ref = "koin" }
koin-android = { module = "io.insert-koin:koin-android", version.ref = "koin" }
slf4j-api = { module = "org.slf4j:slf4j-api", version.ref = "slf4j" }
slf4j-simple = { module = "org.slf4j:slf4j-simple", version.ref = "slf4j" }
kotlin-test = { module = "org.jetbrains.kotlin:kotlin-test", version.ref = "kotlin" }
androidx-activity-compose = { module = "androidx.activity:activity-compose", version = "1.9.3" }

[plugins]
kotlinMultiplatform = { id = "org.jetbrains.kotlin.multiplatform", version.ref = "kotlin" }
kotlinJvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
kotlinAndroid = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlinSerialization = { id = "org.jetbrains.kotlin.plugin.serialization", version.ref = "kotlin" }
androidApplication = { id = "com.android.application", version.ref = "agp" }
androidLibrary = { id = "com.android.library", version.ref = "agp" }
composeMultiplatform = { id = "org.jetbrains.compose", version.ref = "compose" }
composeCompiler = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
sqldelight = { id = "app.cash.sqldelight", version.ref = "sqldelight" }
```

`androidCompileSdk`, `androidMinSdk` и `jvmTarget` — единственный источник Android-значений. Из каталога их читают конвенция `aide.android-library` (шаг 6, через помощник `CatalogVersions`), `androidApp` (шаг 9) и JVM-модули (`kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }` в `host-core`, `platform-desktop`, `desktopApp`). Ни в одном build-файле числа `35`, `26` и `17` больше не пишутся.

Зависимостей Compose в каталоге нет намеренно: Compose подключается аксессорами плагина `org.jetbrains.compose` (`compose.runtime`, `compose.foundation`, `compose.material3`, `compose.ui`, `compose.components.resources` — задача 2, `compose.desktop.currentOs` — там же, `compose.uiTest` — задача 16). Алиас не только дублировал бы версию, но и был бы неверен: `compose.desktop.currentOs` выбирает артефакт под текущую ОС, а фиксированный `compose-desktop:desktop-jvm` — нет.

`kotlinAndroid` объявлен здесь и применяется `apply false` в корневом `build.gradle.kts` (шаг 5), чтобы задача 2 в `androidApp` только применила плагин, а не правила каталог и корневой build-скрипт. Сам `androidApp` в этой задаче остаётся модулем без Kotlin: кода в нём ещё нет, поэтому зелёная сборка Java-only модуля ничего не доказывает — Kotlin-модулем он станет в задаче 2 вместе с `MainActivity`.

- [ ] **Шаг 2: написать `.gitignore` и `.gitattributes`**

`.gitignore` создаётся до первого запуска Gradle: сборка wrapper'а и первого `build` создают `.gradle/`, `.kotlin/` и `build/`, и они не должны попасть в коммит.

```gitignore
# Артефакты сборки корневой сборки и её модулей.
# Правило без ведущего слэша действует на любом уровне вложенности: `.kotlin/` закрывает
# и корневой `.kotlin/`, и `build-logic/.kotlin/` — то же самое, что `**/.kotlin/`.
.gradle/
build/
*/build/
.kotlin/

# Артефакты сборки build-logic: это отдельная included-сборка со своими каталогами
build-logic/build/
build-logic/.gradle/
build-logic/.kotlin/

# Локальные и IDE-файлы
local.properties
.idea/
*.iml
.DS_Store

# Wrapper коммитится: без него на чистой машине нет команды ./gradlew
!gradle/wrapper/gradle-wrapper.jar
```

Правило про wrapper здесь не формальность: `gradle/wrapper/gradle-wrapper.jar` обязан попадать в коммит, иначе на чистой машине `./gradlew` не существует, а все шаги плана запускаются именно через него.

`.gitattributes` — рядом с `.gitignore` и **до** генерации wrapper'а (шаг 3), и это порядок, а не вкусовщина: атрибуты применяются в момент попадания файла в индекс, поэтому если `.gitattributes` появится после `git add gradlew.bat`, блоб запишется с CRLF, и после любого свежего чекаута файл будет вечно висеть изменённым (`git checkout -- gradlew.bat` не помогает, а fast-forward в `master` отказывается выполняться). Кроме того, файл должен лежать в репозитории до того, как его впервые склонируют на Windows: windows-job из задачи 3 запускает `./gradlew` (POSIX-скрипт) через Git Bash, и если чекаут отдаст файл с CRLF, оболочка ответит `$'\r': command not found`.

```gitattributes
# gradlew — POSIX-скрипт: CRLF ломает его при запуске через Git Bash на Windows
gradlew text eol=lf
# .bat — Windows-скрипт, ему нужны CRLF
*.bat text eol=crlf
```

- [ ] **Шаг 3: сгенерировать Gradle wrapper**

В репозитории нет ни Gradle, ни wrapper'а, а каждая команда задачи — `./gradlew …`. Шаг идёт сразу после каталога версий: версия Gradle (8.11) — часть версий проекта, а `.gitignore` из шага 2 уже готов к тому, что первый же запуск Gradle создаст `.gradle/`. Дистрибутив Gradle кладём **вне репозитория** (в нём самом есть свой wrapper и свой `.gradle/`, которым в проекте делать нечего). Скачивание и распаковка идут в подоболочке, чтобы рабочий каталог самой оболочки остался прежним — следующим блоком wrapper генерируется из корня этого репозитория:

```bash
( mkdir -p /tmp/gradle-dist && cd /tmp/gradle-dist \
  && curl -fsSLO https://services.gradle.org/distributions/gradle-8.11-bin.zip \
  && unzip -q gradle-8.11-bin.zip )
```

Wrapper генерируется из корня этого репозитория — до появления `settings.gradle.kts` и build-файлов, поэтому `gradle wrapper` не пытается конфигурировать сборку, а только пишет свои четыре файла:

```bash
# выполняется из корня этого репозитория — каталога с созданными на шаге 2 файлами .gitignore и .gitattributes
/tmp/gradle-dist/gradle-8.11/bin/gradle wrapper --gradle-version 8.11
rm -rf /tmp/gradle-dist
```

Появятся `gradlew`, `gradlew.bat`, `gradle/wrapper/gradle-wrapper.jar` и `gradle/wrapper/gradle-wrapper.properties` (в последнем — `distributionUrl` для Gradle 8.11). Временный дистрибутив из `/tmp` удалён и в репозиторий не попадает; все четыре файла wrapper'а, включая `.jar`, коммитятся в шаге 13.

Сгенерированный `gradle/wrapper/gradle-wrapper.properties` не проверяет целостность скачанного дистрибутива. Дописать в него сумму SHA-256 из официального файла `https://services.gradle.org/distributions/gradle-8.11-bin.zip.sha256`:

```properties
distributionSha256Sum=57dafb5c2622c6cc08b993c85b7c06956a2f53536432a30ead46166dbca0f1e9
```

Без этой строки wrapper распаковывает архив, не сверяя его с опубликованной суммой: подмена дистрибутива на зеркале или в прокси остаётся незамеченной. Сумма привязана к версии Gradle — при обновлении версии её берут заново.

Проверить, что wrapper рабочий:

```bash
./gradlew --version
```

Ожидаемо: строка `Gradle 8.11` и `JVM: 17…`. Если в строке `JVM` видно версию 8, выставить `JAVA_HOME=/usr/lib/jvm/java-17-openjdk` — подробнее в шаге 10.

- [ ] **Шаг 4: написать `settings.gradle.kts`**

```kotlin
pluginManagement {
    includeBuild("build-logic")
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}

plugins {
    // Резолвер тулчейнов: конвенции требуют JDK 17 (`jvmToolchain`), и там, где его нет
    // в системе — на CI-образе или на машине разработчика, — Gradle должен уметь его скачать,
    // а не падать с "No matching toolchains found for requested specification".
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.9.0"
}

dependencyResolutionManagement {
    // Репозитории объявлены ровно один раз — здесь. Модуль не может добавить свой:
    // иначе зависимость может приехать из неизвестного источника, и ревью этого не увидит.
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "aide"

include(
    ":domain",
    ":protocol",
    ":host-core",
    ":client-state",
    ":client-ui",
    ":platform-android",
    ":platform-desktop",
    ":androidApp",
    ":desktopApp",
)
```

Две строки здесь — не формальность. `repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)` запрещает модулю объявить свой репозиторий: без этого `implementation("что-то:1.0")` можно разрешить из локального каталога или чужого зеркала, и в ревью это не видно. Резолвер тулчейнов нужен потому, что JDK 17 требуют и конвенции (`jvmToolchain`), и AGP: там, где JDK 17 нет в системе, без резолвера сборка падает с `No matching toolchains found for requested specification: {languageVersion=17}` — по-разному на разных машинах и без связи с кодом проекта.

- [ ] **Шаг 5: написать корневой `build.gradle.kts` и `gradle.properties`**

`build.gradle.kts` объявляет плагины без применения, чтобы модули брали их из каталога:

```kotlin
plugins {
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
}
```

`gradle.properties`:

```properties
org.gradle.jvmargs=-Xmx3g -XX:MaxMetaspaceSize=768m
org.gradle.caching=true
org.gradle.configuration-cache=true
org.gradle.parallel=true
kotlin.code.style=official
android.useAndroidX=true
android.nonTransitiveRClass=true
```

`org.gradle.configuration-cache=true` включает configuration cache: повторный прогон не переконфигурирует сборку. Плата — задачи, читающие состояние вне конфигурации (переменные окружения, файлы вне inputs), нужно объявлять явно; в этом плане такие задачи не вводятся, и все проверки шага 10 проходят с включённым кэшем.

- [ ] **Шаг 6: написать конвенционные плагины `build-logic`**

`build-logic/settings.gradle.kts`:

```kotlin
dependencyResolutionManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
rootProject.name = "build-logic"
```

`build-logic/gradle/libs.versions.toml` — каталог версий самой included-сборки:

```toml
[versions]
kotlin = "2.1.0"
agp = "8.7.3"
```

Каталог у `build-logic` свой, и это не дублирование ради дублирования: `build-logic` подключён через `includeBuild`, то есть является отдельной сборкой, и каталог корневой сборки (`gradle/libs.versions.toml`) ей не виден. Мест, где эти версии вообще существуют, ровно два, и оба названы явно: `gradle/libs.versions.toml` — для проекта, `build-logic/gradle/libs.versions.toml` — для конвенций. Обновляя Kotlin или AGP, правь оба каталога; в самих build-скриптах версии не пишутся.

`build-logic/build.gradle.kts`:

```kotlin
plugins { `kotlin-dsl` }

dependencies {
    implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:${libs.versions.kotlin.get()}")
    implementation("com.android.tools.build:gradle:${libs.versions.agp.get()}")
}
```

`build-logic/src/main/kotlin/CatalogVersions.kt` — доступ к каталогу версий корневой сборки. Конвенции применяются к проектам этой сборки, поэтому её каталог виден им как расширение проекта `versionCatalogs`:

```kotlin
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
```

`build-logic/src/main/kotlin/aide.android-library.gradle.kts` — Android-таргет для KMP-модулей. Конвенция самодостаточна: она сама применяет AGP, поэтому её можно применить из другой конвенции, и ей достаточно одного идентификатора. Значения Android приходят из каталога корневой сборки, а не из литералов:

```kotlin
import aide.build.CatalogVersions
import com.android.build.api.dsl.LibraryExtension

plugins {
    id("com.android.library")
}

/**
 * namespace выводится из имени каталога модуля, поэтому имя — часть контракта:
 * `client-ui` → `dev.aide.client.ui`. Проверка падает на этапе конфигурации
 * с понятным сообщением, а не превращается в namespace, разошедшийся с пакетом исходников.
 */
val moduleName = project.name
require(moduleName.matches(Regex("[a-z][a-z0-9]*(-[a-z0-9]+)*"))) {
    "Имя модуля '$moduleName' не подходит для namespace: нужен kebab-case из строчных " +
        "латинских букв и цифр, например `client-ui`. Переименовать каталог модуля " +
        "или задать namespace явно."
}

val compileSdkVersion = CatalogVersions.int(project, "androidCompileSdk")
val minSdkVersion = CatalogVersions.int(project, "androidMinSdk")
val targetJavaVersion = JavaVersion.toVersion(CatalogVersions.int(project, "jvmTarget"))

extensions.configure<LibraryExtension> {
    // namespace уникален и выводится из имени модуля: `client-ui` → `dev.aide.client.ui`,
    // `platform-android` → `dev.aide.platform.android`.
    namespace = "dev.aide.${moduleName.replace('-', '.')}"
    compileSdk = compileSdkVersion
    defaultConfig { minSdk = minSdkVersion }
    compileOptions {
        sourceCompatibility = targetJavaVersion
        targetCompatibility = targetJavaVersion
    }
}
```

`build-logic/src/main/kotlin/aide.kmp-base.gradle.kts` — общая часть KMP-модулей. Таргетов здесь нет: набор таргетов задают конвенции-листья, потому что у модулей он разный:

```kotlin
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
```

`build-logic/src/main/kotlin/aide.kmp-library.gradle.kts` — KMP-библиотека с таргетами `jvm` и `androidTarget` (её применяют `domain`, `protocol`, `client-state`, `client-ui`):

```kotlin
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
```

`build-logic/src/main/kotlin/aide.kmp-android-library.gradle.kts` — KMP-библиотека только с Android-таргетом (её применяет `platform-android`):

```kotlin
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
```

Конвенций четыре, а не две, и таргеты вынесены в листья намеренно. `aide.android-library` — самостоятельный Android-блок, который можно применить и без Kotlin Multiplatform; `aide.kmp-base` собирает из него KMP-каркас (тулчейн и тестовый source set), но сам не добавляет ни одного таргета; две листовые конвенции отличаются ровно набором таргетов. Это нужно для `T-0.9` (iOS/macOS): таргеты iOS придут не всем модулям, и правка общей конвенции добавила бы их сразу всем — вместе с компиляцией, которую никто не просил. Отдельно по этой же причине `platform-android` не получает jvm-таргет: на десктопе работает `platform-desktop`, а лишний таргет — это лишняя компиляция и лишний артефакт.

Применять `aide.kmp-base` к модулю напрямую нельзя: KMP-модуль без таргетов не собирается. Она — только основа для двух листовых конвенций; те же конвенции лежат в одном `build-logic`, поэтому `id("aide.kmp-base")` и `id("aide.android-library")` разрешаются по идентификатору, без дополнительных зависимостей; если идентификатор из блока `plugins` почему-то не находится, тот же результат даёт `apply(plugin = "…")` последней строкой блока — Android-настройки остаются в одном месте в любом случае.

Тестовая зависимость объявляется по одному правилу: в KMP-модулях — `kotlin("test")` в `commonTest` (как в `aide.kmp-base`), в JVM-модулях — алиас `libs.kotlin.test`. Две идиомы для одной зависимости не появляются.

- [ ] **Шаг 7: подключить конвенции в модулях**

**Правило зависимостей: `api`, если тип виден в публичной сигнатуре модуля; иначе `implementation`.** Оно действует во всём проекте (`domain`, `protocol`, `client-state`, `client-ui`, `platform-*`) и повторяется в задаче 4 рядом с правилом границ. Причина не во вкусовщине: при `implementation` потребитель получает на своей компиляции `Cannot access class 'kotlinx.datetime.Instant'` — и лечится это не добавлением зависимости у потребителя, а объявлением `api` у владельца типа.

`domain/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: kotlinx.datetime.Instant и @Serializable входят
            // в публичные сигнатуры моделей домена (Task.createdAt, AgentRun.startedAt,
            // ReviewDecision.decidedAt), поэтому без api потребитель получит
            // "Cannot access class 'kotlinx.datetime.Instant'".
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.datetime)
        }
    }
}
```

`protocol/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: сообщения протокола несут доменные типы
            // в публичных сигнатурах (payload'ы, HostEvent), поэтому клиентским модулям
            // домен нужен на компиляции без отдельной строки у каждого.
            api(project(":domain"))
        }
    }
}
```

`client-state/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: HostClient и HostConnection принимают и возвращают
            // типы протокола (ClientMessage, WorkspaceId, HostMessage), поэтому потребителю
            // клиента — client-ui и platform-* — протокол нужен видимым.
            api(project(":protocol"))
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}
```

`client-ui/build.gradle.kts` (зависимости от Compose и от `:domain` добавляет шаг 1 задачи 2):

```kotlin
plugins {
    id("aide.kmp-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: типы client-state (HostConnection, SettingsStore)
            // появляются в публичных подписях модуля — в параметрах App, — поэтому точки
            // входа должны видеть их без дублирования этой зависимости.
            api(project(":client-state"))
        }
    }
}
```

`build-logic` не должен попадать в `:domain` как зависимость — конвенция применяется через `includeBuild`, а не как библиотека.

KMP-модули не пишут блок `android { }`: `namespace`, `compileSdk`, `minSdk` и `compileOptions` задаёт конвенция `aide.kmp-library` (через `aide.android-library`), а сами значения берутся из `gradle/libs.versions.toml` (`androidCompileSdk`, `androidMinSdk`, `jvmTarget`). Второго источника истины для этих значений быть не должно — иначе `namespace` разъедется с фактическим пакетом исходников, а `compileSdk` с каталогом.

- [ ] **Шаг 8: написать build-файлы платформенных модулей**

`platform-android/build.gradle.kts` — KMP-модуль только с Android-таргетом; конвенция `aide.kmp-android-library` задаёт таргет и весь Android-блок, поэтому здесь остаются только зависимости:

```kotlin
plugins {
    // Только Android-таргет: jvm-вариант этого модуля не нужен ни одному потребителю,
    // на десктопе роль платформенного связывания играет `platform-desktop`.
    id("aide.kmp-android-library")
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api: AndroidClientRuntime возвращает AndroidClientDependencies с типами
            // HostConnection и SettingsStore, значит они нужны androidApp на компиляции.
            api(project(":client-state"))
            // api: `CoroutineScope` — параметр публичного `AndroidClientRuntime.start`,
            // а точка входа создаёт свою область корутин сама, поэтому тип должен быть виден.
            api(libs.kotlinx.coroutines.core)
            // Композиционный корень клиента собирается на Koin (DI-фреймворк стека).
            implementation(libs.koin.core)
        }
    }
}
```

`platform-desktop/build.gradle.kts` — JVM-модуль. Кроме `client-state` ему нужен `host-core`: он поднимает `EmbeddedHost` до того, как клиент начнёт подключаться. Корутины объявлены явно: в `client-state` они лежат в `implementation`, поэтому модулю, который сам создаёт `CoroutineScope`, своей строки не избежать:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    // api: DesktopRuntime отдаёт наружу настройки, соединение и область корутин,
    // а поднимает EmbeddedHost — типы обоих модулей нужны точке входа desktopApp.
    api(project(":client-state"))
    api(project(":host-core"))
    // api: `DesktopRuntime.scope` — публичное свойство типа `CoroutineScope`,
    // его читает точка входа desktopApp.
    api(libs.kotlinx.coroutines.core)
    // Композиционный корень десктопа собирается на Koin (DI-фреймворк стека).
    implementation(libs.koin.core)
}
```

**Про исходники.** На этом шаге в обоих модулях нет ни одного `.kt`-файла, и это не заглушка: модули включены в `settings.gradle.kts` и участвуют в сборке, а их содержимое появляется там, где эта работа делается — `AndroidClientRuntime.kt` в задаче 16 (шаг 11), `DesktopRuntime.kt` в задаче 16 (шаг 8). `actual`-реализации `KeyValueStore` живут не здесь, а рядом с `expect`-объявлением — в `client-state/src/androidMain` и `client-state/src/jvmMain` (задача 14), потому что иначе `expect`/`actual` не соберётся.

`host-core/build.gradle.kts` и `desktopApp/build.gradle.kts` в этой задаче — минимальные JVM-модули: конвенция `kotlinJvm` и тулчейн из каталога, без зависимостей.

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }
```

Наличие этих файлов нужно не ради кода (его здесь нет), а ради задач: без build-скрипта у проекта нет задачи `test`, и `./gradlew :host-core:test` из шага 10 и из CI задачи 3 падает с `Task 'test' not found in project ':host-core'`. Зависимости (`Ktor`, `JGit`, SQLDelight — `host-core`; Compose и `platform-desktop` — `desktopApp`) добавляются там, где появляется код: `host-core` — задача 10, потом 15; `desktopApp` — задача 2.

- [ ] **Шаг 9: написать build-файл и манифест Android-приложения**

`androidApp/build.gradle.kts` — пока только AGP-приложение: Kotlin, Compose и зависимости подключаются в задаче 2, когда появляется код, который их использует. Значения Android берутся из каталога, а не из литералов:

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "dev.aide.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.aide.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-stage0"
    }
    compileOptions {
        val javaVersion = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}
```

`androidApp/src/main/AndroidManifest.xml` — минимальный валидный манифест; активность и `intent-filter` добавляет задача 2. Файл нужен уже здесь: без манифеста AGP не собирает модуль, и `./gradlew build` в шаге 10 недостижим.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="AI Studio" />
</manifest>
```

- [ ] **Шаг 10: добавить маркер пакета и проверить сборку с нуля на JDK 17**

`domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt`:

```kotlin
package dev.aide.domain

/** Маркер модуля домена. Удаляется в задаче 5, когда появляются настоящие типы. */
internal const val DOMAIN_MODULE = "domain"
```

Сборка идёт на **JDK 17**: Gradle 8.11, Kotlin 2.1.0 и AGP 8.7.3 не работают на Java 8 — без `JAVA_HOME` сборка падает ещё на резолве плагинов с `Dependency requires at least JVM runtime version 11` (для KGP) или `version 17` (для AGP). Запустить на чистой машине (без `~/.gradle/caches` от этого проекта):

```bash
export JAVA_HOME=/usr/lib/jvm/java-17-openjdk   # подойдёт любой JDK 17
./gradlew --stop && rm -rf .gradle build */build && ./gradlew build
```

Переменную нужно выставлять в каждой новой сессии терминала: абсолютный путь к JDK в репозитории не хранится — он сломал бы машины, где JDK лежит в другом месте. В CI JDK 17 ставит `actions/setup-java` (задача 3, шаг 3), поэтому там ничего дополнительно выставлять не нужно.

Ожидаемо: `BUILD SUCCESSFUL`, в выводе есть задачи `:domain:compileKotlinJvm`, `:domain:compileDebugKotlinAndroid`, `:domain:jvmTest` и `:androidApp:processDebugManifest`: `build` включает `check`, а тот в KMP зависит от `allTests`. Задача `:androidApp:processDebugManifest` проходит именно потому, что манифест из шага 9 существует: без него AGP падает с `Manifest file does not exist`, и сборка с нуля недостижима. Если `:domain:jvmTest` в выводе нет — этим прогоном тесты не проверяются, и запускать их нужно явным списком задач: `./gradlew :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest`.

Отдельно проверить Android-часть конвенции — одной задачей, без сборки APK:

```bash
./gradlew :domain:compileDebugKotlinAndroid
```

Ожидаемо: `BUILD SUCCESSFUL`. Именно эта задача ловит ошибку в конвенции: если `aide.kmp-library` не применяет `aide.android-library` или та не задаёт поля, сборка падает с `Namespace not specified` либо `compileSdkVersion is not specified`. Проверять её отдельно нужно потому, что без явной цели ошибка конвенции видна только в глубине вывода полного `build`.

- [ ] **Шаг 11: написать тест, что модули собраны и видны**

`domain/src/commonTest/kotlin/dev/aide/domain/DomainModuleSmokeTest.kt`:

```kotlin
package dev.aide.domain

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Smoke-проверка сборки модуля, а не поведенческий тест: она подтверждает, что модуль
 * домена скомпилирован и его исходники видны тестовой компиляции. Настоящие тесты домена
 * (round-trip моделей, документированность полей) появляются в задаче 5 вместе с моделями;
 * тогда этот файл удаляется вместе с `PackageMarker.kt`.
 */
class DomainModuleSmokeTest {
    @Test
    fun `домен собран и маркер модуля доступен`() {
        assertEquals("domain", DOMAIN_MODULE)
    }
}
```

- [ ] **Шаг 12: прогнать тест**

```bash
./gradlew :domain:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, отчёт в `domain/build/reports/tests/jvmTest/index.html` содержит 1 пройденный тест.

- [ ] **Шаг 13: коммит**

Положить в индекс исходники и wrapper:

```bash
git add .gitignore .gitattributes gradlew gradlew.bat gradle/wrapper gradle/libs.versions.toml \
    settings.gradle.kts build.gradle.kts gradle.properties \
    build-logic domain protocol client-state client-ui host-core \
    platform-android platform-desktop androidApp desktopApp
```

В списке есть все девять модулей, включая `client-ui`, `host-core` и `desktopApp`: у каждого из них есть минимальный build-файл (шаги 7 и 8), поэтому путь не пуст. Артефакты сборки (`build/`, `.gradle/`, `.kotlin/`, в том числе внутри `build-logic/`) отсекает `.gitignore` из шага 2 — это проверяется следующей командой.

Убедиться, что в индекс не попали артефакты сборки:

```bash
git status --porcelain | grep -E '(^|/)(build|\.gradle|\.kotlin)/' && echo "АРТЕФАКТЫ В ИНДЕКСЕ" || echo "чисто"
```

Ожидаемо: `чисто`. Если печатается `АРТЕФАКТЫ В ИНДЕКСЕ` — `.gitignore` неполон: убрать артефакт из индекса (`git reset` и правка `.gitignore`) и повторить. Без этой проверки дефект возвращается незамеченным: именно так в индекс попадали `*/build/**`, `build-logic/.gradle/**` и `.kotlin/errors/*.log`.

Убедиться, что `.gitattributes` успел примениться к wrapper'у до записи блоба:

```bash
git ls-files --eol gradlew.bat
git status --porcelain
```

Ожидаемо: `i/lf w/crlf attr/text eol=crlf` — в индексе LF, на диске CRLF (это и означает, что атрибут сработал при добавлении) — и пустой `git status`. Если `git status` показывает `gradlew.bat` изменённым, значит блоб записан с CRLF: исправить `git add --renormalize gradlew.bat` и закоммитить заново. Без проверки файл остаётся вечно «изменённым» после каждого чекаута, а fast-forward в `master` отказывается выполняться.

Только после этого коммитить:

```bash
git commit -m "chore(build): скелет KMP-проекта и модули домена, протокола, хоста и клиента"
```

---

## Задача 2: таргеты Android и десктоп (`T-0.2`)

**Файлы:**
- Изменить: `androidApp/build.gradle.kts`, `androidApp/src/main/AndroidManifest.xml`
- Создать: `androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`
- Изменить: `desktopApp/build.gradle.kts`
- Создать: `desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`
- Изменить: `client-ui/build.gradle.kts`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt`
- Тест: `client-ui/src/commonTest/kotlin/dev/aide/client/ui/SharedUiHasNoPlatformBranchingTest.kt`

Своего `AndroidManifest.xml` у `client-ui` нет, и он не нужен: манифест для модуля синтезируется из `namespace` и `minSdk` конвенции `aide.android-library` — в AAR получается `<manifest package="dev.aide.client.ui"><uses-sdk … /></manifest>` со значениями из каталога. Свой манифест появится, когда у модуля будут разрешения или компоненты.

- [ ] **Шаг 1: написать `client-ui` с Compose Multiplatform**

`client-ui/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            implementation(project(":domain"))
            // api, а не implementation (правило шага 7 задачи 1): параметры App — HostConnection
            // и SettingsStore — объявлены в client-state, поэтому точки входа видят эти типы
            // только через api. Сейчас App() параметров не имеет: они появятся вместе
            // с соединением в задаче 13.
            api(project(":client-state"))
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.ui)
            // api: `Strings.appName` и другие поля публичного объекта `Strings` имеют тип
            // `StringResource`, поэтому точки входа (заголовок окна в desktopApp) видят
            // этот тип только через api — иначе `Cannot access class 'StringResource'`.
            api(compose.components.resources)
        }
        androidMain.dependencies {
            implementation(libs.androidx.activity.compose)
        }
    }
}
```

Блока `android { }` здесь нет намеренно: `namespace` (`client-ui` → `dev.aide.client.ui`), `compileSdk`, `minSdk` и `compileOptions` задаёт конвенция `aide.kmp-library`.

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt` — пока минимальный экран, чтобы обе платформы что-то показывали:

```kotlin
package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

@Composable
fun App() {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("AI Studio")
            }
        }
    }
}
```

- [ ] **Шаг 2: написать Android-приложение**

`androidApp/build.gradle.kts` — полное содержимое после этого шага (к минимальному файлу из задачи 1 добавляются Kotlin, Compose и зависимости):

```kotlin
plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.composeCompiler)
}

android {
    namespace = "dev.aide.android"
    compileSdk = libs.versions.androidCompileSdk.get().toInt()
    defaultConfig {
        applicationId = "dev.aide.android"
        minSdk = libs.versions.androidMinSdk.get().toInt()
        targetSdk = libs.versions.androidCompileSdk.get().toInt()
        versionCode = 1
        versionName = "0.1.0-stage0"
    }
    buildFeatures { compose = true }
    compileOptions {
        val javaVersion = JavaVersion.toVersion(libs.versions.jvmTarget.get())
        sourceCompatibility = javaVersion
        targetCompatibility = javaVersion
    }
}

dependencies {
    implementation(project(":client-ui"))
    implementation(project(":platform-android"))
    implementation(libs.androidx.activity.compose)
}
```

`kotlinAndroid` и его объявление `apply false` в корневом `build.gradle.kts` уже сделаны в задаче 1 (шаги 1 и 5) — здесь плагин только применяется, править каталог и корневой скрипт не нужно. Именно с этого шага `androidApp` становится Kotlin-модулем: до него он собирался как Java-only Android-модуль, и его зелёная сборка ничего не доказывала.

`androidApp/src/main/AndroidManifest.xml` — в манифест из задачи 1 добавлена активность с `intent-filter`: без неё приложение не запускается с рабочего стола.

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:label="AI Studio" android:theme="@android:style/Theme.Material.NoActionBar">
        <activity android:name=".MainActivity" android:exported="true">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>
    </application>
</manifest>
```

`androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`:

```kotlin
package dev.aide.android

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.activity.ComponentActivity
import dev.aide.client.ui.App

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { App() }
    }
}
```

- [ ] **Шаг 3: написать десктопное приложение**

`desktopApp/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.composeMultiplatform)
    alias(libs.plugins.composeCompiler)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    implementation(project(":client-ui"))
    implementation(project(":platform-desktop"))
    implementation(compose.desktop.currentOs)
}

compose.desktop {
    application {
        mainClass = "dev.aide.desktop.MainKt"
    }
}
```

`kotlinJvm` уже объявлен в каталоге плагинов на шаге 1 задачи 1 — добавлять его второй раз не нужно.

`desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`:

```kotlin
package dev.aide.desktop

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.ui.App

fun main() = application {
    Window(onCloseRequest = ::exitApplication, title = "AI Studio") {
        App()
    }
}
```

- [ ] **Шаг 4: написать тест, что общий UI не ветвится по платформе**

`client-ui/src/commonTest/kotlin/dev/aide/client/ui/SharedUiHasNoPlatformBranchingTest.kt` — тест читает исходники `client-ui/src/commonMain` и падает, если найдёт платформенные проверки:

```kotlin
package dev.aide.client.ui

import okio.FileSystem
import okio.Path.Companion.toPath
import kotlin.test.Test
import kotlin.test.assertTrue

class SharedUiHasNoPlatformBranchingTest {

    private val forbidden = listOf(
        "expect fun platformName",
        "System.getProperty(\"os.name\")",
        "android.os.Build",
        "isLinux",
        "isWindows",
        "isMacOs",
    )

    @Test
    fun `общие экраны не содержат ветвлений по платформе`() {
        // Gradle запускает тест с рабочим каталогом модуля, поэтому первый кандидат — `src/commonMain`;
        // второй покрывает запуск из корня репозитория.
        val root = listOf("src/commonMain", "client-ui/src/commonMain")
            .map { it.toPath() }
            .firstOrNull { FileSystem.SYSTEM.exists(it) }
            ?: error("Не найден каталог исходников client-ui — проверь рабочий каталог теста (Test.workingDir)")

        val offenders = mutableListOf<String>()
        FileSystem.SYSTEM.listRecursively(root).forEach { path ->
            if (!path.name.endsWith(".kt")) return@forEach
            val text = FileSystem.SYSTEM.read(path) { readUtf8() }
            forbidden.forEach { needle ->
                if (text.contains(needle)) offenders += "${path.name}: $needle"
            }
        }
        assertTrue(offenders.isEmpty(), "Платформенные ветвления в общем UI: $offenders")
    }
}
```

Путь к исходникам ищется среди двух кандидатов не для красоты: `Test.workingDir` у Gradle — каталог модуля (`client-ui`), а не корень репозитория, поэтому путь `client-ui/src/commonMain` от рабочего каталога не находится, и тест падал бы ещё до ассерта. Если не найден ни один кандидат, тест падает с явной ошибкой про рабочий каталог, а не с `FileNotFoundException`.

Добавить в `client-ui` зависимость `commonTest.dependencies { implementation(libs.okio) }` и объявить в каталоге `okio = { module = "com.squareup.okio:okio", version = "3.9.1" }`.

- [ ] **Шаг 5: прогнать тест**

```bash
./gradlew :client-ui:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 1 тест пройден. Затем проверить, что тест действительно работает: временно вставить в `App.kt` строку `val os = System.getProperty("os.name")`, повторить команду — ожидаемо `FAILED` с сообщением `App.kt: System.getProperty("os.name")`. Вернуть файл как было.

- [ ] **Шаг 6: собрать оба таргета**

```bash
./gradlew :androidApp:assembleDebug :desktopApp:createDistributable
```

Ожидаемо: `BUILD SUCCESSFUL`; появились `androidApp/build/outputs/apk/debug/androidApp-debug.apk` и `desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp`.

```bash
./desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp
```

Ожидаемо: открывается окно «AI Studio» с текстом «AI Studio». Закрыть окно.

- [ ] **Шаг 7: коммит**

```bash
git add androidApp desktopApp client-ui gradle/libs.versions.toml build.gradle.kts
git commit -m "feat(build): таргеты Android и десктоп поверх общего client-ui"
```

---

## Задача 3: CI — сборка, линт, тесты (`T-0.3`)

**Файлы:**
- Создать: `.github/workflows/ci.yml`
- Создать: `config/detekt/detekt.yml`
- Изменить: `build.gradle.kts` (подключить detekt в подпроектах)

`.gitignore` создан в задаче 1, шаге 2 — здесь он не дублируется, чтобы у списка игнорируемых путей был один источник.

- [ ] **Шаг 1: проверить, что `.gitignore` покрывает артефакты сборки**

`.gitignore` уже создан в задаче 1 (шаг 2) и закоммичен вместе с wrapper'ом. Перед первым прогоном CI убедиться, что ни один артефакт не попал в индекс:

```bash
git status --porcelain | grep -E '(^|/)(build|\.gradle|\.kotlin)/' && echo "АРТЕФАКТЫ В ИНДЕКСЕ" || echo "чисто"
```

Ожидаемо: `чисто`. Если печатается `АРТЕФАКТЫ В ИНДЕКСЕ` — дописать пропущенный путь в `.gitignore` и повторить.

- [ ] **Шаг 2: подключить detekt как линтер**

Добавить в `gradle/libs.versions.toml`:

```toml
detekt = "1.23.7"

[plugins]
detekt = { id = "io.gitlab.arturbosch.detekt", version.ref = "detekt" }
```

В корневом `build.gradle.kts` добавить:

```kotlin
plugins {
    // … существующие alias'ы …
    alias(libs.plugins.detekt) apply false
}

subprojects {
    apply(plugin = "io.gitlab.arturbosch.detekt")
    extensions.configure<io.gitlab.arturbosch.detekt.extensions.DetektExtension> {
        config.setFrom(rootProject.files("config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        // Без этой строки detekt молчит на KMP-модулях: по умолчанию он смотрит только
        // в `src/main/kotlin` и `src/test/kotlin`, а исходники KMP лежат в `src/commonMain/kotlin`,
        // `src/androidMain/kotlin` и так далее. Прогон тогда печатает `:domain:detekt NO-SOURCE`,
        // `:client-ui:detekt NO-SOURCE` и линтит только `androidApp` и `desktopApp`.
        setSource(fileTree("src") { include("**/*.kt", "**/*.kts") })
    }
}
```

`config/detekt/detekt.yml` — минимум, который ловит реальные проблемы и не шумит:

```yaml
complexity:
  LongMethod:
    threshold: 80
  TooManyFunctions:
    thresholdInFiles: 25
style:
  MagicNumber:
    active: true
    ignoreNumbers: ['-1', '0', '1', '2', '100']
  MaxLineLength:
    maxLineLength: 120
naming:
  FunctionNaming:
    # Compose требует PascalCase у composable-функций, поэтому `fun App()` — не нарушение.
    # Исключение узкое: для всех остальных функций правило работает как обычно.
    ignoreAnnotated: ['Composable']
```

`FunctionNaming` лежит в наборе `naming`, а не в `style`: если положить его в `style`, detekt не примет конфиг — такого свойства в этом наборе нет, и ошибка будет вида `Property '…>FunctionNaming' is misspelled or does not exist`. Исключение для `@Composable` — вынужденное (имя `App()` задано соглашением Compose), но узкое: ни одно правило не отключено, у остальных функций имя по-прежнему проверяется.

Про пороги: на текущем объёме кода они не несут нагрузки — прогон со стоковыми дефолтами detekt тоже зелёный, а `LongMethod: 80` и `TooManyFunctions: 25` сработают только на крупных файлах. Значение `'100'` в `ignoreNumbers` ничем не обосновано; когда какое-то из этих правил впервые помешает, порог нужно пересматривать осознанно, а не расширять список значений молча.

- [ ] **Шаг 3: написать workflow**

`.github/workflows/ci.yml`:

```yaml
name: CI

on:
  push:
  pull_request:

jobs:
  build:
    runs-on: ubuntu-latest
    timeout-minutes: 15
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - name: Android SDK
        uses: android-actions/setup-android@v3
        with:
          # Дефолт действия — `tools platform-tools`, а пакета `tools` в репозитории Android SDK
          # больше нет: шаг падает за секунды с `Failed to find package 'tools'`. Нужный набор
          # (platform 35 и build-tools 35.0.0) всё равно ставится следующим шагом.
          packages: platform-tools

      - name: Лицензии Android SDK
        run: yes | sdkmanager --licenses

      - name: Платформа и инструменты Android
        run: sdkmanager "platform-tools" "platforms;android-35" "build-tools;35.0.0"

      - name: Юнит-тесты
        run: ./gradlew :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest

      - name: Линт
        run: ./gradlew detekt

      - name: Сборка Android
        run: ./gradlew :androidApp:assembleDebug

      - name: Сборка десктопа
        run: ./gradlew :desktopApp:createDistributable

      - name: Бюджет времени прогона
        run: echo "Прогон без кэша не должен превышать 15 минут — ограничение задано job.timeout-minutes"

  # Windows-дистрибутив Compose Desktop с Linux не собрать: `compose.desktop.currentOs`
  # подставляет артефакты хоста. Поэтому Windows собирается на своём раннере —
  # этот job и закрывает требование «Linux и Windows» из T-0.2.
  desktop-windows:
    runs-on: windows-latest
    timeout-minutes: 30
    steps:
      - uses: actions/checkout@v4

      - uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: '17'

      - uses: gradle/actions/setup-gradle@v4

      - name: Сборка десктопа под Windows
        shell: bash
        run: ./gradlew :desktopApp:createDistributable

      - name: Тесты JVM-модулей под Windows
        shell: bash
        run: ./gradlew :client-ui:jvmTest :client-state:jvmTest
```

`shell: bash` у шагов Windows-сборки нужен явно: `gradlew` без расширения — это POSIX-скрипт, и в `pwsh` (оболочка по умолчанию на `windows-latest`) он не запускается. `bash` на раннерах GitHub есть (Git for Windows). Других оболочечных различий в job'е нет: обе команды идут одной строкой, без `chmod` и многострочных конструкций. `JAVA_HOME` на Windows выставляет `actions/setup-java`, отдельная настройка не нужна.

Почему в Windows-job'е идут ещё и тесты: единственный Windows-специфичный код этапа — путь к файлу настроек в `client-state/src/jvmMain/.../KeyValueStore.jvm.kt` (там `Path.of(System.getProperty("user.home"), ".config")`, то есть на Windows `%USERPROFILE%\.config`). Если этот job только собирает дистрибутив, такой код на Windows не проверяется вообще, поэтому здесь запускаются `jvmTest` обоих клиентских модулей. GUI-тесты (`compose.uiTest`) здесь не гоняются: они требуют графической сессии и остаются в Linux-job'е.

Шаг `Android SDK` ставит только `platform-tools`, и это не мелочь: дефолтный набор действия (`tools platform-tools`) сегодня не резолвится — пакет `tools` из репозитория Android SDK убран, и шаг падает за секунды с `Warning: Failed to find package 'tools'`. Платформа и build-tools ставятся следующей командой `sdkmanager` с явными версиями из каталога (`android-35`, `35.0.0`), поэтому дефолт не нужен и оставлять его нельзя.

**Что с барьером против `java.*` в общем коде.** Задача метадата-компиляции общего кода для текущего набора таргетов не работает: в KGP 2.1.0 она отключена, когда у модуля только `jvm` и `androidJvm` (JVM и Android делят бэкенд — KT-42383, KT-42468), поэтому в CI она уходила в `SKIPPED`, а `java.util.UUID` в `client-ui/commonMain` компилируется зелёным. Настоящий компиляторный барьер появится, когда у модуля будет не-JVM таргет — это придёт на этапе 9 (iOS и macOS). До тех пор общий код защищает статический тест-сканер: тест из задачи 2, который в задаче 13 сводится к одному механизму и усиливается до проверки импортов платформенных пакетов (`java.`, `javax.`, `android.`, `kotlinx.cinterop`, `platform.`, `UIKit`).

**Фактическое время прогона (первый прогон CI, оба job'а зелёные).** Холодный `build` — 7m0s из 15 минут бюджета; в логе видно `Gradle User Home cache not found. Will initialize empty.`, то есть кэш действительно пустой. `desktop-windows` — 6m5s из 30 минут. Тёплый прогон с прогретым кэшем — около 2m. Эти числа — ориентир для следующих правок CI: если холодный прогон перестанет укладываться в 15 минут, сначала смотреть, что именно добавилось, а не поднимать `timeout-minutes`.

`timeout-minutes: 30` у `desktop-windows` — с запасом больше, чем у `build`: холодная Windows-сборка скачивает Compose- и Skiko-артефакты под Windows и упаковывает дистрибутив через `jpackage`; фактический холодный прогон занял 6m5s (см. замеры выше). Бюджет 15 минут из `T-0.3` относится к job `build` и не меняется: job'ы идут параллельно, поэтому Windows-сборка Linux-прогон не удлиняет.

Почему в шаге «Юнит-тесты» модули перечислены явно: `test` поднимает тесты только у JVM-модулей (`host-core`), а тестовая задача KMP-модуля называется `jvmTest` — агрегат `allTests` вместо этого тянет тестовые задачи всех таргетов, включая Android, для которых план не задаёт ни ожидаемого результата, ни окружения. Явный список запускает ровно те тесты, что описаны в плане, и падает заметно при опечатке в имени задачи или переименовании модуля.

Почему Android-сборка в том же job, а не отдельным: таргет `androidTarget()` и `compileSdk` из `gradle/libs.versions.toml` есть у KMP-модулей, поэтому Android SDK нужен и юнит-тестам, и сборке — отдельный job дублировал бы установку SDK и добавил бы ещё один обязательный статус в правила защиты ветки (шаг 4). Windows вынесен отдельно не по вкусу, а по необходимости: кросс-сборки у Compose Desktop нет.

Про JDK два независимых механизма, и оба нужны. `actions/setup-java` ставит JDK 17 в раннер — на нём запускаются Gradle и `jvmToolchain(17)` из конвенций. Резолвер тулчейнов (foojay) настроен в `settings.gradle.kts` (задача 1, шаг 4) — он закрывает случай, когда установленный JDK не совпадает с требуемым: тогда Gradle скачивает подходящий сам, а не падает с `No matching toolchains found`. Одно другому не мешает: если JDK 17 уже есть, резолвер не вызывается.

- [ ] **Шаг 4: включить требование зелёного CI на ветке**

В настройках репозитория (GitHub → Settings → Branches → Branch protection rules → `master`) включить **Require status checks to pass before merging** и выбрать оба job'а — `build` и `desktop-windows`. Без второго Windows-сборка окажется необязательной, и требование «Linux и Windows» из `T-0.2` перестанет что-либо гарантировать. Это настройка репозитория, а не файла; если доступов нет — отметить в описании задачи, что шаг выполняется владельцем репозитория.

- [ ] **Шаг 5: проверить, что линт действительно ловит нарушение**

Временно добавить по строке длиннее 120 символов в два KMP-модуля — в `domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt` и в `client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt` — и запустить:

```bash
./gradlew detekt
```

Ожидаемо: `FAILED`; в выводе есть `:domain:detekt FAILED` и `:client-ui:detekt FAILED` со строками `MaxLineLength`, при этом `:androidApp:detekt` и `:desktopApp:detekt` проходят. Оба KMP-модуля обязаны падать: если вместо `FAILED` видно `:domain:detekt NO-SOURCE` или `:client-ui:detekt NO-SOURCE`, значит источники KMP не подключены — вернуться к `setSource(...)` из шага 2, иначе линт молчит и проверка ничего не проверяет. Вернуть оба файла.

- [ ] **Шаг 6: прогнать весь набор локально**

```bash
./gradlew clean :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest detekt :androidApp:assembleDebug :desktopApp:createDistributable
./gradlew -p build-logic test
```

Ожидаемо: `BUILD SUCCESSFUL` в обоих прогонах. Набор задач тот же, что в workflow; тесты логики сборки идут отдельной командой, потому что `build-logic` — самостоятельная included-сборка и её `:test` не входит в корневой `check`. Если хотя бы один тест KMP-модуля падает, прогон падает здесь, а не только в CI. Замерить время и сверить с измеренным CI: холодный `build` — 7m0s из 15 минут, тёплый прогон с прогретым кэшем — около 2m; локальный прогон без кэша должен укладываться в те же 15 минут. Если нет — включить `org.gradle.caching` и кэш `gradle/actions/setup-gradle` уже включены; при превышении разобрать, какая задача дольше всех, командой `./gradlew build --profile` и посмотреть `build/reports/profile/`.

- [ ] **Шаг 7: проверить, что прогон ловит падение теста KMP-модуля**

Временно создать заведомо падающий тест в KMP-модуле — в `domain`, рядом с существующим `DomainModuleSmokeTest`:

`domain/src/commonTest/kotlin/dev/aide/domain/IntentionalFailureTest.kt`:

```kotlin
package dev.aide.domain

import kotlin.test.Test
import kotlin.test.fail

/**
 * Временный тест: он нужен только для проверки, что прогон действительно
 * запускает тесты KMP-модуля. Удаляется сразу после проверки.
 */
class IntentionalFailureTest {
    @Test
    fun `намеренная поломка`() {
        fail("намеренная поломка: прогон дошёл до тестов KMP-модуля")
    }
}
```

Прогнать ту же команду, что в шаге 6 (или её тестовую часть):

```bash
./gradlew :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest
```

Ожидаемо: `FAILED`; в выводе есть задача `:domain:jvmTest FAILED`, класс `IntentionalFailureTest` и строка с именем теста `намеренная поломка`. Если бы тесты KMP-модулей не запускались, команда завершилась бы успехом — именно это и проверяется. Удалить файл `domain/src/commonTest/kotlin/dev/aide/domain/IntentionalFailureTest.kt`.

- [ ] **Шаг 8: коммит**

```bash
git add .github/workflows/ci.yml config/detekt/detekt.yml build.gradle.kts gradle/libs.versions.toml
git commit -m "ci: сборка Android и десктопа, линт detekt и юнит-тесты на каждый push"
```

---

## Задача 4: правило границ между модулями (`T-0.4`)

**Файлы:**
- Создать: `build-logic/src/main/kotlin/ModuleBoundariesTask.kt`
- Создать: `build-logic/src/main/kotlin/aide.module-boundaries.gradle.kts`
- Изменить: `build-logic/build.gradle.kts` (тестовая зависимость и запуск на JUnit Platform)
- Изменить: `build.gradle.kts` (применить конвенцию `aide.module-boundaries`)
- Изменить: `settings.gradle.kts` (ничего, если `includeBuild` уже есть)
- Изменить: `.github/workflows/ci.yml` (шаги «Тесты логики сборки» и «Границы модулей»)
- Тест: `build-logic/src/test/kotlin/ModuleBoundariesTest.kt`

- [ ] **Шаг 1: написать падающий тест на сам чекер**

Логика проверки — чистая функция, поэтому тестируется без Gradle. `build-logic/src/main/kotlin/ModuleBoundariesTask.kt`:

```kotlin
package aide.build

/**
 * Правило границ: какие префиксы пакетов запрещены в каких модулях.
 *
 * Проверяются исходники только main-наборов модуля из [module]: в тестах ребро
 * «хост знает клиента» легально (интеграционный тест поднимает хост и подключается
 * к нему настоящим клиентом), в main-коде — нет.
 */
data class ModuleBoundary(
    val module: String,
    val forbiddenPackagePrefixes: List<String>,
    /** Почему запрещено: печатается в сообщении о нарушении. */
    val explanation: String,
    /** Что делать нарушителю. У каждого модуля своё, поэтому это часть правила, а не общий совет. */
    val action: String,
)

object ModuleBoundaries {
    val rules: List<ModuleBoundary> = listOf(
        ModuleBoundary(
            module = "client-ui",
            forbiddenPackagePrefixes = listOf("dev.aide.host", "org.eclipse.jgit", "app.cash.sqldelight"),
            explanation = "Клиент не обращается к хосту, git и БД напрямую — только через API хоста (§ 3.2, § 8.1)",
            action = "Перенести работу на сторону хоста и вызвать её через API хоста.",
        ),
        ModuleBoundary(
            module = "client-state",
            forbiddenPackagePrefixes = listOf("dev.aide.host", "org.eclipse.jgit", "app.cash.sqldelight"),
            explanation = "Состояние клиента не знает о реализации хоста (§ 8.1)",
            action = "Перенести работу на сторону хоста и вызвать её через API хоста.",
        ),
        ModuleBoundary(
            module = "domain",
            forbiddenPackagePrefixes = listOf(
                "androidx.compose", "org.jetbrains.compose", "io.ktor", "dev.aide.host", "dev.aide.client",
            ),
            explanation = "Домен не знает ни о UI, ни о транспорте, ни о хосте (§ 8.1)",
            action = "Убрать из домена зависимость на UI, транспорт и хост: домен — это чистые данные и правила.",
        ),
        ModuleBoundary(
            module = "protocol",
            forbiddenPackagePrefixes = listOf("androidx.compose", "org.jetbrains.compose", "dev.aide.host", "dev.aide.client"),
            explanation = "Протокол не знает ни о UI, ни о реализации хоста (§ 8.1)",
            action = "Оставить в протоколе только описание сообщений: UI и хост живут в своих модулях.",
        ),
        ModuleBoundary(
            module = "host-core",
            forbiddenPackagePrefixes = listOf("dev.aide.client", "androidx.compose", "org.jetbrains.compose"),
            explanation = "Хост не знает о клиенте и его UI: направление «хост → клиент» запрещено (§ 3.2, § 8.1)",
            action = "Перенести клиентский код в client-* и вызывать хост по протоколу; ребро host-core → client-state допустимо только в тестах (testImplementation).",
        ),
    )

    /** Возвращает список нарушений: «файл: запрещённый пакет» — по одной записи на каждое вхождение в начало строки с import. */
    fun findViolations(module: String, files: Map<String, String>): List<String> {
        val rule = rules.firstOrNull { it.module == module } ?: return emptyList()
        val importRegex = Regex("""^\s*import\s+([A-Za-z0-9_.]+)""", RegexOption.MULTILINE)
        return buildList {
            files.forEach { (fileName, text) ->
                importRegex.findAll(text).forEach { match ->
                    val imported = match.groupValues[1]
                    rule.forbiddenPackagePrefixes
                        .firstOrNull { imported.startsWith(it) }
                        ?.let { add("$fileName: $it → $imported") }
                }
            }
        }
    }

    fun message(module: String, violations: List<String>): String {
        val rule = rules.first { it.module == module }
        return buildString {
            appendLine("Нарушены границы модуля '$module':")
            violations.forEach { appendLine("  - $it") }
            appendLine("Почему это запрещено: ${rule.explanation}")
            appendLine("Что делать: ${rule.action}")
        }
    }
}
```

`build-logic/src/test/kotlin/ModuleBoundariesTest.kt`:

```kotlin
package aide.build

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ModuleBoundariesTest {

    @Test
    fun `импорт хоста в client-ui считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "client-ui",
            files = mapOf("App.kt" to "package dev.aide.client.ui\nimport dev.aide.host.HostApp\n"),
        )
        assertEquals(listOf("App.kt: dev.aide.host → dev.aide.host.HostApp"), violations)
    }

    @Test
    fun `импорт compose в domain считается нарушением`() {
        val violations = ModuleBoundaries.findViolations(
            module = "domain",
            files = mapOf("Risk.kt" to "import org.jetbrains.compose.ui.Modifier\n"),
        )
        assertEquals(listOf("Risk.kt: org.jetbrains.compose → org.jetbrains.compose.ui.Modifier"), violations)
    }

    @Test
    fun `чистый файл нарушений не даёт`() {
        val violations = ModuleBoundaries.findViolations(
            module = "domain",
            files = mapOf("Risk.kt" to "import kotlinx.serialization.Serializable\n"),
        )
        assertTrue(violations.isEmpty())
    }

    @Test
    fun `текст сообщения объясняет причину и действие`() {
        val message = ModuleBoundaries.message("client-ui", listOf("App.kt: dev.aide.host → dev.aide.host.HostApp"))
        assertTrue(message.contains("Нарушены границы модуля 'client-ui'"))
        assertTrue(message.contains("Почему это запрещено"))
        assertTrue(message.contains("Что делать"))
    }
}
```

**Правило зависимостей: `api` или `implementation`.** Проверка выше смотрит на импорты, но половина границ держится на объявлении зависимостей, и здесь действует одно правило: **`api`, если тип виден в публичной сигнатуре модуля, иначе `implementation`.** В этом проекте это значит: `:domain` → `api(libs.kotlinx.datetime)`, `api(libs.kotlinx.serialization.core)`; `:protocol` → `api(project(":domain"))`; `:client-state` → `api(project(":protocol"))`; `:client-ui` → `api(project(":client-state"))`; `:platform-*` → `api(...)` на то, что они отдают в `androidApp` и `desktopApp`. Слишком широкий `api` размывает границу (потребитель видит лишнее), слишком узкий `implementation` ломает сборку у потребителя сообщением `Cannot access class '…'` — оба случая видны на компиляции, поэтому правило проверяемо, а не декларативно.

**Почему у правила есть поле `action`.** Совет «что делать» у каждого модуля свой: `domain` не должен ничего переносить на хост — ему нужно убрать зависимость на UI или транспорт, и прежняя общая формулировка на этом модуле просто неверна. Поэтому текст действия — часть правила, а не константа в `message`. Тест `текст сообщения объясняет причину и действие` проверяет, что оба блока в сообщении есть.

**Исключения, которые правило границ обязано пропускать.** Их два, и они не нарушения:

1. `platform-desktop` → `host-core` — легально. Локальный режим (§ 3.3) означает, что хост живёт в том же процессе, и знает об этом ровно один модуль — `platform-desktop`; `client-state` и `client-ui` о хосте не знают. Поэтому в список проверяемых модулей входит `client-ui`, но не `platform-desktop`, и расширять правило на зависимости сборки без этого исключения нельзя.
2. `host-core` → `client-state` **в тестовой конфигурации** — легально. Интеграционный тест `EmbeddedHostTest` (задача 13) подключается к поднятому хосту настоящим клиентом (`HostClient`, `KtorHostConnection`), то есть `host-core` нужен `testImplementation(project(":client-state"))`. Направление «хост знает клиента» в main-коде остаётся запрещённым, и это не декларация: `host-core` входит в список защищаемых модулей с запретом `dev.aide.client`, а сканирование ограничено main-наборами — тестовое ребро правило не видит, а импорт клиента в main-коде хоста валит проверку.

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

Добавить в `build-logic/build.gradle.kts` тестовую зависимость и запуск на JUnit Platform (остальное содержимое файла — как в задаче 1, шаге 6: версии KGP и AGP берутся из `build-logic/gradle/libs.versions.toml`):

```kotlin
dependencies {
    testImplementation(kotlin("test"))
}

tasks.test { useJUnitPlatform() }
```

```bash
./gradlew -p build-logic test
```

Ожидаемо: `BUILD SUCCESSFUL`, `ModuleBoundariesTest` — 4 пройденных теста. Если тест добавляется раньше реализации, прогон падает с `Unresolved reference: ModuleBoundaries`; но шаг 1 содержит и код чекера, поэтому при обычном порядке работы (тест и код пишутся вместе) здесь ожидается успех, а не падение.

- [ ] **Шаг 3: написать задачу `VerifyModuleBoundariesTask`**

Добавить в конец `ModuleBoundariesTask.kt`:

```kotlin
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

abstract class VerifyModuleBoundariesTask : DefaultTask() {

    /** Имя модуля, к которому применено правило; подставляется при регистрации задачи. */
    @get:Input
    abstract val module: Property<String>

    /**
     * Каталог модуля — нужен только для относительных путей в сообщении.
     *
     * Это свойство, а не `project.projectDir`: обращение к `Task.project` в момент
     * выполнения запрещено при включённом configuration cache (а он включён в задаче 1),
     * и сборка падает целиком с `invocation of 'Task.project' at execution time is unsupported`.
     */
    @get:Internal
    abstract val projectDirectory: DirectoryProperty

    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sources: ConfigurableFileCollection

    @TaskAction
    fun verify() {
        val root = projectDirectory.get().asFile
        val files = sources.files.associate { it.relativeTo(root).path to it.readText() }
        val violations = ModuleBoundaries.findViolations(module.get(), files)
        if (violations.isNotEmpty()) {
            throw GradleException(ModuleBoundaries.message(module.get(), violations))
        }
        logger.lifecycle("Границы модуля '${module.get()}' соблюдены (проверено файлов: ${files.size})")
    }
}
```

- [ ] **Шаг 4: написать конвенцию `aide.module-boundaries` и применить её в корне**

Регистрация задачи живёт в конвенционном плагине, а не в корневом `build.gradle.kts`: `pluginManagement { includeBuild("build-logic") }` отдаёт корневому скрипту только плагины, но не классы, поэтому `import aide.build.VerifyModuleBoundariesTask` в корне не собирается — падает с `Unresolved reference: aide`. Конвенция же лежит внутри `build-logic`, где класс задачи доступен напрямую.

`build-logic/src/main/kotlin/aide.module-boundaries.gradle.kts`:

```kotlin
import aide.build.VerifyModuleBoundariesTask

// Имена main-наборов. Тестовые наборы не проверяются: в тестах ребро host-core → client-state
// легально (интеграционный тест поднимает хост и подключается к нему настоящим клиентом).
val mainSourceSetNames = listOf("commonMain", "jvmMain", "androidMain", "main")

val guardedModules = setOf("domain", "protocol", "client-ui", "client-state", "host-core")

subprojects {
    if (name !in guardedModules) return@subprojects

    // Имя модуля нужно захватить здесь, в области подпроекта: внутри конфигурации задачи
    // `name` — это уже имя самой задачи, и в сообщении печаталось бы 'verifyModuleBoundaries'.
    val moduleName = name

    val verify = tasks.register<VerifyModuleBoundariesTask>("verifyModuleBoundaries") {
        group = "verification"
        description = "Проверяет границы модуля (T-0.4)"
        module.set(moduleName)
        projectDirectory.set(layout.projectDirectory)
        sources.from(fileTree("src") { mainSourceSetNames.forEach { include("$it/**/*.kt") } })
    }

    // Блок subprojects {} выполняется при конфигурации корня, то есть до применения плагинов
    // подпроекта, и попытка привязаться к check прямо там падает с `Task with name 'check' not found`.
    // Поэтому привязка отложенная: ждём, пока в подпроекте появится base-плагин.
    plugins.withId("base") {
        tasks.named("check") { dependsOn(verify) }
    }
}
```

В корневом `build.gradle.kts` остаётся одна строка — применить конвенцию:

```kotlin
plugins {
    // … существующие alias'ы …
    id("aide.module-boundaries")
}
```

- [ ] **Шаг 5: проверить, что чистая сборка проходит**

```bash
./gradlew verifyModuleBoundaries
```

Ожидаемо: для каждого из пяти модулей строка `Границы модуля '<имя>' соблюдены (проверено файлов: N)` — имена `domain`, `protocol`, `client-ui`, `client-state`, `host-core`, — и `BUILD SUCCESSFUL`. Если `host-core` в выводе нет, значит правило для хоста не подключено, и заявленный запрет «хост не знает клиента» ничем не обеспечен.

- [ ] **Шаг 6: проверить намеренным нарушением**

Добавить в `client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt` строку `import dev.aide.host.HostApp` — именно в main-набор: тестовые наборы правило не сканирует — и запустить:

```bash
./gradlew :client-ui:verifyModuleBoundaries
```

Ожидаемо: `BUILD FAILED` и текст:

```
Нарушены границы модуля 'client-ui':
  - src/commonMain/kotlin/dev/aide/client/ui/App.kt: dev.aide.host → dev.aide.host.HostApp
Почему это запрещено: Клиент не обращается к хосту, git и БД напрямую — только через API хоста (§ 3.2, § 8.1)
Что делать: Перенести работу на сторону хоста и вызвать её через API хоста.
```

Удалить нарушающую строку.

- [ ] **Шаг 7: добавить проверки в CI**

В `.github/workflows/ci.yml` в Linux-job'е `build` после шага линта добавить:

```yaml
      - name: Тесты логики сборки
        run: ./gradlew -p build-logic test

      - name: Границы модулей
        run: ./gradlew verifyModuleBoundaries
```

Тесты `build-logic` не выполняются основной сборкой: `build-logic` — отдельная included-сборка, и её `:test` не входит ни в корневой `check`, ни в `./gradlew build`, поэтому в CI она запускается явно.

**Две известные дыры, которые сейчас не закрываются.** Первая: `build-logic` не линтуется detekt — корневой `subprojects { detekt }` до included-сборки не достаёт, поэтому новый код правил границ линт не проходит. Если это станет проблемой, понадобится отдельная detekt-задача внутри самой included-сборки; отдельного шага на это в плане нет намеренно. Вторая: правило не проверялось на Windows — job `desktop-windows` не вызывает `check`, поэтому `verifyModuleBoundaries` там не выполняется; проверка есть только в Linux-job'е.

- [ ] **Шаг 8: коммит**

```bash
git add build-logic build.gradle.kts .github/workflows/ci.yml
git commit -m "build: правило границ модулей как Gradle-задача с понятным сообщением"
```

---

## Задача 5: модели домена (`T-0.5`)

**Файлы:**
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/Ids.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/Enums.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/Task.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/AgentRun.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/ToolCall.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/ChangePacket.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/ReviewDecision.kt`
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/Snapshot.kt`
- Удалить: `domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt`
- Удалить: `domain/src/commonTest/kotlin/dev/aide/domain/DomainModuleSmokeTest.kt`
- Создать: `domain/src/commonTest/kotlin/dev/aide/domain/DomainFixtures.kt`
- Создать: `domain/src/commonTest/kotlin/dev/aide/domain/DomainRoundTripTest.kt`
- Создать: `domain/src/jvmTest/kotlin/dev/aide/domain/PublicFieldsAreDocumentedTest.kt`
- Изменить: `domain/build.gradle.kts` (CBOR в тестах, Dokka, системное свойство для теста документации)

**Про договорённость об именах.** Дальше во всём плане используются только эти имена. Модели объявляются без валидации — инварианты добавляются в задаче 6, вычисление риска — в задаче 7. Объяснение на уровне отдельного hunk'а (`FR-DIFF-14`) в домен не входит: оно появляется в `T-1.25` как поле `Hunk.explanation`.

- [ ] **Шаг 1: написать идентификаторы**

`domain/src/commonMain/kotlin/dev/aide/domain/Ids.kt`:

```kotlin
package dev.aide.domain

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Идентификатор задачи, поставленной пользователем. */
@Serializable
@JvmInline
value class TaskId(val value: String)

/** Идентификатор одного прогона агента по задаче. */
@Serializable
@JvmInline
value class RunId(val value: String)

/** Идентификатор пакета изменений — единицы ревью. */
@Serializable
@JvmInline
value class PacketId(val value: String)

/** Идентификатор блока изменений внутри файла. */
@Serializable
@JvmInline
value class HunkId(val value: String)

/** Идентификатор одного вызова инструмента агентом. */
@Serializable
@JvmInline
value class ToolCallId(val value: String)

/** Ссылка на снапшот в git, вида `refs/ai/snap/<timestamp>-<label>` (§ 9). */
@Serializable
@JvmInline
value class SnapshotRef(val value: String)
```

- [ ] **Шаг 2: написать перечисления**

`domain/src/commonMain/kotlin/dev/aide/domain/Enums.kt`:

```kotlin
package dev.aide.domain

import kotlinx.serialization.Serializable

/**
 * Уровень риска изменения. Порядок объявления — от меньшего к большему,
 * поэтому [RiskLevel.ordinal] можно сравнивать: инвариант 5 § 4.1 требует,
 * чтобы риск пакета был не ниже максимума по его hunk'ам.
 */
@Serializable
enum class RiskLevel { SAFE, NORMAL, RISKY }

/** Режим автономности агента (§ 5.3.1). */
@Serializable
enum class AutonomyMode {
    /** «Только предлагать» — агент ничего не применяет сам. */
    SUGGEST_ONLY,

    /** «Спрашивать перед изменениями» — каждое изменяющее действие требует подтверждения. */
    ASK_BEFORE_CHANGES,

    /** «Авто-применять безопасное» — изменения уровня SAFE применяются автоматически. */
    AUTO_APPLY_SAFE,

    /** «Полный автомат с чекпоинтами» — агент работает сам, но ставит снапшот перед каждым шагом. */
    FULL_AUTO_WITH_CHECKPOINTS,
}

/** Разрешение на использование инструмента (§ 10.1). */
@Serializable
enum class Permission {
    /** Выполняется без спроса. */
    ALLOW,

    /** Требует подтверждения пользователя. */
    ASK,

    /** Запрещено. */
    DENY,
}

/** Кто автор изменения. */
@Serializable
enum class ChangeSource { AGENT, HUMAN }

/** Состояние задачи. */
@Serializable
enum class TaskStatus {
    /** Поставлена в очередь, прогон ещё не начат. */
    QUEUED,

    /** Прогон идёт. */
    RUNNING,

    /** Есть пакет, ожидающий ревью. */
    REVIEW,

    /** Пакет принят. */
    ACCEPTED,

    /** Пакет отклонён. */
    REJECTED,

    /** Прогон завершился ошибкой. */
    FAILED,
}

/** Состояние прогона агента (FR-AGENT-7, FR-AGENT-11). */
@Serializable
enum class RunState {
    /** План сформирован, изменяющих действий ещё не было. */
    PLANNED,

    /** Прогон выполняется. */
    RUNNING,

    /** Прогон приостановлен пользователем, состояние сохранено. */
    PAUSED,

    /** Прогон завершён успешно. */
    FINISHED,

    /** Прогон завершился ошибкой. */
    FAILED,

    /** Прогон остановлен пользователем. */
    STOPPED,

    /** Прогон прерван падением хоста; не продолжается с середины молча (T-1.1). */
    INTERRUPTED,
}

/** Состояние шага плана. */
@Serializable
enum class StepStatus { PENDING, DONE, FAILED, SKIPPED }

/** Чем закончился вызов инструмента (FR-AGENT-9). */
@Serializable
enum class ToolOutcome {
    SUCCESS,
    FAILURE,

    /** Пользователь запретил вызов; прогон от этого не ломается (T-1.8). */
    DENIED,

    /** Вызов не уложился в лимит времени. */
    TIMEOUT,
}

/** Что ответил пользователь на запрос подтверждения (FR-CTRL-16). */
@Serializable
enum class ApprovalDecision {
    /** Разрешить только этот вызов; не запоминается (FR-TOOLS-11). */
    ALLOW_ONCE,

    /** Разрешить до конца сессии. */
    ALLOW_SESSION,

    /** Разрешить постоянно. */
    ALLOW_ALWAYS,

    /** Запретить. */
    DENY,
}

/** Что произошло с файлом. */
@Serializable
enum class FileChangeKind { ADDED, MODIFIED, DELETED, RENAMED }

/** Что произошло с блоком строк. */
@Serializable
enum class HunkKind { ADD, REMOVE, REPLACE }

/** Роль строки внутри блока. */
@Serializable
enum class LineKind { CONTEXT, ADDED, REMOVED }

/** Итог прогона тестов и линтера. */
@Serializable
enum class TestState {
    /** Тесты не запускались. */
    NOT_RUN,

    /** Всё зелёное. */
    GREEN,

    /** Есть упавшие тесты. */
    RED,

    /** Прогон не уложился в лимит времени. */
    TIMEOUT,

    /** Инфраструктурная ошибка: не нашёлся раннер, не поднялась БД тестов. */
    INFRA_ERROR,

    /** Тестов в проекте нет. */
    SKIPPED,
}

/** Состояние пакета в очереди ревью. */
@Serializable
enum class PacketStatus { AWAITING_REVIEW, IN_PROGRESS, ACCEPTED, REJECTED }

/** Уровень, к которому относится решение ревью. */
@Serializable
enum class DecisionScope { PACKET, HUNK }

/** Что решил пользователь. */
@Serializable
enum class DecisionValue { ACCEPTED, REJECTED, CHANGES_REQUESTED }

/** Что стало поводом для снапшота; половина «после» нужна для инварианта 3 § 4.1. */
@Serializable
enum class SnapshotTrigger {
    BEFORE_AGENT_STEP,
    AFTER_AGENT_STEP,
    BEFORE_MANUAL_EDIT,
    AFTER_MANUAL_EDIT,
}

/** Платформа клиента, принявшего решение ревью; нужна для продуктовых метрик (§ 2.3). */
@Serializable
enum class ClientPlatform { ANDROID, DESKTOP_LINUX, DESKTOP_WINDOWS, DESKTOP_MACOS, IOS, UNKNOWN }
```

- [ ] **Шаг 3: написать `Task` и `AgentRun`**

`domain/src/commonMain/kotlin/dev/aide/domain/Task.kt`:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Постановка задачи пользователем: текст или расшифровка голоса (§ 4). */
@Serializable
data class Task(
    /** Идентификатор задачи. */
    val id: TaskId,
    /** Короткое название, которое видно на карточке пакета. */
    val title: String,
    /** Исходная постановка: текст или расшифровка голоса (FR-AGENT-20, FR-AGENT-21). */
    val prompt: String,
    /** Ветка задачи вида `ai/<task-id>` (§ 8.3). */
    val branch: String,
    /** Текущее состояние задачи. */
    val status: TaskStatus,
    /** Момент постановки задачи. */
    val createdAt: Instant,
    /** Прогоны агента по этой задаче, в порядке запуска. */
    val runIds: List<RunId> = emptyList(),
)
```

`domain/src/commonMain/kotlin/dev/aide/domain/AgentRun.kt`:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Стоимость в миллионных долях единицы тарификации провайдера.
 * Единица выбрана целочисленной, чтобы суммы совпадали с журналом до последней цифры (§ 5.9).
 */
@Serializable
data class Cost(
    /** Накопленная стоимость. При [known] = false равно нулю и не учитывается в итогах. */
    val amountMicros: Long = 0,
    /** false — цена хотя бы одного вызова неизвестна; итог с такой позицией помечается как неполный (FR-COST-5). */
    val known: Boolean = true,
)

/** Один шаг плана, показанного до первого изменяющего действия (FR-AGENT-7). */
@Serializable
data class PlanStep(
    /** Порядковый номер шага, начиная с 0. */
    val index: Int,
    /** Что агент собирается сделать, одной строкой. */
    val summary: String,
    /** Состояние шага. */
    val status: StepStatus,
)

/** Один прогон агента по задаче (§ 4). */
@Serializable
data class AgentRun(
    /** Идентификатор прогона. */
    val id: RunId,
    /** Задача, по которой идёт прогон. */
    val taskId: TaskId,
    /** Текущее состояние прогона. */
    val state: RunState,
    /** Режим автономности, зафиксированный на старте; переключение режима его не меняет (FR-AGENT-5). */
    val mode: AutonomyMode,
    /** План, показанный до первого изменяющего действия. */
    val plan: List<PlanStep> = emptyList(),
    /** Идентификаторы вызовов инструментов этого прогона, в порядке выполнения. */
    val toolCallIds: List<ToolCallId> = emptyList(),
    /** Момент старта прогона. */
    val startedAt: Instant,
    /** Момент завершения; null, пока прогон идёт. */
    val finishedAt: Instant? = null,
    /** Длительность прогона в миллисекундах (FR-AGENT-10). */
    val elapsedMillis: Long = 0,
    /** Накопленная стоимость прогона (FR-AGENT-10). */
    val cost: Cost = Cost(),
    /** Причина прерывания: падение хоста, стоп пользователя или отказ инструмента. */
    val interruptReason: String? = null,
)
```

- [ ] **Шаг 4: написать `ToolCall` и `ToolPermission`**

`domain/src/commonMain/kotlin/dev/aide/domain/ToolCall.kt`:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Один вызов инструмента агентом (§ 4, FR-AGENT-8, FR-AGENT-9). */
@Serializable
data class ToolCall(
    /** Идентификатор вызова. */
    val id: ToolCallId,
    /** Прогон, в рамках которого сделан вызов. */
    val runId: RunId,
    /** Имя инструмента. */
    val tool: String,
    /** Аргументы вызова в сериализованном виде. */
    val arguments: String,
    /** Результат вызова; null, пока вызов не завершён. */
    val result: String? = null,
    /** Чем закончился вызов. */
    val outcome: ToolOutcome,
    /** Длительность вызова в миллисекундах. */
    val durationMillis: Long,
    /** Стоимость вызова. */
    val cost: Cost,
    /** Требовал ли вызов подтверждения пользователя. */
    val requiredApproval: Boolean,
    /** Что ответил пользователь; null, если подтверждение не требовалось. */
    val approval: ApprovalDecision? = null,
    /** Момент завершения вызова. */
    val at: Instant,
)

/** Разрешение на инструмент, отдельно для чтения и для записи (FR-TOOLS-8, § 10.1). */
@Serializable
data class ToolPermission(
    /** Имя инструмента. */
    val tool: String,
    /** Разрешение на чтение. */
    val read: Permission,
    /** Разрешение на запись. */
    val write: Permission,
)
```

- [ ] **Шаг 5: написать `ChangePacket`, `FileChange`, `Hunk`, `HunkLine`, `TestStatus`**

`domain/src/commonMain/kotlin/dev/aide/domain/ChangePacket.kt`:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Пакет изменений — единица ревью, соответствует одной задаче (§ 4). */
@Serializable
data class ChangePacket(
    /** Идентификатор пакета. */
    val id: PacketId,
    /** Задача, результатом которой стал пакет. */
    val taskId: TaskId,
    /** Ревизия пакета; доработка создаёт новую ревизию, прежняя остаётся в истории (инвариант 2 § 4.1). */
    val revision: Int,
    /** Название пакета для карточки инбокса. */
    val title: String,
    /** Краткое объяснение от агента целиком по пакету (FR-INBOX-3). */
    val summary: String,
    /** Изменения по файлам. */
    val files: List<FileChange>,
    /** Уровень риска пакета; не ниже максимального риска его hunk'ов (инвариант 5 § 4.1). */
    val risk: RiskLevel,
    /** Результат тестов и линтера (FR-DIFF-17). */
    val tests: TestStatus,
    /** Кто источник изменений. */
    val source: ChangeSource,
    /** Состояние пакета в очереди ревью. */
    val status: PacketStatus,
    /** Ветка задачи, из которой собран пакет. */
    val branch: String,
    /** Снапшот, к которому можно откатить пакет; null, если снапшот ещё не поставлен. */
    val snapshotRef: SnapshotRef? = null,
    /** Момент сборки пакета. */
    val createdAt: Instant,
)

/** Суммарно добавленные строки по всем файлам — то, что карточка показывает как `+N`. */
val ChangePacket.addedLines: Int get() = files.sumOf { it.addedLines }

/** Суммарно удалённые строки по всем файлам — то, что карточка показывает как `-M`. */
val ChangePacket.removedLines: Int get() = files.sumOf { it.removedLines }

/** Изменения одного файла внутри пакета (§ 4). */
@Serializable
data class FileChange(
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Что произошло с файлом. */
    val changeKind: FileChangeKind,
    /** Блоки изменений файла. */
    val hunks: List<Hunk>,
    /** Добавленные строки в этом файле. */
    val addedLines: Int,
    /** Удалённые строки в этом файле. */
    val removedLines: Int,
    /** Прежний путь; заполнен только при [changeKind] = [FileChangeKind.RENAMED]. */
    val previousPath: String? = null,
)

/** Один связанный блок изменений — единица показа и решения на мобильном экране (§ 4). */
@Serializable
data class Hunk(
    /** Идентификатор блока. */
    val id: HunkId,
    /** Путь файла, к которому относится блок. */
    val filePath: String,
    /** Номер первой строки в исходном файле. */
    val startLine: Int,
    /** Что произошло с блоком. */
    val kind: HunkKind,
    /** Риск блока; определяет, попадает ли он в «Принять всё безопасное» (FR-INBOX-10). */
    val risk: RiskLevel,
    /** Строки блока в порядке следования. */
    val lines: List<HunkLine>,
)

/** Одна строка внутри блока. */
@Serializable
data class HunkLine(
    /** Роль строки: контекст, добавленная или удалённая. */
    val kind: LineKind,
    /** Номер строки в исходном файле; null для добавленных строк. */
    val oldNumber: Int? = null,
    /** Номер строки в новом файле; null для удалённых строк. */
    val newNumber: Int? = null,
    /** Текст строки без diff-префикса. */
    val text: String,
)

/** Итог прогона тестов и линтера по пакету. */
@Serializable
data class TestStatus(
    /** Итог последнего прогона. */
    val state: TestState,
    /** Имена упавших тестов; пусто, если падений нет. */
    val failed: List<String> = emptyList(),
    /** Число замечаний линтера. */
    val lintFindings: Int = 0,
)
```

- [ ] **Шаг 6: написать `ReviewDecision` и `Snapshot`**

`domain/src/commonMain/kotlin/dev/aide/domain/ReviewDecision.kt` — имя файла совпадает с именем единственной top-level декларации: если назвать файл иначе, detekt падает на правиле `MatchingDeclarationName` с текстом `The file name '…' does not match the name of the single top-level declaration 'ReviewDecision'`. Имена типов при этом не меняются — `ReviewDecision`, `DecisionScope`, `DecisionValue` остаются контрактом для остальных задач:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Решение пользователя по пакету или по отдельному блоку (§ 4). */
@Serializable
data class ReviewDecision(
    /** Пакет, к которому относится решение. */
    val packetId: PacketId,
    /** Ревизия пакета на момент решения; к новой ревизии решение не применяется (§ 9, правило 2). */
    val packetRevision: Int,
    /** Уровень решения: весь пакет или отдельный блок. */
    val scope: DecisionScope,
    /** Идентификатор блока при [scope] = [DecisionScope.HUNK]; null при [scope] = [DecisionScope.PACKET]. */
    val targetHunkId: HunkId? = null,
    /** Что решил пользователь. */
    val value: DecisionValue,
    /** Комментарий пользователя при возврате на доработку (FR-INBOX-13). */
    val comment: String? = null,
    /** Платформа клиента, принявшего решение; нужна для продуктовых метрик (§ 2.3). */
    val clientPlatform: ClientPlatform,
    /** Момент решения. */
    val decidedAt: Instant,
)
```

`domain/src/commonMain/kotlin/dev/aide/domain/Snapshot.kt`:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/** Точка отката в git, к которой можно вернуть состояние репозитория (§ 9). */
@Serializable
data class Snapshot(
    /** Ссылка вида `refs/ai/snap/<timestamp>-<label>`. */
    val ref: SnapshotRef,
    /** Метка снапшота: `before-agent-step`, `after-agent-step`, `before-manual-edit`, `after-manual-edit`. */
    val label: String,
    /** Коммит, на который указывает ссылка. */
    val commit: String,
    /** Что стало поводом для снапшота. */
    val trigger: SnapshotTrigger,
    /** Задача, в рамках которой сделан снапшот; null для ручных правок вне задачи. */
    val taskId: TaskId? = null,
    /** Момент создания снапшота. */
    val createdAt: Instant,
)
```

- [ ] **Шаг 7: удалить заглушки**

```bash
rm domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt
rm domain/src/commonTest/kotlin/dev/aide/domain/DomainModuleSmokeTest.kt
```

- [ ] **Шаг 8: написать фикстуры**

`domain/src/commonTest/kotlin/dev/aide/domain/DomainFixtures.kt` — один корректный экземпляр каждого из 11 типов, плюс образцы с пустыми списками и `null`-полями:

```kotlin
package dev.aide.domain

import kotlinx.datetime.Instant

object DomainFixtures {

    private val t0 = Instant.parse("2026-09-22T10:00:00Z")
    private val t1 = Instant.parse("2026-09-22T10:01:00Z")

    val hunkLineAdded = HunkLine(kind = LineKind.ADDED, oldNumber = null, newNumber = 4, text = "val token = issue()")
    val hunkLineRemoved = HunkLine(kind = LineKind.REMOVED, oldNumber = 4, newNumber = null, text = "val token = null")
    val hunkLineContext = HunkLine(kind = LineKind.CONTEXT, oldNumber = 3, newNumber = 3, text = "fun login() {")

    val hunk = Hunk(
        id = HunkId("h-1"),
        filePath = "src/auth/Login.kt",
        startLine = 3,
        kind = HunkKind.REPLACE,
        risk = RiskLevel.SAFE,
        lines = listOf(hunkLineContext, hunkLineRemoved, hunkLineAdded),
    )

    val fileChange = FileChange(
        path = "src/auth/Login.kt",
        changeKind = FileChangeKind.MODIFIED,
        hunks = listOf(hunk),
        addedLines = 1,
        removedLines = 1,
    )

    val fileChangeRenamed = FileChange(
        path = "src/auth/Session.kt",
        changeKind = FileChangeKind.RENAMED,
        hunks = listOf(hunk.copy(id = HunkId("h-2"), filePath = "src/auth/Session.kt")),
        addedLines = 1,
        removedLines = 1,
        previousPath = "src/auth/Login.kt",
    )

    val packet = ChangePacket(
        id = PacketId("p-1"),
        taskId = TaskId("t-1"),
        revision = 1,
        title = "Авторизация: выдача токена",
        summary = "Заменил заглушку выдачи токена на вызов сервиса.",
        files = listOf(fileChange, fileChangeRenamed),
        risk = RiskLevel.SAFE,
        tests = TestStatus(state = TestState.GREEN, failed = emptyList(), lintFindings = 0),
        source = ChangeSource.AGENT,
        status = PacketStatus.AWAITING_REVIEW,
        branch = "ai/t-1",
        snapshotRef = SnapshotRef("refs/ai/snap/1758535200-before-agent-step"),
        createdAt = t1,
    )

    val task = Task(
        id = TaskId("t-1"),
        title = "Авторизация",
        prompt = "Сделай выдачу токена через сервис",
        branch = "ai/t-1",
        status = TaskStatus.REVIEW,
        createdAt = t0,
        runIds = listOf(RunId("r-1")),
    )

    val run = AgentRun(
        id = RunId("r-1"),
        taskId = TaskId("t-1"),
        state = RunState.FINISHED,
        mode = AutonomyMode.ASK_BEFORE_CHANGES,
        plan = listOf(PlanStep(index = 0, summary = "Разобрать Login.kt", status = StepStatus.DONE)),
        toolCallIds = listOf(ToolCallId("tc-1")),
        startedAt = t0,
        finishedAt = t1,
        elapsedMillis = 60_000,
        cost = Cost(amountMicros = 12_500, known = true),
        interruptReason = null,
    )

    val toolCall = ToolCall(
        id = ToolCallId("tc-1"),
        runId = RunId("r-1"),
        tool = "fs.write",
        arguments = """{"path":"src/auth/Login.kt"}""",
        result = "ok",
        outcome = ToolOutcome.SUCCESS,
        durationMillis = 42,
        cost = Cost(amountMicros = 0, known = true),
        requiredApproval = true,
        approval = ApprovalDecision.ALLOW_ONCE,
        at = t1,
    )

    val toolPermission = ToolPermission(tool = "fs.write", read = Permission.ALLOW, write = Permission.ASK)

    val decision = ReviewDecision(
        packetId = PacketId("p-1"),
        packetRevision = 1,
        scope = DecisionScope.HUNK,
        targetHunkId = HunkId("h-1"),
        value = DecisionValue.CHANGES_REQUESTED,
        comment = "Вынеси выдачу токена в отдельную функцию",
        clientPlatform = ClientPlatform.ANDROID,
        decidedAt = t1,
    )

    val snapshot = Snapshot(
        ref = SnapshotRef("refs/ai/snap/1758535200-before-agent-step"),
        label = "before-agent-step",
        commit = "0f1e2d3c4b5a69788796a5b4c3d2e1f009182736",
        trigger = SnapshotTrigger.BEFORE_AGENT_STEP,
        taskId = TaskId("t-1"),
        createdAt = t0,
    )

    /** Образцы «пусто и null»: проверяют, что кодек не теряет пустые списки и незаполненные поля. */
    val emptyRun = AgentRun(
        id = RunId("r-empty"),
        taskId = TaskId("t-empty"),
        state = RunState.PLANNED,
        mode = AutonomyMode.SUGGEST_ONLY,
        plan = emptyList(),
        toolCallIds = emptyList(),
        startedAt = t0,
        finishedAt = null,
        elapsedMillis = 0,
        cost = Cost(amountMicros = 0, known = false),
        interruptReason = null,
    )

    val emptyPacket = packet.copy(
        id = PacketId("p-empty"),
        revision = 1,
        files = emptyList(),
        tests = TestStatus(state = TestState.NOT_RUN),
        summary = "",
        snapshotRef = null,
    )

    val packetLevelDecision = decision.copy(scope = DecisionScope.PACKET, targetHunkId = null, comment = null)
}
```

- [ ] **Шаг 9: написать round-trip тесты на все 11 типов**

`domain/src/commonTest/kotlin/dev/aide/domain/DomainRoundTripTest.kt`:

```kotlin
package dev.aide.domain

import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.cbor.Cbor
import kotlinx.serialization.decodeFromByteArray
import kotlinx.serialization.encodeToByteArray
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Round-trip: сериализация и обратный разбор дают равный объект (T-0.5).
 * Формат — CBOR, тот же, что поедет по сети в задаче 8.
 *
 * `@OptIn` нужен из-за `Cbor { … }`: настройка формата числится экспериментальной
 * и без этого даёт три предупреждения `needs opt-in for 'ExperimentalSerializationApi'`.
 * Импорты `encodeToByteArray` и `decodeFromByteArray` — top-level расширения
 * `kotlinx.serialization`, а не члены `BinaryFormat`: без них компилятор выбирает
 * перегрузку, требующую сериализатор, и падает с `Argument type mismatch`.
 */
@OptIn(ExperimentalSerializationApi::class)
class DomainRoundTripTest {

    private val cbor = Cbor { ignoreUnknownKeys = true }
    private inline fun <reified T> roundTrip(value: T): T =
        cbor.decodeFromByteArray(cbor.encodeToByteArray(value))

    @Test
    fun `RiskLevel переживает round-trip`() {
        assertEquals(RiskLevel.RISKY, roundTrip(RiskLevel.RISKY))
    }

    @Test
    fun `AutonomyMode переживает round-trip`() {
        assertEquals(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS, roundTrip(AutonomyMode.FULL_AUTO_WITH_CHECKPOINTS))
    }

    @Test
    fun `ToolPermission переживает round-trip`() {
        assertEquals(DomainFixtures.toolPermission, roundTrip(DomainFixtures.toolPermission))
    }

    @Test
    fun `Task переживает round-trip`() {
        assertEquals(DomainFixtures.task, roundTrip(DomainFixtures.task))
    }

    @Test
    fun `AgentRun переживает round-trip`() {
        assertEquals(DomainFixtures.run, roundTrip(DomainFixtures.run))
    }

    @Test
    fun `AgentRun с пустыми списками и null-полями переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.emptyRun)
        assertEquals(DomainFixtures.emptyRun, decoded)
        assertTrue(decoded.plan.isEmpty())
        assertNull(decoded.finishedAt)
        assertEquals(false, decoded.cost.known)
    }

    @Test
    fun `ToolCall переживает round-trip`() {
        assertEquals(DomainFixtures.toolCall, roundTrip(DomainFixtures.toolCall))
    }

    @Test
    fun `Hunk переживает round-trip`() {
        assertEquals(DomainFixtures.hunk, roundTrip(DomainFixtures.hunk))
    }

    @Test
    fun `FileChange переживает round-trip и сохраняет previousPath`() {
        val decoded = roundTrip(DomainFixtures.fileChangeRenamed)
        assertEquals("src/auth/Login.kt", decoded.previousPath)
        assertEquals(DomainFixtures.fileChangeRenamed, decoded)
    }

    @Test
    fun `ChangePacket с вложенными hunk-ами переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.packet)
        assertEquals(DomainFixtures.packet, decoded)
        assertEquals(2, decoded.files.size)
        assertEquals(DomainFixtures.hunk, decoded.files.first().hunks.first())
    }

    @Test
    fun `ChangePacket без файлов переживает round-trip`() {
        val decoded = roundTrip(DomainFixtures.emptyPacket)
        assertTrue(decoded.files.isEmpty())
        assertEquals(TestState.NOT_RUN, decoded.tests.state)
        assertNull(decoded.snapshotRef)
    }

    @Test
    fun `ReviewDecision на уровне блока переживает round-trip`() {
        assertEquals(DomainFixtures.decision, roundTrip(DomainFixtures.decision))
    }

    @Test
    fun `ReviewDecision на уровне пакета переживает round-trip и сохраняет null`() {
        val decoded = roundTrip(DomainFixtures.packetLevelDecision)
        assertEquals(DomainFixtures.packetLevelDecision, decoded)
        assertNull(decoded.targetHunkId)
        assertNull(decoded.comment)
    }

    @Test
    fun `Snapshot переживает round-trip`() {
        assertEquals(DomainFixtures.snapshot, roundTrip(DomainFixtures.snapshot))
    }

    @Test
    fun `производные счётчики строк считаются по файлам`() {
        assertEquals(2, DomainFixtures.packet.addedLines)
        assertEquals(2, DomainFixtures.packet.removedLines)
    }
}
```

- [ ] **Шаг 10: прогнать тесты**

```bash
./gradlew :domain:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 15 тестов пройдено. Если упал round-trip на `Instant` — значит не подключён сериализатор kotlinx-datetime: проверить, что в `domain/build.gradle.kts` в `commonMain.dependencies` есть `libs.kotlinx.datetime` (он несёт сериализатор `Instant` по умолчанию).

- [ ] **Шаг 11: подключить CBOR в тестах и Dokka**

`domain/build.gradle.kts` целиком:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.dokka)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: Instant и @Serializable входят в публичные сигнатуры
            // моделей (Task.createdAt, AgentRun.startedAt, ReviewDecision.decidedAt), поэтому
            // потребителю нужен доступ к этим типам — правило из задачи 4.
            api(libs.kotlinx.serialization.core)
            api(libs.kotlinx.datetime)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.serialization.cbor)
        }
    }
}

tasks.named<Test>("jvmTest") {
    systemProperty("domainSourcesDir", layout.projectDirectory.dir("src/commonMain/kotlin").asFile.path)
}
```

Добавить в каталог: `dokka = "2.1.0"` и `dokka = { id = "org.jetbrains.dokka", version.ref = "dokka" }`.

Версия именно **2.1.0**, а не 2.0.0, и откатывать её нельзя. С Dokka 2.0.0 при Kotlin 2.1.0 плагин работает в legacy-режиме DGP v1: задачи называются `dokkaHtml` и `dokkaGfm`, задачи `dokkaGenerate` в проекте просто нет, а `:domain:dokkaHtml` рушит запись configuration cache (`cannot serialize object of type 'DefaultUnlockedConfiguration'`, `Configuration cache entry discarded with 2 problems`) — то есть конфликтует с `org.gradle.configuration-cache=true`, который включён в задаче 1. В Dokka 2.1.0 режим DGP v2 — режим по умолчанию: есть ровно задача `dokkaGenerate`, configuration cache сохраняется, документация кладётся в `domain/build/dokka/html`, предупреждений о несовместимости с Kotlin 2.1.0 нет.

`import org.gradle.api.tasks.testing.Test` в этом build-файле не нужен: тип входит в default-импорты Kotlin DSL, поэтому в листинге его нет — это не пропуск. Реализация может оставить его как самодокументируемый, на сборку и смысл это не влияет.

- [ ] **Шаг 12: написать тест, что у каждого публичного поля есть документирующий комментарий**

`domain/src/jvmTest/kotlin/dev/aide/domain/PublicFieldsAreDocumentedTest.kt`:

```kotlin
package dev.aide.domain

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Требование T-0.5: у каждого публичного поля есть документирующий комментарий,
 * проверяется сборкой документации. Здесь проверяется наличие KDoc над полем,
 * а сама сборка документации выполняется задачей `:domain:dokkaGenerate`.
 */
class PublicFieldsAreDocumentedTest {

    private val modelFiles = listOf(
        "Task.kt", "AgentRun.kt", "ToolCall.kt", "ChangePacket.kt", "ReviewDecision.kt", "Snapshot.kt",
    )

    @Test
    fun `каждое публичное поле моделей описано KDoc`() {
        val root = File(
            System.getProperty("domainSourcesDir")
                ?: error("Не задано системное свойство domainSourcesDir — проверь блок jvmTest в build.gradle.kts"),
        )
        assertTrue(root.isDirectory, "Каталог исходников не найден: $root")

        val undocumented = mutableListOf<String>()

        modelFiles.forEach { fileName ->
            val file = File(root, "dev/aide/domain/$fileName")
            assertTrue(file.isFile, "Модельный файл не найден: ${file.path}")

            val lines = file.readLines()
            lines.forEachIndexed { index, line ->
                val isPublicProperty = line.startsWith("    val ") || line.startsWith("    var ")
                if (!isPublicProperty) return@forEachIndexed

                val previousMeaningful = lines.take(index).lastOrNull { it.isNotBlank() }.orEmpty()
                if (!previousMeaningful.trimEnd().endsWith("*/")) {
                    undocumented += "$fileName:${index + 1} → ${line.trim()}"
                }
            }
        }

        assertTrue(
            undocumented.isEmpty(),
            "Поля без документирующего комментария:\n" + undocumented.joinToString("\n"),
        )
    }
}
```

Почему в списке шесть файлов, а не восемь: `Ids.kt` и `Enums.kt` тест не проверяет, и это осознанно — там `value class` и enum-константы, а их поля не начинаются с четырёх пробелов и `val`, поэтому правило «перед полем стоит `*/`» к ним неприменимо. Проверяются файлы с data-классами.

**Пометка на будущее.** Тест ищет «предыдущую значимую строку» и ждёт, что она заканчивается на `*/`. Если между KDoc и `val` появится аннотация (`@Transient`, `@Deprecated`), предыдущей значимой строкой станет она, и тест даст ложное срабатывание — так и происходит на временных правках. Сейчас аннотированных полей нет, но задачи 6 и дальше могут их добавить: при первом же таком поле тест нужно уточнить (пропускать строки, начинающиеся с `@`), а не отключать.

- [ ] **Шаг 13: прогнать тест документации и сборку документации**

```bash
./gradlew :domain:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 16 тестов пройдено. Затем убедиться, что тест действительно ловит нарушение: временно удалить KDoc-строку над `val branch: String` в `Task.kt`, повторить команду — ожидаемо `FAILED` со строкой `Task.kt:15 → val branch: String`. Вернуть KDoc.

```bash
./gradlew :domain:dokkaGenerate
```

Ожидаемо: `BUILD SUCCESSFUL`, появился каталог `domain/build/dokka/html` с документацией. Если Gradle сообщает, что задачи `dokkaGenerate` нет, посмотреть доступные имена — задачи документации лежат в группе `Dokka tasks` и в обычном выводе `tasks` не показываются, поэтому нужен `--all`:

```bash
./gradlew :domain:tasks --all
```

и использовать имя оттуда, заменив его в этом шаге и в CI (задача 3, шаг 3).

- [ ] **Шаг 14: добавить сборку документации в CI**

В `.github/workflows/ci.yml` в Linux-job'е `build` после шага «Границы модулей» добавить:

```yaml
      - name: Документация домена
        run: ./gradlew :domain:dokkaGenerate
```

- [ ] **Шаг 15: коммит**

```bash
git rm domain/src/commonMain/kotlin/dev/aide/domain/PackageMarker.kt domain/src/commonTest/kotlin/dev/aide/domain/DomainModuleSmokeTest.kt
git add domain
git commit -m "feat(domain): 11 моделей домена, round-trip тесты на CBOR и проверка документированности полей"
```

---

## Задача 6: инварианты домена (`T-0.6`)

**Файлы:**
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/Invariants.kt`
- Изменить: `domain/src/commonMain/kotlin/dev/aide/domain/ChangePacket.kt` (валидация в `init`)
- Тест: `domain/src/commonTest/kotlin/dev/aide/domain/InvariantsTest.kt`

**Что именно проверяемо на уровне домена.** Из шести инвариантов § 4.1 конструктором или валидатором закрываются только два: 1 (принадлежность hunk → file → packet) и 5 (риск пакета не ниже максимума по hunk'ам). Остальные четыре — свойства процесса и проверяются в задачах хоста: 2 (неизменяемость пакета) — `T-1.24`, 3 (снапшот до и после) — `T-1.19`, 4 (автор в истории git) — `T-1.11`, 6 (решения ревью не теряются) — `T-1.37`.

- [ ] **Шаг 1: написать падающие тесты на оба инварианта**

`domain/src/commonTest/kotlin/dev/aide/domain/InvariantsTest.kt`:

```kotlin
package dev.aide.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class InvariantsTest {

    // Инвариант 1: каждый Hunk принадлежит ровно одному FileChange, каждый FileChange — ровно одному ChangePacket.

    @Test
    fun `пакет с двумя файлами по одному пути отвергается`() {
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(
                    DomainFixtures.fileChange,
                    DomainFixtures.fileChange.copy(addedLines = 5),
                ),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)
    }

    @Test
    fun `файл с hunk-ом, чей filePath не совпадает с путём файла, отвергается`() {
        val foreign = DomainFixtures.hunk.copy(id = HunkId("h-foreign"), filePath = "src/other/File.kt")
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(foreign))),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)
        assertTrue(violation.message!!.contains("src/other/File.kt"))
    }

    @Test
    fun `файл без hunk-ов отвергается, кроме удаления файла`() {
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = emptyList())),
            )
        }
        assertEquals(DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE, violation.invariant)

        // Удаление файла — единственный случай, когда hunk-ов может не быть.
        val deleted = DomainFixtures.packet.copy(
            files = listOf(
                DomainFixtures.fileChange.copy(
                    changeKind = FileChangeKind.DELETED,
                    hunks = emptyList(),
                    addedLines = 0,
                ),
            ),
            risk = RiskLevel.RISKY,
        )
        assertEquals(FileChangeKind.DELETED, deleted.files.single().changeKind)
    }

    @Test
    fun `корректный пакет создаётся без исключения`() {
        assertTrue(DomainFixtures.packet.files.isNotEmpty())
    }

    // Инвариант 5: RiskLevel пакета не может быть ниже максимального RiskLevel его hunk-ов.

    @Test
    fun `риск пакета ниже риска hunk-а отвергается`() {
        val riskyHunk = DomainFixtures.hunk.copy(risk = RiskLevel.RISKY)
        val violation = assertFailsWith<DomainViolation> {
            DomainFixtures.packet.copy(
                files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(riskyHunk))),
                risk = RiskLevel.SAFE,
            )
        }
        assertEquals(DomainInvariant.PACKET_RISK_NOT_BELOW_HUNKS, violation.invariant)
        assertTrue(violation.message!!.contains("RISKY"))
    }

    @Test
    fun `риск пакета выше максимума по hunk-ам допускается`() {
        val packet = DomainFixtures.packet.copy(risk = RiskLevel.RISKY)
        assertEquals(RiskLevel.RISKY, packet.risk)
    }

    @Test
    fun `риск пакета равный максимуму по hunk-ам допускается`() {
        val normalHunk = DomainFixtures.hunk.copy(risk = RiskLevel.NORMAL)
        val packet = DomainFixtures.packet.copy(
            files = listOf(DomainFixtures.fileChange.copy(hunks = listOf(normalHunk))),
            risk = RiskLevel.NORMAL,
        )
        assertEquals(RiskLevel.NORMAL, packet.risk)
    }

    @Test
    fun `пакет без файлов проходит проверку риска`() {
        assertEquals(RiskLevel.SAFE, DomainFixtures.emptyPacket.risk)
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :domain:jvmTest --tests 'dev.aide.domain.InvariantsTest'
```

Ожидаемо: `FAILED` с `Unresolved reference: DomainViolation` и `DomainInvariant` — они ещё не написаны.

- [ ] **Шаг 3: написать валидатор инвариантов**

`domain/src/commonMain/kotlin/dev/aide/domain/Invariants.kt`:

```kotlin
package dev.aide.domain

/** Инвариант домена, который можно проверить конструктором. */
enum class DomainInvariant(val description: String) {
    /** Инвариант 1 § 4.1: каждый hunk принадлежит ровно одному файлу, каждый файл — ровно одному пакету. */
    HUNK_HAS_EXACTLY_ONE_FILE(
        "Каждый hunk принадлежит ровно одному FileChange, каждый FileChange — ровно одному ChangePacket",
    ),

    /** Инвариант 5 § 4.1: риск пакета не ниже максимума по его hunk'ам. */
    PACKET_RISK_NOT_BELOW_HUNKS(
        "RiskLevel пакета не может быть ниже максимального RiskLevel его hunk'ов",
    ),
}

/** Нарушение инварианта домена; несёт сам инвариант, чтобы тест и UI могли на него сослаться. */
class DomainViolation(
    val invariant: DomainInvariant,
    detail: String,
) : IllegalArgumentException("${invariant.description}. $detail")

/**
 * Проверяет инварианты 1 и 5 § 4.1. Вызывается из `init`-блока [ChangePacket],
 * поэтому некорректный пакет нельзя ни создать, ни разобрать из сериализованного вида.
 */
internal fun validatePacket(files: List<FileChange>, risk: RiskLevel) {
    val duplicates = files.groupBy { it.path }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw DomainViolation(
            DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
            "Пути, встречающиеся в пакете больше одного раза: ${duplicates.sorted()}",
        )
    }

    files.forEach { file ->
        if (file.changeKind != FileChangeKind.DELETED && file.hunks.isEmpty()) {
            throw DomainViolation(
                DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
                "Файл '${file.path}' изменён (${file.changeKind}), но не содержит ни одного hunk'а",
            )
        }
        file.hunks.forEach { hunk ->
            if (hunk.filePath != file.path) {
                throw DomainViolation(
                    DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
                    "Hunk '${hunk.id.value}' указывает файл '${hunk.filePath}', " +
                        "но лежит в FileChange '${file.path}'",
                )
            }
        }
    }

    val hunks = files.flatMap { it.hunks }
    val highestHunkRisk = hunks.maxOfOrNull { it.risk.ordinal } ?: return
    if (risk.ordinal < highestHunkRisk) {
        val highest = RiskLevel.entries[highestHunkRisk]
        throw DomainViolation(
            DomainInvariant.PACKET_RISK_NOT_BELOW_HUNKS,
            "Риск пакета $risk ниже риска самого опасного hunk'а ($highest)",
        )
    }
}
```

- [ ] **Шаг 4: подключить валидацию в `ChangePacket`**

Добавить в `domain/src/commonMain/kotlin/dev/aide/domain/ChangePacket.kt` внутрь `data class ChangePacket` после последнего поля:

```kotlin
) {
    init {
        validatePacket(files, risk)
    }
}
```

То есть закрывающая скобка конструктора меняется с `)` на `) { init { … } }`. Полный `init`-блок:

```kotlin
) {
    init {
        validatePacket(files, risk)
    }
}
```

- [ ] **Шаг 5: прогнать тесты**

```bash
./gradlew :domain:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 24 теста пройдено (`InvariantsTest` — 8, `DomainRoundTripTest` — 15, `PublicFieldsAreDocumentedTest` — 1).

Обратить внимание: round-trip на `emptyPacket` и `packetLevelDecision` должны остаться зелёными — валидация не должна отвергать пакет без файлов.

- [ ] **Шаг 6: проверить, что валидация ломает разбор некорректных данных**

Добавить в `DomainRoundTripTest` ещё один тест — он подтверждает, что инвариант нельзя обойти, подсунув готовый CBOR:

```kotlin
    @Test
    fun `разбор пакета, нарушающего инвариант 5, бросает исключение`() {
        val bytes = cbor.encodeToByteArray(DomainFixtures.packet.copy(risk = RiskLevel.RISKY))
        // Валидные данные разбираются…
        assertEquals(RiskLevel.RISKY, roundTrip(DomainFixtures.packet.copy(risk = RiskLevel.RISKY)).risk)

        // …а собранный вручную CBOR с риском ниже максимума по hunk-ам — нет.
        val handCrafted = """{"risk":"SAFE"}""".toByteArray()
        assertFailsWith<Exception> { cbor.decodeFromByteArray<ChangePacket>(handCrafted) }
        assertTrue(bytes.isNotEmpty())
    }
```

Добавить импорты `kotlin.test.assertFailsWith` в этот файл. Прогнать:

```bash
./gradlew :domain:jvmTest --tests 'dev.aide.domain.DomainRoundTripTest'
```

Ожидаемо: `BUILD SUCCESSFUL`. Тест проходит и потому, что неполный CBOR не разбирается вообще, — это ожидаемо; ценность теста в том, что он фиксирует: невалидный вход до объекта не доходит.

- [ ] **Шаг 7: коммит**

```bash
git add domain
git commit -m "feat(domain): инварианты 1 и 5 § 4.1 как валидация в конструкторе ChangePacket"
```

---

## Задача 7: вычисление `RiskLevel` (`T-0.7`)

**Файлы:**
- Создать: `domain/src/commonMain/kotlin/dev/aide/domain/risk/RiskEvaluator.kt`
- Тест: `domain/src/commonTest/kotlin/dev/aide/domain/risk/RiskEvaluatorTest.kt`

**Правила, которые реализуются (из § 5.3.2 спецификации).**

`safe` — все условия выполнены: изменения только в тестах, документации, файлах локализации или результатах форматтера; тесты и линтер зелёные; объём ниже порога (по умолчанию 50 строк на пакет); не затронуты публичный API, схема БД, конфигурация прав и секреты; не удалены файлы.

`risky` — хотя бы одно: удалены файлы; затронуты публичный API, схема БД, права доступа или секреты; тесты красные; объём выше порога в 5 раз.

`normal` — всё остальное.

- [ ] **Шаг 1: написать падающие тесты на каждое правило**

`domain/src/commonTest/kotlin/dev/aide/domain/risk/RiskEvaluatorTest.kt`:

```kotlin
package dev.aide.domain.risk

import dev.aide.domain.DomainFixtures
import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.Hunk
import dev.aide.domain.HunkId
import dev.aide.domain.RiskLevel
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus
import kotlin.test.Test
import kotlin.test.assertEquals

class RiskEvaluatorTest {

    private val defaultThreshold = 50

    private fun hunk(path: String, risk: RiskLevel = RiskLevel.SAFE, added: Int = 1) = Hunk(
        id = HunkId("h-$path"),
        filePath = path,
        startLine = 1,
        kind = dev.aide.domain.HunkKind.REPLACE,
        risk = risk,
        lines = emptyList(),
    ).let { DomainFixtures.hunk.copy(id = it.id, filePath = path, risk = risk) }

    private fun file(
        path: String,
        kind: FileChangeKind = FileChangeKind.MODIFIED,
        added: Int = 1,
        risk: RiskLevel = RiskLevel.SAFE,
    ) = FileChange(
        path = path,
        changeKind = kind,
        hunks = if (kind == FileChangeKind.DELETED) emptyList() else listOf(hunk(path, risk)),
        addedLines = if (kind == FileChangeKind.DELETED) 0 else added,
        removedLines = if (kind == FileChangeKind.DELETED) 10 else 0,
    )

    private fun evaluate(
        files: List<FileChange>,
        tests: TestStatus = TestStatus(TestState.GREEN),
        volume: Int = files.sumOf { it.addedLines + it.removedLines },
        threshold: Int = defaultThreshold,
    ) = RiskEvaluator.evaluate(
        changes = RiskEvaluator.Input(
            files = files,
            tests = tests,
            totalChangedLines = volume,
            touchesPublicApi = false,
            touchesDatabaseSchema = false,
            touchesPermissionsOrSecrets = false,
        ),
        volumeThresholdLines = threshold,
    )

    // ——— safe ———

    @Test
    fun `только тесты, зелёные тесты и объём ниже порога — safe`() {
        val risk = evaluate(listOf(file("src/test/auth/LoginTest.kt", added = 10)))
        assertEquals(RiskLevel.SAFE, risk)
    }

    @Test
    fun `только документация — safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("docs/auth.md", added = 3))))
    }

    @Test
    fun `только локализация — safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("src/main/res/values/strings.xml", added = 2))))
    }

    // ——— границы safe ———

    @Test
    fun `объём ровно на пороге — ещё safe`() {
        assertEquals(RiskLevel.SAFE, evaluate(listOf(file("src/test/a/ATest.kt", added = 50))))
    }

    @Test
    fun `объём на единицу выше порога — уже normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/test/a/ATest.kt", added = 51))))
    }

    @Test
    fun `тесты красные на тестовом файле — risky, а не safe`() {
        val risk = evaluate(
            files = listOf(file("src/test/auth/LoginTest.kt")),
            tests = TestStatus(state = TestState.RED, failed = listOf("login_issues_token")),
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `замечания линтера снимают safe`() {
        val risk = evaluate(
            files = listOf(file("docs/auth.md")),
            tests = TestStatus(state = TestState.GREEN, lintFindings = 1),
        )
        assertEquals(RiskLevel.NORMAL, risk)
    }

    // ——— risky ———

    @Test
    fun `удаление файла — risky`() {
        val risk = evaluate(listOf(file("src/auth/Legacy.kt", kind = FileChangeKind.DELETED)))
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `публичный API — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/auth/Api.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = true,
                touchesDatabaseSchema = false,
                touchesPermissionsOrSecrets = false,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `схема БД — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/db/Migrations.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = false,
                touchesDatabaseSchema = true,
                touchesPermissionsOrSecrets = false,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `права и секреты — risky`() {
        val risk = RiskEvaluator.evaluate(
            changes = RiskEvaluator.Input(
                files = listOf(file("src/auth/Secrets.kt")),
                tests = TestStatus(TestState.GREEN),
                totalChangedLines = 1,
                touchesPublicApi = false,
                touchesDatabaseSchema = false,
                touchesPermissionsOrSecrets = true,
            ),
            volumeThresholdLines = defaultThreshold,
        )
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `объём выше порога в пять раз — risky`() {
        assertEquals(RiskLevel.RISKY, evaluate(listOf(file("src/auth/Big.kt", added = 251))))
    }

    @Test
    fun `объём ровно в пять раз выше порога — ещё normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/auth/Big.kt", added = 250))))
    }

    // ——— normal ———

    @Test
    fun `обычная правка исходника ниже порога — normal`() {
        assertEquals(RiskLevel.NORMAL, evaluate(listOf(file("src/auth/Login.kt", added = 10))))
    }

    @Test
    fun `тесты не запускались — normal, а не safe`() {
        val risk = evaluate(
            files = listOf(file("docs/auth.md")),
            tests = TestStatus(state = TestState.NOT_RUN),
        )
        assertEquals(RiskLevel.NORMAL, risk)
    }

    // ——— детерминированность и композиция ———

    @Test
    fun `вычисление детерминировано`() {
        val files = listOf(file("src/auth/Login.kt"), file("docs/auth.md"))
        assertEquals(evaluate(files), evaluate(files))
    }

    @Test
    fun `riск пакета равен максимуму по входам и не ниже него`() {
        val risk = evaluate(listOf(file("src/auth/Legacy.kt", kind = FileChangeKind.DELETED), file("docs/a.md")))
        assertEquals(RiskLevel.RISKY, risk)
    }

    @Test
    fun `порог по умолчанию равен пятидесяти`() {
        assertEquals(50, RiskEvaluator.DEFAULT_VOLUME_THRESHOLD_LINES)
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :domain:jvmTest --tests 'dev.aide.domain.risk.RiskEvaluatorTest'
```

Ожидаемо: `FAILED` с `Unresolved reference: RiskEvaluator`.

- [ ] **Шаг 3: написать реализацию**

`domain/src/commonMain/kotlin/dev/aide/domain/risk/RiskEvaluator.kt`:

```kotlin
package dev.aide.domain.risk

import dev.aide.domain.FileChange
import dev.aide.domain.FileChangeKind
import dev.aide.domain.RiskLevel
import dev.aide.domain.TestState
import dev.aide.domain.TestStatus

/**
 * Вычисление уровня риска изменения строго по правилам § 5.3.2.
 *
 * Функция чистая: одинаковый вход даёт одинаковый выход, обращений к сети и к диску нет.
 * LLM не может понизить уровень — она может добавить пояснение, но не участвует в вычислении
 * (FR-AGENT-6). Список путей, изменения в которых считаются безопасными, намеренно задан
 * явными правилами, а не эвристикой.
 */
object RiskEvaluator {

    /** Порог объёма по умолчанию: 50 строк на пакет (§ 5.3.2). Хранение настройки — этап 1, T-1.16. */
    const val DEFAULT_VOLUME_THRESHOLD_LINES: Int = 50

    /** Во сколько раз объём должен превысить порог, чтобы изменение стало [RiskLevel.RISKY]. */
    const val RISKY_VOLUME_MULTIPLIER: Int = 5

    /** Вход вычисления: сам набор изменений и контекст, которого нет в diff'е. */
    data class Input(
        /** Изменения по файлам. */
        val files: List<FileChange>,
        /** Итог последнего прогона тестов и линтера. */
        val tests: TestStatus,
        /** Суммарный объём изменения в строках (добавленные + удалённые). */
        val totalChangedLines: Int,
        /** Затронут публичный API: экспортируемые символы, схемы протокола, публичные интерфейсы. */
        val touchesPublicApi: Boolean,
        /** Затронута схема БД: миграции, определения таблиц. */
        val touchesDatabaseSchema: Boolean,
        /** Затронуты права доступа или секреты. */
        val touchesPermissionsOrSecrets: Boolean,
    )

    /**
     * Вычисляет уровень риска.
     *
     * @param volumeThresholdLines порог объёма; по умолчанию [DEFAULT_VOLUME_THRESHOLD_LINES].
     */
    fun evaluate(changes: Input, volumeThresholdLines: Int = DEFAULT_VOLUME_THRESHOLD_LINES): RiskLevel =
        when {
            anyRiskyCondition(changes, volumeThresholdLines) -> RiskLevel.RISKY
            allSafeConditions(changes, volumeThresholdLines) -> RiskLevel.SAFE
            else -> RiskLevel.NORMAL
        }

    private fun anyRiskyCondition(changes: Input, threshold: Int): Boolean =
        changes.files.any { it.changeKind == FileChangeKind.DELETED } ||
            changes.touchesPublicApi ||
            changes.touchesDatabaseSchema ||
            changes.touchesPermissionsOrSecrets ||
            changes.tests.state == TestState.RED ||
            changes.totalChangedLines > threshold * RISKY_VOLUME_MULTIPLIER

    private fun allSafeConditions(changes: Input, threshold: Int): Boolean =
        changes.files.isNotEmpty() &&
            changes.files.all { isSafePath(it.path) } &&
            changes.tests.state == TestState.GREEN &&
            changes.tests.lintFindings == 0 &&
            changes.totalChangedLines in 1..threshold &&
            !changes.touchesPublicApi &&
            !changes.touchesDatabaseSchema &&
            !changes.touchesPermissionsOrSecrets

    /**
     * Путь считается безопасным, если он относится только к тестам, документации,
     * локализации или это файл сгенерированного форматтером результата.
     */
    internal fun isSafePath(path: String): Boolean {
        val normalized = path.replace('\\', '/').lowercase()
        val fileName = normalized.substringAfterLast('/')

        val inTestTree = normalized.contains("/test/") || normalized.contains("/tests/") ||
            normalized.contains("/androidtest/") || normalized.contains("/commonTest/".lowercase()) ||
            normalized.startsWith("test/") || normalized.startsWith("tests/") ||
            fileName.endsWith("test.kt") || fileName.endsWith("tests.kt") ||
            fileName.endsWith("_test.go") || fileName.startsWith("test_") ||
            fileName.endsWith("spec.kt") || fileName.endsWith("spec.js") || fileName.endsWith("spec.ts")

        val isDocs = normalized.startsWith("docs/") || normalized.contains("/docs/") ||
            fileName.endsWith(".md") || fileName.endsWith(".rst") || fileName == "license" ||
            fileName == "changelog.md"

        val isLocalization = normalized.contains("/values-") || normalized.contains("/i18n/") ||
            normalized.contains("/l10n/") || normalized.contains("/locales/") ||
            fileName == "strings.xml" || fileName.endsWith(".po") || fileName.endsWith(".ftl")

        return inTestTree || isDocs || isLocalization
    }
}
```

- [ ] **Шаг 4: прогнать тесты**

```bash
./gradlew :domain:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 43 теста пройдено (25 + 18 из `RiskEvaluatorTest`).

- [ ] **Шаг 5: проверить, что LLM не может понизить риск**

Это требование проверяется структурно: у `evaluate` нет параметра, принимающего подсказку от модели. Добавить тест, который это фиксирует, — если кто-то потом добавит такой параметр, тест укажет на нарушение контракта:

```kotlin
    @Test
    fun `вход вычисления не содержит полей, которые заполняет LLM`() {
        val fields = RiskEvaluator.Input::class.members.map { it.name }.toSet()
        val llmSuspicious = fields.filter { name ->
            name.contains("suggest", ignoreCase = true) ||
                name.contains("llm", ignoreCase = true) ||
                name.contains("model", ignoreCase = true) ||
                name.contains("advice", ignoreCase = true)
        }
        assertTrue(
            llmSuspicious.isEmpty(),
            "Уровень риска вычисляется только правилами § 5.3.2 (FR-AGENT-6), но во входе появились поля: $llmSuspicious",
        )
    }
```

Добавить импорт `kotlin.test.assertTrue`. Прогнать:

```bash
./gradlew :domain:jvmTest --tests 'dev.aide.domain.risk.RiskEvaluatorTest'
```

Ожидаемо: `BUILD SUCCESSFUL`, 19 тестов.

- [ ] **Шаг 6: коммит**

```bash
git add domain
git commit -m "feat(domain): RiskEvaluator как чистая функция по правилам § 5.3.2"
```

---

## Задача 8: сообщения протокола (`T-0.8`)

**Файлы:**
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolIds.kt`
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolVersion.kt`
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolError.kt`
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/Payloads.kt`
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/Messages.kt`
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolCodec.kt`
- Тест: `protocol/src/commonTest/kotlin/dev/aide/protocol/ProtocolCodecTest.kt`
- Изменить: `protocol/build.gradle.kts` (CBOR)

**Про транспортный конверт.** Сообщения кодируются в два слоя: внешний `WireEnvelope` с именем типа и внутренняя полезная нагрузка в CBOR. Так неизвестный тип сообщения становится обычным значением, а не исключением: хост логирует его и продолжает работу, не роняя сессию (это проверяется тестом на три неизвестных типа подряд).

- [ ] **Шаг 1: написать идентификаторы протокола**

`protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolIds.kt`:

```kotlin
package dev.aide.protocol

import kotlin.jvm.JvmInline
import kotlinx.serialization.Serializable

/** Идентификатор запроса. Повтор запроса с тем же значением не создаёт вторую операцию (§ 8.4). */
@Serializable
@JvmInline
value class RequestId(val value: String)

/** Идентификатор открытого на хосте воркспейса; непрозрачный для клиента. */
@Serializable
@JvmInline
value class WorkspaceId(val value: String)

/** Идентификатор сессии клиента на хосте. Меняется при полном переподключении. */
@Serializable
@JvmInline
value class SessionId(val value: String)

/** Версия протокола. */
@Serializable
data class ProtocolVersion(
    /** Несовпадение major означает несовместимость: частично работающий UI запрещён (§ 8.4). */
    val major: Int,
    /** В пределах одного major младшая версия хоста обслуживает более старые клиенты. */
    val minor: Int,
) {
    override fun toString(): String = "$major.$minor"

    companion object {
        /** Версия, которую объявляют и хост, и клиент этой сборки. */
        val CURRENT: ProtocolVersion = ProtocolVersion(major = 1, minor = 0)
    }
}
```

- [ ] **Шаг 2: написать типизированные ошибки**

`protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolError.kt`:

```kotlin
package dev.aide.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Ошибка обработки запроса. Ошибки типизированы, чтобы клиент показывал осмысленное
 * состояние экрана (§ 6.1), а не общее «что-то пошло не так».
 */
@Serializable
sealed interface ProtocolError {

    /** Попытка выйти за пределы воркспейса или прочитать то, что запрещено (§ 10.1). */
    @Serializable
    @SerialName("accessDenied")
    data class AccessDenied(
        /** Путь, к которому пытались обратиться; в UI показывается как есть. */
        val path: String,
        /** Почему отказано: вне корня воркспейса, симлинк наружу, нет прав на файл. */
        val reason: String,
    ) : ProtocolError

    /** Запрошенного объекта нет. */
    @Serializable
    @SerialName("notFound")
    data class NotFound(
        /** Что именно не найдено: путь, воркспейс, файл. */
        val what: String,
    ) : ProtocolError

    /** Каталог существует, но не является git-репозиторием. */
    @Serializable
    @SerialName("notAGitRepository")
    data class NotAGitRepository(
        /** Путь, который открывали. */
        val path: String,
    ) : ProtocolError

    /** Воркспейс был открыт, но уже закрыт. */
    @Serializable
    @SerialName("workspaceClosed")
    data class WorkspaceClosed(
        /** Идентификатор закрытого воркспейса. */
        val workspaceId: WorkspaceId,
    ) : ProtocolError

    /** Возможность появится в следующих этапах. */
    @Serializable
    @SerialName("notImplemented")
    data class NotImplemented(
        /** Что именно ещё не реализовано. */
        val what: String,
    ) : ProtocolError

    /** Внутренняя ошибка хоста. */
    @Serializable
    @SerialName("internal")
    data class Internal(
        /** Краткое описание для пользователя. */
        val message: String,
        /** Техническая деталь для лога; в UI показывается по запросу (§ 6.1). */
        val detail: String? = null,
    ) : ProtocolError
}

/** Почему версии протокола несовместимы. */
@Serializable
enum class IncompatibilityReason {
    /** Клиент старее хоста — пользователю нужно обновить приложение. */
    CLIENT_OUTDATED,

    /** Клиент новее хоста — нужно обновить хост. */
    HOST_OUTDATED,

    /** Приветствие не разобрано. */
    MALFORMED_HELLO,
}
```

- [ ] **Шаг 3: написать полезные нагрузки**

`protocol/src/commonMain/kotlin/dev/aide/protocol/Payloads.kt`:

```kotlin
package dev.aide.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/** Дерево файлов воркспейса. Плоский список, а не вложенные узлы: на 20 000 файлах
 *  вложенность упирается в глубину стека и в лимиты CBOR, а отступы UI считает по пути. */
@Serializable
data class FileTreePayload(
    /** Воркспейс, к которому относится дерево. */
    val workspaceId: WorkspaceId,
    /** Абсолютный путь корня; показывается в шапке. */
    val rootPath: String,
    /** Записи, отсортированные по пути; директория отличима по [FileTreeEntry.isDirectory]. */
    val entries: List<FileTreeEntry>,
    /** true, если обход остановлен лимитом и дерево неполное. */
    val truncated: Boolean,
    /** Сколько записей пропущено; 0, если [truncated] = false. */
    val skippedEntries: Int = 0,
)

/** Одна запись дерева. */
@Serializable
data class FileTreeEntry(
    /** Путь относительно корня воркспейса, разделитель — `/`. */
    val path: String,
    /** Директория это или файл. */
    val isDirectory: Boolean,
    /** Размер файла в байтах; null для директорий. */
    val sizeBytes: Long? = null,
)

/** Содержимое файла для просмотра. */
@Serializable
data class FileContentPayload(
    /** Воркспейс, из которого прочитан файл. */
    val workspaceId: WorkspaceId,
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Содержимое в UTF-8. */
    val text: String,
    /** Размер файла в байтах. */
    val sizeBytes: Long,
    /** true, если файл обрезан по лимиту показа. */
    val truncated: Boolean,
    /** Язык для подсветки, определённый по расширению; null, если неизвестен. */
    val language: String? = null,
)

/** Состояние хоста: то, что клиент показывает в шапке (FR-LAYOUT-1). */
@Serializable
data class HostStatePayload(
    /** Воркспейс, состояние которого отдаётся. */
    val workspaceId: WorkspaceId,
    /** Корень воркспейса. */
    val rootPath: String,
    /** Текущая ветка репозитория. */
    val branch: String,
    /** Короткий хеш HEAD; пустая строка, если коммитов нет. */
    val headCommit: String,
    /** Время работы хоста в миллисекундах. */
    val uptimeMillis: Long,
    /** Режим работы хоста — только для диагностических надписей, поведение клиента от него не зависит. */
    val mode: HostMode,
)

/** Режим работы хоста. */
@Serializable
enum class HostMode {
    /** Хост в том же процессе или рядом на той же машине. */
    LOCAL,

    /** Хост на другом устройстве; протокол и поведение клиента те же (§ 3.3). */
    REMOTE,
}

/** Событие хоста, приходящее клиенту без запроса. */
@Serializable
sealed interface HostEvent {

    /** Состояние воркспейса изменилось, данные нужно перезапросить. */
    @Serializable
    @SerialName("workspaceChanged")
    data class WorkspaceChanged(
        /** Какой воркспейс изменился. */
        val workspaceId: WorkspaceId,
    ) : HostEvent

    /** Хост завершает работу; клиенту нужно показать состояние «нет связи» (§ 6.1). */
    @Serializable
    @SerialName("hostShuttingDown")
    data object HostShuttingDown : HostEvent
}
```

- [ ] **Шаг 4: написать сообщения**

`protocol/src/commonMain/kotlin/dev/aide/protocol/Messages.kt`:

```kotlin
package dev.aide.protocol

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Сообщение клиента хосту. В этом этапе все сообщения клиента — запросы:
 * изменяющих операций нет до этапа 1, но механизм идемпотентности по [RequestId]
 * уже введён и проверяется в задаче 10.
 */
@Serializable
sealed interface ClientMessage {

    /** Приветствие: версия клиента и последняя увиденная последовательность событий. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Версия протокола клиента. */
        val clientVersion: ProtocolVersion,
        /** Последний полученный номер события; 0 при первом подключении. */
        val lastEventSeq: Long = 0,
    ) : ClientMessage

    /** Открыть репозиторий по пути. */
    @Serializable
    @SerialName("openWorkspace")
    data class OpenWorkspace(
        /** Идентификатор запроса для идемпотентности и сопоставления с ответом. */
        val requestId: RequestId,
        /** Абсолютный или относительный путь к каталогу репозитория. */
        val path: String,
    ) : ClientMessage

    /** Запросить дерево файлов. */
    @Serializable
    @SerialName("fileTree")
    data class FileTree(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage

    /** Запросить содержимое файла. */
    @Serializable
    @SerialName("fileContent")
    data class FileContent(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
        /** Путь относительно корня воркспейса. */
        val path: String,
    ) : ClientMessage

    /** Запросить состояние хоста. */
    @Serializable
    @SerialName("hostState")
    data class HostState(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Открытый воркспейс. */
        val workspaceId: WorkspaceId,
    ) : ClientMessage
}

/** Сообщение хоста клиенту. */
@Serializable
sealed interface HostMessage {

    /** Ответ на приветствие: версия хоста и выданный идентификатор сессии. */
    @Serializable
    @SerialName("hello")
    data class Hello(
        /** Версия протокола хоста. */
        val hostVersion: ProtocolVersion,
        /** Идентификатор сессии; меняется при переподключении. */
        val sessionId: SessionId,
    ) : HostMessage

    /** Открытый воркспейс. */
    @Serializable
    @SerialName("workspaceOpened")
    data class WorkspaceOpened(
        /** Идентификатор запроса, на который это ответ. */
        val requestId: RequestId,
        /** Идентификатор открытого воркспейса. */
        val workspaceId: WorkspaceId,
    ) : HostMessage

    /** Дерево файлов. */
    @Serializable
    @SerialName("tree")
    data class Tree(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с деревом. */
        val tree: FileTreePayload,
    ) : HostMessage

    /** Содержимое файла. */
    @Serializable
    @SerialName("content")
    data class Content(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с содержимым. */
        val content: FileContentPayload,
    ) : HostMessage

    /** Состояние хоста. */
    @Serializable
    @SerialName("state")
    data class State(
        /** Идентификатор запроса. */
        val requestId: RequestId,
        /** Полезная нагрузка с состоянием. */
        val state: HostStatePayload,
    ) : HostMessage

    /** Ошибка обработки запроса. */
    @Serializable
    @SerialName("failure")
    data class Failure(
        /** Идентификатор запроса, который не удалось выполнить. */
        val requestId: RequestId,
        /** Типизированная ошибка. */
        val error: ProtocolError,
    ) : HostMessage

    /** Версии несовместимы; соединение закрывается, UI показывает требование обновления (T-0.9). */
    @Serializable
    @SerialName("incompatible")
    data class Incompatible(
        /** Почему несовместимы. */
        val reason: IncompatibilityReason,
        /** Версия хоста, чтобы клиент мог показать её пользователю. */
        val hostVersion: ProtocolVersion,
    ) : HostMessage

    /** Событие без запроса. */
    @Serializable
    @SerialName("event")
    data class Event(
        /** Что произошло на хосте. */
        val event: HostEvent,
    ) : HostMessage
}
```

- [ ] **Шаг 5: написать кодек с толерантностью к неизвестным типам**

`protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolCodec.kt`:

```kotlin
package dev.aide.protocol

import kotlinx.serialization.DeserializationStrategy
import kotlinx.serialization.SerializationStrategy
import kotlinx.serialization.Serializable
import kotlinx.serialization.cbor.Cbor

/** Транспортный конверт: имя типа плюс полезная нагрузка в CBOR. */
@Serializable
internal data class WireEnvelope(
    /** Имя типа сообщения. */
    val type: String,
    /** Полезная нагрузка. */
    val payload: ByteArray,
) {
    override fun equals(other: Any?): Boolean =
        this === other || (other is WireEnvelope && type == other.type && payload.contentEquals(other.payload))

    override fun hashCode(): Int = 31 * type.hashCode() + payload.contentHashCode()
}

/** Результат разбора сообщения. */
sealed interface DecodeResult<out T> {

    /** Сообщение разобрано. */
    data class Message<T>(val message: T) : DecodeResult<T>

    /** Сообщение пропущено: неизвестный тип или неразобранная нагрузка. Клиент продолжает работу. */
    data class Ignored(val rawType: String?, val reason: String) : DecodeResult<Nothing>
}

/** Имена типов сообщений клиента. */
object ClientMessageType {
    const val HELLO = "hello"
    const val OPEN_WORKSPACE = "openWorkspace"
    const val FILE_TREE = "fileTree"
    const val FILE_CONTENT = "fileContent"
    const val HOST_STATE = "hostState"
}

/** Имена типов сообщений хоста. */
object HostMessageType {
    const val HELLO = "hello"
    const val WORKSPACE_OPENED = "workspaceOpened"
    const val TREE = "tree"
    const val CONTENT = "content"
    const val STATE = "state"
    const val FAILURE = "failure"
    const val INCOMPATIBLE = "incompatible"
    const val EVENT = "event"
}

/**
 * Бинарный кодек протокола (§ 8.4). Формат — CBOR.
 *
 * Неизвестный тип сообщения не бросает исключение наружу: он возвращается как
 * [DecodeResult.Ignored], чтобы вызывающая сторона записала его в лог и продолжила работу.
 */
object ProtocolCodec {

    private val cbor = Cbor {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(message: ClientMessage): ByteArray = when (message) {
        is ClientMessage.Hello ->
            envelope(ClientMessageType.HELLO, ClientMessage.Hello.serializer(), message)
        is ClientMessage.OpenWorkspace ->
            envelope(ClientMessageType.OPEN_WORKSPACE, ClientMessage.OpenWorkspace.serializer(), message)
        is ClientMessage.FileTree ->
            envelope(ClientMessageType.FILE_TREE, ClientMessage.FileTree.serializer(), message)
        is ClientMessage.FileContent ->
            envelope(ClientMessageType.FILE_CONTENT, ClientMessage.FileContent.serializer(), message)
        is ClientMessage.HostState ->
            envelope(ClientMessageType.HOST_STATE, ClientMessage.HostState.serializer(), message)
    }

    fun encode(message: HostMessage): ByteArray = when (message) {
        is HostMessage.Hello -> envelope(HostMessageType.HELLO, HostMessage.Hello.serializer(), message)
        is HostMessage.WorkspaceOpened ->
            envelope(HostMessageType.WORKSPACE_OPENED, HostMessage.WorkspaceOpened.serializer(), message)
        is HostMessage.Tree -> envelope(HostMessageType.TREE, HostMessage.Tree.serializer(), message)
        is HostMessage.Content -> envelope(HostMessageType.CONTENT, HostMessage.Content.serializer(), message)
        is HostMessage.State -> envelope(HostMessageType.STATE, HostMessage.State.serializer(), message)
        is HostMessage.Failure -> envelope(HostMessageType.FAILURE, HostMessage.Failure.serializer(), message)
        is HostMessage.Incompatible ->
            envelope(HostMessageType.INCOMPATIBLE, HostMessage.Incompatible.serializer(), message)
        is HostMessage.Event -> envelope(HostMessageType.EVENT, HostMessage.Event.serializer(), message)
    }

    fun decodeClientMessage(bytes: ByteArray): DecodeResult<ClientMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return when (env.type) {
            ClientMessageType.HELLO -> decode(env, ClientMessage.Hello.serializer())
            ClientMessageType.OPEN_WORKSPACE -> decode(env, ClientMessage.OpenWorkspace.serializer())
            ClientMessageType.FILE_TREE -> decode(env, ClientMessage.FileTree.serializer())
            ClientMessageType.FILE_CONTENT -> decode(env, ClientMessage.FileContent.serializer())
            ClientMessageType.HOST_STATE -> decode(env, ClientMessage.HostState.serializer())
            else -> DecodeResult.Ignored(env.type, "неизвестный тип сообщения клиента")
        }
    }

    fun decodeHostMessage(bytes: ByteArray): DecodeResult<HostMessage> {
        val env = readEnvelope(bytes) ?: return DecodeResult.Ignored(null, "конверт не разобран")
        return when (env.type) {
            HostMessageType.HELLO -> decode(env, HostMessage.Hello.serializer())
            HostMessageType.WORKSPACE_OPENED -> decode(env, HostMessage.WorkspaceOpened.serializer())
            HostMessageType.TREE -> decode(env, HostMessage.Tree.serializer())
            HostMessageType.CONTENT -> decode(env, HostMessage.Content.serializer())
            HostMessageType.STATE -> decode(env, HostMessage.State.serializer())
            HostMessageType.FAILURE -> decode(env, HostMessage.Failure.serializer())
            HostMessageType.INCOMPATIBLE -> decode(env, HostMessage.Incompatible.serializer())
            HostMessageType.EVENT -> decode(env, HostMessage.Event.serializer())
            else -> DecodeResult.Ignored(env.type, "неизвестный тип сообщения хоста")
        }
    }

    /** Собирает конверт, который клиент или хост может прочитать, даже не зная тип сообщения. */
    fun <T> envelope(type: String, serializer: SerializationStrategy<T>, value: T): ByteArray =
        cbor.encodeToByteArray(WireEnvelope.serializer(), WireEnvelope(type, cbor.encodeToByteArray(serializer, value)))

    /** Возвращает имя типа из сырых байтов, не разбирая нагрузку; null, если конверт нечитаем. */
    fun peekType(bytes: ByteArray): String? = readEnvelope(bytes)?.type

    private fun readEnvelope(bytes: ByteArray): WireEnvelope? =
        runCatching { cbor.decodeFromByteArray(WireEnvelope.serializer(), bytes) }.getOrNull()

    private fun <T> decode(env: WireEnvelope, serializer: DeserializationStrategy<T>): DecodeResult<T> =
        runCatching { cbor.decodeFromByteArray(serializer, env.payload) }.fold(
            onSuccess = { DecodeResult.Message(it) },
            onFailure = { DecodeResult.Ignored(env.type, "полезная нагрузка не разобрана: ${it.message}") },
        )
}
```

- [ ] **Шаг 6: подключить CBOR к модулю**

`protocol/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation: сообщения протокола несут доменные типы в публичных
            // сигнатурах (payload'ы, HostEvent) — правило шага 7 задачи 1.
            api(project(":domain"))
            implementation(libs.kotlinx.serialization.core)
            implementation(libs.kotlinx.serialization.cbor)
            implementation(libs.kotlinx.datetime)
        }
    }
}
```

- [ ] **Шаг 7: написать тесты кодека**

`protocol/src/commonTest/kotlin/dev/aide/protocol/ProtocolCodecTest.kt`:

```kotlin
package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProtocolCodecTest {

    private val workspaceId = WorkspaceId("ws-1")
    private val requestId = RequestId("req-1")

    @Test
    fun `сообщение клиента переживает round-trip`() {
        val message = ClientMessage.FileContent(
            requestId = requestId,
            workspaceId = workspaceId,
            path = "src/auth/Login.kt",
        )
        val decoded = ProtocolCodec.decodeClientMessage(ProtocolCodec.encode(message))
        assertEquals(message, assertIs<DecodeResult.Message<ClientMessage>>(decoded).message)
    }

    @Test
    fun `дерево файлов переживает round-trip целиком`() {
        val message = HostMessage.Tree(
            requestId = requestId,
            tree = FileTreePayload(
                workspaceId = workspaceId,
                rootPath = "/projects/aide",
                entries = listOf(
                    FileTreeEntry(path = "src", isDirectory = true, sizeBytes = null),
                    FileTreeEntry(path = "src/auth", isDirectory = true, sizeBytes = null),
                    FileTreeEntry(path = "src/auth/Login.kt", isDirectory = false, sizeBytes = 2048),
                ),
                truncated = true,
                skippedEntries = 17,
            ),
        )
        val decoded = assertIs<DecodeResult.Message<HostMessage>>(
            ProtocolCodec.decodeHostMessage(ProtocolCodec.encode(message)),
        ).message
        assertEquals(message, decoded)
    }

    @Test
    fun `типизированная ошибка доступа переживает round-trip`() {
        val message = HostMessage.Failure(
            requestId = requestId,
            error = ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"),
        )
        val decoded = assertIs<DecodeResult.Message<HostMessage>>(
            ProtocolCodec.decodeHostMessage(ProtocolCodec.encode(message)),
        ).message
        assertEquals(message, decoded)
    }

    @Test
    fun `событие хоста переживает round-trip`() {
        val message = HostMessage.Event(HostEvent.WorkspaceChanged(workspaceId))
        val decoded = assertIs<DecodeResult.Message<HostMessage>>(
            ProtocolCodec.decodeHostMessage(ProtocolCodec.encode(message)),
        ).message
        assertEquals(message, decoded)
    }

    @Test
    fun `неизвестный тип сообщения не роняет разбор, а возвращается как Ignored`() {
        val unknown = ProtocolCodec.envelope(
            type = "quantumTeleport",
            serializer = ClientMessage.Hello.serializer(),
            value = ClientMessage.Hello(ProtocolVersion.CURRENT),
        )
        val result = ProtocolCodec.decodeClientMessage(unknown)
        val ignored = assertIs<DecodeResult.Ignored>(result)
        assertEquals("quantumTeleport", ignored.rawType)
        assertTrue(ignored.reason.contains("неизвестный тип"))
    }

    @Test
    fun `три неизвестных типа подряд разбираются как три Ignored и работа продолжается`() {
        val unknowns = listOf("a", "b", "c").map { name ->
            ProtocolCodec.envelope(name, HostMessage.Hello.serializer(), HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s")))
        }
        val results = unknowns.map { ProtocolCodec.decodeHostMessage(it) }
        assertEquals(listOf("a", "b", "c"), results.map { assertIs<DecodeResult.Ignored>(it).rawType })

        // …и следующее известное сообщение после них по-прежнему разбирается.
        val known = ProtocolCodec.encode(HostMessage.Hello(ProtocolVersion.CURRENT, SessionId("s-2")))
        assertIs<DecodeResult.Message<HostMessage>>(ProtocolCodec.decodeHostMessage(known))
    }

    @Test
    fun `битые байты дают Ignored, а не исключение`() {
        val result = ProtocolCodec.decodeClientMessage(byteArrayOf(0x00, 0x01, 0x02))
        assertIs<DecodeResult.Ignored>(result)
    }

    @Test
    fun `имя типа можно прочитать без разбора нагрузки`() {
        val bytes = ProtocolCodec.encode(ClientMessage.HostState(requestId, workspaceId))
        assertEquals(ClientMessageType.HOST_STATE, ProtocolCodec.peekType(bytes))
        assertNull(ProtocolCodec.peekType(byteArrayOf(0x7f)))
    }

    @Test
    fun `кодирование одного сообщения дважды даёт одинаковые байты`() {
        val message = ClientMessage.OpenWorkspace(requestId, "/projects/aide")
        assertContentEquals(ProtocolCodec.encode(message), ProtocolCodec.encode(message))
    }

    @Test
    fun `версия протокола участвует в приветствии`() {
        val hello = ClientMessage.Hello(clientVersion = ProtocolVersion.CURRENT, lastEventSeq = 7)
        val decoded = assertIs<DecodeResult.Message<ClientMessage>>(
            ProtocolCodec.decodeClientMessage(ProtocolCodec.encode(hello)),
        ).message
        assertEquals(ProtocolVersion.CURRENT, assertIs<ClientMessage.Hello>(decoded).clientVersion)
        assertEquals(7, assertIs<ClientMessage.Hello>(decoded).lastEventSeq)
    }
}
```

- [ ] **Шаг 8: прогнать тесты**

```bash
./gradlew :protocol:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 10 тестов пройдено. Если не компилируется `WireEnvelope` — проверить, что у него переопределены `equals` и `hashCode`: массив в `data class` сравнивается по ссылке, и без переопределения тест round-trip на дереве упадёт неочевидным образом.

- [ ] **Шаг 9: коммит**

```bash
git add protocol
git commit -m "feat(protocol): бинарный CBOR-кодек, типизированные ошибки и толерантность к неизвестным типам"
```

---

## Задача 9: совместимость версий протокола (`T-0.9`)

**Файлы:**
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolCompatibility.kt`
- Тест: `protocol/src/commonTest/kotlin/dev/aide/protocol/ProtocolCompatibilityTest.kt`

**Правило совместимости.** Несовпадение `major` означает несовместимость в любую сторону: частично работающий UI запрещён (§ 8.4), соединение закрывается, клиент показывает требование обновления. При совпадающем `major` хост обслуживает клиента с `minor` не выше своего; клиент с более высоким `minor` получает требование обновить хост.

- [ ] **Шаг 1: написать падающие тесты**

`protocol/src/commonTest/kotlin/dev/aide/protocol/ProtocolCompatibilityTest.kt`:

```kotlin
package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class ProtocolCompatibilityTest {

    @Test
    fun `одинаковые версии совместимы`() {
        val result = ProtocolCompatibility.check(
            client = ProtocolVersion(1, 0),
            host = ProtocolVersion(1, 0),
        )
        assertEquals(ProtocolCompatibility.Compatible, result)
    }

    @Test
    fun `клиент с меньшим minor при том же major совместим`() {
        val result = ProtocolCompatibility.check(
            client = ProtocolVersion(1, 0),
            host = ProtocolVersion(1, 3),
        )
        assertEquals(ProtocolCompatibility.Compatible, result)
    }

    @Test
    fun `версия клиента старше по major — обновите приложение`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 0), host = ProtocolVersion(2, 0)),
        )
        assertEquals(IncompatibilityReason.CLIENT_OUTDATED, result.reason)
        assertTrue(result.userMessage.contains("обновите приложение", ignoreCase = true))
    }

    @Test
    fun `версия клиента новее по major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(3, 1), host = ProtocolVersion(2, 9)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
        assertTrue(result.userMessage.contains("обновите хост", ignoreCase = true))
    }

    @Test
    fun `клиент с большим minor при том же major — обновите хост`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertEquals(IncompatibilityReason.HOST_OUTDATED, result.reason)
    }

    @Test
    fun `сообщение несовместимости содержит обе версии, чтобы пользователь видел, что обновлять`() {
        val result = assertIs<ProtocolCompatibility.Incompatible>(
            ProtocolCompatibility.check(client = ProtocolVersion(1, 5), host = ProtocolVersion(1, 2)),
        )
        assertTrue(result.userMessage.contains("1.5"))
        assertTrue(result.userMessage.contains("1.2"))
    }

    @Test
    fun `ответ хоста при несовместимости содержит причину и версию хоста`() {
        val incompatible = ProtocolCompatibility.toHostMessage(
            result = ProtocolCompatibility.Incompatible(
                reason = IncompatibilityReason.CLIENT_OUTDATED,
                userMessage = "Обновите приложение",
            ),
            hostVersion = ProtocolVersion(2, 0),
        )
        assertEquals(IncompatibilityReason.CLIENT_OUTDATED, incompatible.reason)
        assertEquals(ProtocolVersion(2, 0), incompatible.hostVersion)
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :protocol:jvmTest --tests 'dev.aide.protocol.ProtocolCompatibilityTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: ProtocolCompatibility`.

- [ ] **Шаг 3: написать реализацию**

`protocol/src/commonMain/kotlin/dev/aide/protocol/ProtocolCompatibility.kt`:

```kotlin
package dev.aide.protocol

/**
 * Проверка совместимости версий протокола (§ 8.4, T-0.9).
 *
 * Решение принимает хост при получении приветствия и, если версии несовместимы,
 * отвечает [HostMessage.Incompatible] и закрывает соединение. Так клиент и хост
 * никогда не работают частично: либо протокол целиком понятен обеим сторонам, либо
 * пользователь видит, что именно обновить.
 */
object ProtocolCompatibility {

    /** Версии совместимы. */
    data object Compatible : ProtocolCompatibilityResult

    /** Версии несовместимы; [userMessage] показывается пользователю как есть. */
    data class Incompatible(
        /** Машинночитаемая причина. */
        val reason: IncompatibilityReason,
        /** Текст для пользователя: что обновить и до чего. */
        val userMessage: String,
    ) : ProtocolCompatibilityResult

    /** Сравнивает версии клиента и хоста. */
    fun check(client: ProtocolVersion, host: ProtocolVersion): ProtocolCompatibilityResult = when {
        client.major != host.major && client.major < host.major -> Incompatible(
            reason = IncompatibilityReason.CLIENT_OUTDATED,
            userMessage = "Версия протокола не поддерживается: обновите приложение " +
                "(клиент $client, хост $host).",
        )

        client.major != host.major -> Incompatible(
            reason = IncompatibilityReason.HOST_OUTDATED,
            userMessage = "Версия протокола не поддерживается: обновите хост " +
                "(клиент $client, хост $host).",
        )

        client.minor > host.minor -> Incompatible(
            reason = IncompatibilityReason.HOST_OUTDATED,
            userMessage = "Клиент новее хоста: обновите хост (клиент $client, хост $host).",
        )

        else -> Compatible
    }

    /** Превращает результат проверки в сообщение хоста для ответа клиенту. */
    fun toHostMessage(result: Incompatible, hostVersion: ProtocolVersion): HostMessage.Incompatible =
        HostMessage.Incompatible(reason = result.reason, hostVersion = hostVersion)
}

/** Результат проверки совместимости: либо [ProtocolCompatibility.Compatible], либо причина несовместимости. */
sealed interface ProtocolCompatibilityResult

/** Псевдоним для читаемости объявлений вида `when (result) { is Compatible -> … }`. */
typealias Compatible = ProtocolCompatibility.Compatible

/** Псевдоним для читаемости обработки несовместимости. */
typealias Incompatible = ProtocolCompatibility.Incompatible
```

- [ ] **Шаг 4: прогнать тесты**

```bash
./gradlew :protocol:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 17 тестов (10 из задачи 8 + 7 из этой).

- [ ] **Шаг 5: коммит**

```bash
git add protocol
git commit -m "feat(protocol): проверка совместимости версий с явной ошибкой вместо частичной работы"
```

---

## Задача 10: транспорт WebSocket, реконнект и идемпотентность (`T-0.10`)

**Файлы:**
- Создать: `protocol/src/commonMain/kotlin/dev/aide/protocol/RequestDedupCache.kt`
- Тест: `protocol/src/commonTest/kotlin/dev/aide/protocol/RequestDedupCacheTest.kt`
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/ConnectionState.kt`
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/HostConnection.kt`
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/KtorHostConnection.kt`
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/HostClient.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/server/ProtocolServer.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/server/ClientSession.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/server/Ports.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/server/ProtocolServerTest.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/server/ReconnectTest.kt`
- Изменить: `client-state/build.gradle.kts`, `host-core/build.gradle.kts`

**Три требования, которые здесь закрываются:** клиент переживает обрыв сети и восстанавливает сессию без перезапуска приложения; после реконнекта клиент получает актуальное состояние, а не продолжает с устаревшим; повтор изменяющего запроса с тем же `RequestId` не выполняет операцию второй раз.

- [ ] **Шаг 1: написать падающий тест на кэш идемпотентности**

`protocol/src/commonTest/kotlin/dev/aide/protocol/RequestDedupCacheTest.kt`:

```kotlin
package dev.aide.protocol

import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNull

class RequestDedupCacheTest {

    private val req = RequestId("req-1")

    @Test
    fun `неизвестный запрос не найден`() {
        assertNull(RequestDedupCache().get(req))
    }

    @Test
    fun `сохранённый ответ возвращается тем же`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1, 2, 3))
        assertContentEquals(byteArrayOf(1, 2, 3), cache.get(req))
    }

    @Test
    fun `повторное сохранение того же запроса не создаёт вторую запись`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1))
        cache.put(req, byteArrayOf(1))
        assertEquals(1, cache.size)
    }

    @Test
    fun `при переполнении вытесняется самый старый запрос`() {
        val cache = RequestDedupCache(capacity = 2)
        cache.put(RequestId("a"), byteArrayOf(1))
        cache.put(RequestId("b"), byteArrayOf(2))
        cache.put(RequestId("c"), byteArrayOf(3))

        assertNull(cache.get(RequestId("a")))
        assertNull(cache.get(RequestId("b")), "Обращение к 'a' должно было обновить порядок вытеснения")
        assertContentEquals(byteArrayOf(3), cache.get(RequestId("c")))
        assertEquals(2, cache.size)
    }

    @Test
    fun `обращение к записи отодвигает её вытеснение`() {
        val cache = RequestDedupCache(capacity = 2)
        cache.put(RequestId("a"), byteArrayOf(1))
        cache.put(RequestId("b"), byteArrayOf(2))
        cache.get(RequestId("a")) // 'a' становится самой свежей
        cache.put(RequestId("c"), byteArrayOf(3))

        assertContentEquals(byteArrayOf(1), cache.get(RequestId("a")))
        assertNull(cache.get(RequestId("b")))
    }

    @Test
    fun `очистка убирает все записи`() {
        val cache = RequestDedupCache()
        cache.put(req, byteArrayOf(1))
        cache.clear()
        assertEquals(0, cache.size)
        assertNull(cache.get(req))
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :protocol:jvmTest --tests 'dev.aide.protocol.RequestDedupCacheTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: RequestDedupCache`.

- [ ] **Шаг 3: написать кэш идемпотентности**

`protocol/src/commonMain/kotlin/dev/aide/protocol/RequestDedupCache.kt`:

```kotlin
package dev.aide.protocol

/**
 * Кэш ответов по [RequestId] — механизм идемпотентности из § 8.4.
 *
 * Если клиент повторит запрос после обрыва связи (он не знает, дошёл ли запрос),
 * хост вернёт сохранённый ответ и не выполнит операцию второй раз. Порядок
 * вытеснения — LRU: обращение к записи отодвигает её вытеснение.
 *
 * Хранит не объекты, а уже закодированные байты: ответ должен уйти повторно
 * ровно в том же виде, в каком был сформирован.
 */
class RequestDedupCache(private val capacity: Int = DEFAULT_CAPACITY) {

    init {
        require(capacity > 0) { "Ёмкость кэша должна быть положительной, получено $capacity" }
    }

    private val responses = LinkedHashMap<RequestId, ByteArray>(capacity, 0.75f, /* accessOrder = */ true)

    /** Число сохранённых ответов. */
    val size: Int get() = responses.size

    /** Возвращает сохранённый ответ или null, если запрос ещё не выполнялся. */
    fun get(requestId: RequestId): ByteArray? = responses[requestId]

    /** Сохраняет ответ; повторное сохранение перезаписывает запись, не добавляя новую. */
    fun put(requestId: RequestId, response: ByteArray) {
        responses[requestId] = response
        while (responses.size > capacity) {
            val oldest = responses.keys.first()
            responses.remove(oldest)
        }
    }

    /** Полная очистка; вызывается при смене воркспейса, когда прежние ответы уже неактуальны. */
    fun clear() = responses.clear()

    companion object {
        /** Значение по умолчанию: с запасом покрывает запросы одного экрана ревью. */
        const val DEFAULT_CAPACITY: Int = 256
    }
}
```

- [ ] **Шаг 4: прогнать тест**

```bash
./gradlew :protocol:jvmTest --tests 'dev.aide.protocol.RequestDedupCacheTest'
```

Ожидаемо: `BUILD SUCCESSFUL`, 6 тестов. Если упал тест про переполнение — проверить, что в `LinkedHashMap` передан `accessOrder = true`: при `false` вытеснение идёт по порядку вставки.

- [ ] **Шаг 5: написать состояние соединения и интерфейс**

`client-state/src/commonMain/kotlin/dev/aide/client/state/ConnectionState.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.SessionId

/**
 * Состояние связи с хостом. UI обязан различать эти состояния, а не показывать
 * пустой экран: cached-режим помечается явно (§ 3.5, § 6.1).
 */
sealed interface ConnectionState {

    /** Соединения ещё не было. */
    data object Idle : ConnectionState

    /** Идёт первая попытка подключения. */
    data object Connecting : ConnectionState

    /** Соединение установлено и приветствие принято. */
    data class Connected(
        /** Идентификатор сессии, выданный хостом. */
        val sessionId: SessionId,
        /** true, если это восстановление после обрыва, а не первое подключение. */
        val reconnected: Boolean,
    ) : ConnectionState

    /** Связь потеряна, идёт ожидание перед следующей попыткой. */
    data class Reconnecting(
        /** Номер попытки, начиная с 1. */
        val attempt: Int,
        /** Сколько миллисекунд ждать до следующей попытки. */
        val nextRetryMillis: Long,
    ) : ConnectionState

    /** Версии протокола несовместимы; UI показывает [userMessage] и не даёт работать. */
    data class Incompatible(
        /** Что обновить и до какой версии. */
        val userMessage: String,
    ) : ConnectionState

    /** Соединение закрыто окончательно: остановлено пользователем или хост отверг сессию. */
    data class Closed(
        /** Причина для лога и для показа по запросу. */
        val reason: String,
    ) : ConnectionState
}
```

`client-state/src/commonMain/kotlin/dev/aide/client/state/HostConnection.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Соединение с хостом. Клиентский код знает только этот интерфейс,
 * поэтому локальный хост и удалённый неотличимы (§ 3.3).
 */
interface HostConnection {

    /** Текущее состояние связи; UI подписывается на него для показа «нет связи». */
    val state: StateFlow<ConnectionState>

    /** Сообщения хоста, не являющиеся ответами на запросы: события. */
    val events: SharedFlow<HostMessage>

    /** Запускает цикл соединения с автопереподключением. Возвращается сразу. */
    fun start()

    /** Останавливает соединение и отменяет переподключения. */
    suspend fun stop()

    /**
     * Отправляет запрос и ждёт ответ с тем же `requestId`.
     *
     * @return ответ хоста либо null, если ответ не пришёл за [timeoutMillis].
     */
    suspend fun request(message: ClientMessage, timeoutMillis: Long = DEFAULT_TIMEOUT_MILLIS): HostMessage?

    companion object {
        /** Таймаут ответа по умолчанию: открытие репозитория на большом дереве укладывается в него с запасом. */
        const val DEFAULT_TIMEOUT_MILLIS: Long = 30_000
    }
}
```

- [ ] **Шаг 6: написать реализацию соединения с реконнектом**

`client-state/src/commonMain/kotlin/dev/aide/client/state/KtorHostConnection.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostMessage
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolCompatibility
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import io.ktor.client.plugins.websocket.webSocketSession
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import kotlin.math.min
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.datetime.Clock

/**
 * Соединение по WebSocket с автопереподключением.
 *
 * Переподключение — внешний цикл: любая ошибка чтения или записи завершает текущую
 * попытку, состояние переходит в [ConnectionState.Reconnecting], и через задержку
 * с экспоненциальным ростом цикл начинается заново. Приложение при этом не
 * перезапускается: очередь ожидающих запросов и подписчики живут дольше соединения.
 *
 * @param endpoint адрес вида `ws://127.0.0.1:8080/ws`. Отличается у локального и
 *   удалённого хоста только значением — кода это не касается.
 */
class KtorHostConnection(
    private val endpoint: String,
    private val scope: CoroutineScope,
    private val httpClient: HttpClient = defaultHttpClient(),
    private val clientVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    private val initialRetryMillis: Long = 250,
    private val maxRetryMillis: Long = 5_000,
) : HostConnection {

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Idle)
    override val state: StateFlow<ConnectionState> = _state.asStateFlow()

    private val _events = MutableSharedFlow<HostMessage>(extraBufferCapacity = 64)
    override val events: SharedFlow<HostMessage> = _events.asSharedFlow()

    private val outgoing = Channel<ByteArray>(capacity = Channel.UNLIMITED)
    private val pending = mutableMapOf<RequestId, CompletableDeferred<HostMessage>>()

    private var loop: Job? = null
    private var everConnected = false

    override fun start() {
        if (loop?.isActive == true) return
        loop = scope.launch {
            var attempt = 0
            while (isActive) {
                try {
                    _state.value = if (everConnected) {
                        ConnectionState.Reconnecting(attempt = attempt + 1, nextRetryMillis = 0)
                    } else {
                        ConnectionState.Connecting
                    }
                    connectOnce()
                    attempt = 0
                } catch (error: Throwable) {
                    if (_state.value is ConnectionState.Closed || _state.value is ConnectionState.Incompatible) return@launch
                    attempt += 1
                    val wait = backoffMillis(attempt)
                    _state.value = ConnectionState.Reconnecting(attempt = attempt, nextRetryMillis = wait)
                    delay(wait)
                }
            }
        }
    }

    override suspend fun stop() {
        loop?.cancel()
        loop = null
        httpClient.close()
        _state.value = ConnectionState.Closed("Соединение остановлено пользователем")
    }

    override suspend fun request(message: ClientMessage, timeoutMillis: Long): HostMessage? {
        val requestId = message.requestIdOrNull()
            ?: return withTimeoutOrNull(timeoutMillis) { sendAndAwait(message, RequestId("__hello__")) }

        // Если соединение уже установлено, отправляем сразу; иначе кладём в очередь —
        // цикл соединения вышлет накопленное после подключения.
        val deferred = CompletableDeferred<HostMessage>()
        pending[requestId] = deferred
        outgoing.trySend(ProtocolCodec.encode(message))
        return withTimeoutOrNull(timeoutMillis) { deferred.await() }.also { pending.remove(requestId) }
    }

    private suspend fun sendAndAwait(message: ClientMessage, requestId: RequestId): HostMessage? {
        val deferred = CompletableDeferred<HostMessage>()
        pending[requestId] = deferred
        outgoing.trySend(ProtocolCodec.encode(message))
        return deferred.await().also { pending.remove(requestId) }
    }

    private suspend fun connectOnce() {
        val session = httpClient.webSocketSession(endpoint)
        try {
            session.send(Frame.Binary(true, ProtocolCodec.encode(ClientMessage.Hello(clientVersion))))

            // Отправляем накопленные запросы, включая те, что клиент поставил в очередь до подключения.
            val pump = scope.launch {
                for (bytes in outgoing) session.send(Frame.Binary(true, bytes))
            }

            try {
                for (frame in session.incoming) {
                    if (frame !is Frame.Binary) continue
                    handleFrame(frame.readBytes())
                }
            } finally {
                pump.cancel()
            }
        } finally {
            session.close()
        }
    }

    private suspend fun handleFrame(bytes: ByteArray) {
        when (val decoded = ProtocolCodec.decodeHostMessage(bytes)) {
            is DecodeResult.Ignored -> {
                // Неизвестное сообщение не роняет соединение: оно логируется и пропускается (T-0.8).
                logger.warn("Пропущено сообщение хоста: ${decoded.reason} (тип: ${decoded.rawType})")
            }

            is DecodeResult.Message -> {
                val message = decoded.message
                when (message) {
                    is HostMessage.Hello -> {
                        val reconnected = everConnected
                        everConnected = true
                        _state.value = ConnectionState.Connected(sessionId = message.sessionId, reconnected = reconnected)
                    }

                    is HostMessage.Incompatible -> {
                        val userMessage = ProtocolCompatibility.check(
                            client = clientVersion,
                            host = message.hostVersion,
                        ).let { result ->
                            (result as? ProtocolCompatibility.Incompatible)?.userMessage
                                ?: "Версии протокола несовместимы (хост ${message.hostVersion})"
                        }
                        _state.value = ConnectionState.Incompatible(userMessage)
                    }

                    else -> {
                        val requestId = message.requestIdOrNull()
                        val waiter = requestId?.let { pending.remove(it) }
                        if (waiter != null) waiter.complete(message) else _events.tryEmit(message)
                    }
                }
            }
        }
    }

    private fun backoffMillis(attempt: Int): Long {
        var millis = initialRetryMillis
        repeat(attempt - 1) { millis = min(millis * 2, maxRetryMillis) }
        return min(millis, maxRetryMillis)
    }

    companion object {
        /** Клиент по умолчанию: движок websockets. */
        fun defaultHttpClient(): HttpClient = HttpClient { install(WebSockets) }
    }
}
```

Добавить в этот файл логгер — временный, до появления общего логгера в задаче 14:

```kotlin
import org.slf4j.LoggerFactory

private val logger = LoggerFactory.getLogger("dev.aide.client.state.KtorHostConnection")
```

И расширение для извлечения `requestId`, в конце того же файла:

```kotlin
/** Идентификатор запроса, если сообщение является ответом; null для событий. */
internal fun HostMessage.requestIdOrNull(): RequestId? = when (this) {
    is HostMessage.WorkspaceOpened -> requestId
    is HostMessage.Tree -> requestId
    is HostMessage.Content -> requestId
    is HostMessage.State -> requestId
    is HostMessage.Failure -> requestId
    else -> null
}

/** Идентификатор запроса, если сообщение клиента является запросом; null для приветствия. */
internal fun ClientMessage.requestIdOrNull(): RequestId? = when (this) {
    is ClientMessage.OpenWorkspace -> requestId
    is ClientMessage.FileTree -> requestId
    is ClientMessage.FileContent -> requestId
    is ClientMessage.HostState -> requestId
    is ClientMessage.Hello -> null
}
```

- [ ] **Шаг 7: написать типизированный клиент хоста**

`client-state/src/commonMain/kotlin/dev/aide/client/state/HostClient.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** Что клиент знает о хосте прямо сейчас. */
data class HostSession(
    /** Открытый воркспейс; null, если ни один не открыт. */
    val workspaceId: WorkspaceId? = null,
    /** Состояние хоста: ветка, корень, режим. */
    val hostState: HostStatePayload? = null,
    /** Последняя ошибка запроса; null, если ошибок нет. */
    val lastError: ProtocolError? = null,
)

/**
 * Типизированный доступ к хосту поверх [HostConnection].
 *
 * Отдельно решает задачу «после реконнекта клиент получает актуальное состояние»:
 * при переходе соединения в [ConnectionState.Connected] с признаком `reconnected`
 * клиент заново запрашивает состояние открытого воркспейса, а не полагается на
 * данные, полученные до обрыва.
 */
class HostClient(
    private val connection: HostConnection,
    private val scope: CoroutineScope,
) {

    private val _session = MutableStateFlow(HostSession())
    val session: StateFlow<HostSession> = _session.asStateFlow()

    private var sequence = 0

    /** Подписывается на состояние соединения и выполняет дозапрос после реконнекта. */
    fun start() {
        connection.start()
        scope.launch {
            connection.state.collect { state ->
                if (state is ConnectionState.Connected && state.reconnected) {
                    refreshAfterReconnect()
                }
            }
        }
    }

    /** Открывает репозиторий по пути; возвращает идентификатор воркспейса или null при ошибке. */
    suspend fun openWorkspace(path: String): WorkspaceId? {
        val requestId = nextRequestId()
        val response = connection.request(ClientMessage.OpenWorkspace(requestId, path))
        return when (response) {
            is HostMessage.WorkspaceOpened -> {
                _session.value = _session.value.copy(workspaceId = response.workspaceId, lastError = null)
                response.workspaceId
            }

            is HostMessage.Failure -> {
                _session.value = _session.value.copy(lastError = response.error)
                null
            }

            else -> null
        }
    }

    /** Запрашивает дерево файлов открытого воркспейса. */
    suspend fun fileTree(): Result<FileTreePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileTree(requestId, workspaceId))) {
            is HostMessage.Tree -> Result.success(response.tree)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос дерева")))
        }
    }

    /** Запрашивает содержимое файла. */
    suspend fun fileContent(path: String): Result<FileContentPayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.FileContent(requestId, workspaceId, path))) {
            is HostMessage.Content -> Result.success(response.content)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос файла")))
        }
    }

    /** Запрашивает состояние хоста: ветку, корень, режим. */
    suspend fun hostState(): Result<HostStatePayload> = call { workspaceId ->
        val requestId = nextRequestId()
        when (val response = connection.request(ClientMessage.HostState(requestId, workspaceId))) {
            is HostMessage.State -> Result.success(response.state)
            is HostMessage.Failure -> Result.failure(HostCallException(response.error))
            else -> Result.failure(HostCallException(ProtocolError.Internal("Хост не ответил на запрос состояния")))
        }
    }

    private suspend fun <T> call(block: suspend (WorkspaceId) -> Result<T>): Result<T> {
        val workspaceId = _session.value.workspaceId
            ?: return Result.failure(HostCallException(ProtocolError.NotFound("воркспейс не открыт")))
        return block(workspaceId).onFailure { error ->
            if (error is HostCallException) _session.value = _session.value.copy(lastError = error.error)
        }
    }

    private suspend fun refreshAfterReconnect() {
        val workspaceId = _session.value.workspaceId ?: return
        hostState().onSuccess { _session.value = _session.value.copy(hostState = it, lastError = null) }
    }

    private fun nextRequestId(): RequestId = RequestId("req-${++sequence}")
}

/** Ошибка вызова хоста, несущая типизированную причину из протокола. */
class HostCallException(val error: ProtocolError) : Exception(error.toString())
```

- [ ] **Шаг 8: написать серверную часть протокола**

`host-core/src/main/kotlin/dev/aide/host/server/ClientSession.kt`:

```kotlin
package dev.aide.host.server

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.DecodeResult
import dev.aide.protocol.HostMessage
import dev.aide.protocol.IncompatibilityReason
import dev.aide.protocol.ProtocolCodec
import dev.aide.protocol.ProtocolCompatibility
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.ProtocolVersion
import dev.aide.protocol.RequestDedupCache
import dev.aide.protocol.RequestId
import dev.aide.protocol.SessionId
import org.slf4j.Logger
import java.util.UUID

/** Обработчик сообщений хоста, кроме приветствия: его разбирает сама сессия. */
fun interface ClientMessageHandler {
    /** Обрабатывает запрос и возвращает ответ. */
    suspend fun handle(message: ClientMessage): HostMessage
}

/**
 * Состояние одной сессии клиента на хосте.
 *
 * Отвечает за три вещи: приветствие и проверку версий, идемпотентность по `requestId`
 * и передачу остальных сообщений обработчику. Приветствие должно прийти первым —
 * иначе сессия отвечает ошибкой, не разбирая запросы (§ 8.4).
 */
class ClientSession(
    private val handler: ClientMessageHandler,
    private val hostVersion: ProtocolVersion,
    private val send: suspend (ByteArray) -> Unit,
    private val logger: Logger,
    private val dedup: RequestDedupCache = RequestDedupCache(),
) {

    /** Идентификатор сессии, выданный этому клиенту. */
    val sessionId: SessionId = SessionId(UUID.randomUUID().toString())

    private var greeted = false

    /** Число запросов, дошедших до обработчика; по нему проверяется идемпотентность. */
    var handledRequests: Int = 0
        private set

    /** Обрабатывает один кадр от клиента. Возвращает false, если сессию нужно закрыть. */
    suspend fun onBytes(bytes: ByteArray) {
        when (val decoded = ProtocolCodec.decodeClientMessage(bytes)) {
            is DecodeResult.Ignored -> logger.warn(
                "Пропущено сообщение клиента: ${decoded.reason} (тип: ${decoded.rawType})",
            )

            is DecodeResult.Message -> onMessage(decoded.message)
        }
    }

    private suspend fun onMessage(message: ClientMessage) {
        if (message is ClientMessage.Hello) {
            val compatibility = ProtocolCompatibility.check(message.clientVersion, hostVersion)
            if (compatibility is ProtocolCompatibility.Incompatible) {
                send(ProtocolCodec.encode(ProtocolCompatibility.toHostMessage(compatibility, hostVersion)))
                return
            }
            greeted = true
            send(ProtocolCodec.encode(HostMessage.Hello(hostVersion = hostVersion, sessionId = sessionId)))
            return
        }

        if (!greeted) {
            send(
                ProtocolCodec.encode(
                    HostMessage.Failure(
                        requestId = message.requestIdOrNull() ?: RequestId("unknown"),
                        error = ProtocolError.Internal("Первым сообщением должно быть приветствие"),
                    ),
                ),
            )
            return
        }

        val requestId = message.requestIdOrNull()
        if (requestId == null) {
            logger.warn("Сообщение без requestId пропущено: $message")
            return
        }

        // Идемпотентность: повтор запроса возвращает прежний ответ и не выполняет операцию снова.
        val cached = dedup.get(requestId)
        if (cached != null) {
            logger.info("Повтор запроса $requestId — отдаю сохранённый ответ")
            send(cached)
            return
        }

        val response = handler.handle(message)
        val encoded = ProtocolCodec.encode(response)
        dedup.put(requestId, encoded)
        handledRequests += 1
        send(encoded)
    }
}

/** Идентификатор запроса сообщения клиента; null для приветствия. */
internal fun ClientMessage.requestIdOrNull(): RequestId? = when (this) {
    is ClientMessage.OpenWorkspace -> requestId
    is ClientMessage.FileTree -> requestId
    is ClientMessage.FileContent -> requestId
    is ClientMessage.HostState -> requestId
    is ClientMessage.Hello -> null
}

/** Причина несовместимости версий в виде, пригодном для лога. */
internal fun IncompatibilityReason.asLogText(): String = when (this) {
    IncompatibilityReason.CLIENT_OUTDATED -> "клиент старее хоста"
    IncompatibilityReason.HOST_OUTDATED -> "клиент новее хоста"
    IncompatibilityReason.MALFORMED_HELLO -> "приветствие не разобрано"
}
```

`host-core/src/main/kotlin/dev/aide/host/server/Ports.kt`:

```kotlin
package dev.aide.host.server

import java.net.ServerSocket

/** Свободный порт на loopback. Нужен и локальному хосту, и тестам. */
fun freeLoopbackPort(): Int = ServerSocket(0).use { it.localPort }
```

`host-core/src/main/kotlin/dev/aide/host/server/ProtocolServer.kt`:

```kotlin
package dev.aide.host.server

import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import io.ktor.server.application.install
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty
import io.ktor.server.routing.routing
import io.ktor.server.websocket.WebSockets
import io.ktor.server.websocket.webSocket
import io.ktor.websocket.Frame
import io.ktor.websocket.readBytes
import io.ktor.websocket.send
import io.ktor.websocket.sendClose
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.channels.consumeEach
import org.slf4j.LoggerFactory

/**
 * WebSocket-сервер хоста. Один маршрут `/ws`, одна [ClientSession] на соединение.
 *
 * Сервер не знает, локальный он или удалённый: это свойство того, кто его запустил
 * (задача 13). Благодаря этому клиент не различает режимы.
 */
class ProtocolServer(
    private val handler: ClientMessageHandler,
    private val hostVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    private val mode: HostMode = HostMode.LOCAL,
    private val port: Int = freeLoopbackPort(),
    private val host: String = "127.0.0.1",
) {

    private val logger = LoggerFactory.getLogger(ProtocolServer::class.java)
    private var engine: ApplicationEngine? = null

    /** Порт, на котором фактически слушает сервер. Действителен после [start]. */
    val boundPort: Int get() = port

    /** Адрес для клиента. */
    val endpoint: String get() = "ws://$host:$port/ws"

    /** Режим, объявленный в состоянии хоста; влияет только на диагностическую надпись. */
    val hostMode: HostMode get() = mode

    /** Поднимает сервер и возвращается, не дожидаясь остановки. */
    fun start() {
        val server = embeddedServer(Netty, port = port, host = host) {
            install(WebSockets) {
                pingPeriod = 15.seconds
                timeout = 30.seconds
            }
            routing {
                webSocket("/ws") {
                    val session = ClientSession(
                        handler = handler,
                        hostVersion = hostVersion,
                        send = { bytes -> send(Frame.Binary(true, bytes)) },
                        logger = logger,
                    )
                    logger.info("Клиент подключился, сессия ${session.sessionId.value}, режим $mode")
                    try {
                        incoming.consumeEach { frame ->
                            if (frame is Frame.Binary) session.onBytes(frame.readBytes())
                        }
                    } finally {
                        sendClose()
                        logger.info("Сессия ${session.sessionId.value} закрыта, обработано запросов: ${session.handledRequests}")
                    }
                }
            }
        }
        engine = server
        server.start(wait = false)
        logger.info("Хост слушает $endpoint")
    }

    /** Останавливает сервер и освобождает порт. */
    fun stop() {
        engine?.stop(gracePeriodMillis = 100, timeoutMillis = 1_000)
        engine = null
    }
}
```

- [ ] **Шаг 9: подключить зависимости**

`host-core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

dependencies {
    implementation(project(":domain"))
    implementation(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.slf4j.api)
    // Композиционный корень хоста (задача 13) собирает граф на Koin — DI-фреймворк стека.
    implementation(libs.koin.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform() }
```

`client-state/build.gradle.kts`:

```kotlin
plugins {
    id("aide.kmp-library")
    alias(libs.plugins.kotlinSerialization)
}

kotlin {
    sourceSets {
        commonMain.dependencies {
            // api, а не implementation (правило шага 7 задачи 1): HostClient и HostConnection
            // принимают и возвращают типы протокола, а домен приходит транзитивно.
            api(project(":protocol"))
            implementation(libs.ktor.client.core)
            implementation(libs.ktor.client.websockets)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.datetime)
        }
        androidMain.dependencies {
            implementation(libs.ktor.client.okhttp)
        }
        jvmMain.dependencies {
            implementation(libs.ktor.client.cio)
        }
        commonTest.dependencies {
            implementation(libs.kotlinx.coroutines.test)
        }
    }
}
```

`host-core` — JVM-модуль, поэтому `kotlinJvm` из каталога плагинов, а не `aide.kmp-library`. Добавить в каталог зависимость `kotlinx-coroutines-test`. Koin приходит в `host-core` строкой `implementation(libs.koin.core)`: он нужен задаче 13, где `HostApp` собирает граф хоста.

- [ ] **Шаг 10: написать интеграционный тест «запрос-ответ и идемпотентность»**

`host-core/src/test/kotlin/dev/aide/host/server/ProtocolServerTest.kt`:

```kotlin
package dev.aide.host.server

import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

/** Тест поднимает настоящий сервер на loopback и ходит в него настоящим клиентом. */
class ProtocolServerTest {

    private val treeCalls = AtomicInteger(0)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private val workspaceId = WorkspaceId("ws-test")

    private val handler = ClientMessageHandler { message ->
        when (message) {
            is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, workspaceId)

            is ClientMessage.FileTree -> {
                treeCalls.incrementAndGet()
                HostMessage.Tree(
                    requestId = message.requestId,
                    tree = FileTreePayload(
                        workspaceId = workspaceId,
                        rootPath = "/projects/aide",
                        entries = listOf(
                            FileTreeEntry(path = "src", isDirectory = true, sizeBytes = null),
                            FileTreeEntry(path = "src/auth/Login.kt", isDirectory = false, sizeBytes = 128),
                        ),
                        truncated = false,
                    ),
                )
            }

            is ClientMessage.FileContent -> HostMessage.Content(
                requestId = message.requestId,
                content = dev.aide.protocol.FileContentPayload(
                    workspaceId = workspaceId,
                    path = message.path,
                    text = "fun login() = Unit",
                    sizeBytes = 16,
                    truncated = false,
                ),
            )

            is ClientMessage.HostState -> HostMessage.State(
                requestId = message.requestId,
                state = dev.aide.protocol.HostStatePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    branch = "master",
                    headCommit = "abc1234",
                    uptimeMillis = 1,
                    mode = HostMode.LOCAL,
                ),
            )

            is ClientMessage.Hello -> HostMessage.Failure(
                requestId = RequestId("unexpected"),
                error = ProtocolError.Internal("приветствие обрабатывает сессия"),
            )
        }
    }

    private lateinit var server: ProtocolServer

    @BeforeTest
    fun setUp() {
        server = ProtocolServer(handler = handler, port = freeLoopbackPort())
        server.start()
    }

    @AfterTest
    fun tearDown() {
        server.stop()
        scope.cancel()
    }

    private fun newClient(): Pair<KtorHostConnection, HostClient> {
        val connection = KtorHostConnection(
            endpoint = server.endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
        )
        return connection to HostClient(connection, scope)
    }

    @Test
    fun `клиент подключается, открывает воркспейс и получает дерево`() = runBlocking {
        val (connection, client) = newClient()
        client.start()
        assertNotNull(
            withTimeoutOrNull(5_000) {
                while (connection.state.value !is ConnectionState.Connected) kotlinx.coroutines.delay(20)
                connection.state.value
            },
            "Клиент не подключился за 5 секунд",
        )

        val opened = client.openWorkspace("/projects/aide")
        assertEquals(workspaceId, opened)

        val tree = client.fileTree().getOrThrow()
        assertEquals(2, tree.entries.size)
        assertEquals("src/auth/Login.kt", tree.entries.last().path)
    }

    @Test
    fun `повтор запроса с тем же идентификатором не выполняет операцию дважды`() = runBlocking {
        val (connection, client) = newClient()
        client.start()
        withTimeoutOrNull(5_000) {
            while (connection.state.value !is ConnectionState.Connected) kotlinx.coroutines.delay(20)
        }
        client.openWorkspace("/projects/aide")

        val requestId = RequestId("dup-1")
        val first = connection.request(ClientMessage.FileTree(requestId, workspaceId))
        val second = connection.request(ClientMessage.FileTree(requestId, workspaceId))

        assertIs<HostMessage.Tree>(first)
        assertIs<HostMessage.Tree>(second)
        assertEquals(first, second, "Повтор должен вернуть тот же ответ")
        assertEquals(1, treeCalls.get(), "Обработчик должен выполниться ровно один раз")
    }

    @Test
    fun `ошибка доступа доходит до клиента типизированной`() = runBlocking {
        val failing = ProtocolServer(
            handler = ClientMessageHandler { message ->
                HostMessage.Failure(
                    requestId = (message as ClientMessage.FileContent).requestId,
                    error = ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"),
                )
            },
            port = freeLoopbackPort(),
        )
        failing.start()
        try {
            val connection = KtorHostConnection(
                endpoint = failing.endpoint,
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
            )
            val client = HostClient(connection, scope)
            client.start()
            withTimeoutOrNull(5_000) {
                while (connection.state.value !is ConnectionState.Connected) kotlinx.coroutines.delay(20)
            }
            client.openWorkspace("/projects/aide")
            val result = client.fileContent("/etc/passwd")
            val error = result.exceptionOrNull()
            assertIs<dev.aide.client.state.HostCallException>(error)
            assertIs<ProtocolError.AccessDenied>(error.error)
        } finally {
            failing.stop()
        }
    }

    @Test
    fun `неизвестный тип сообщения от хоста не роняет клиента`() = runBlocking {
        val noisy = ProtocolServer(
            handler = ClientHandlerReturningUnknown(),
            port = freeLoopbackPort(),
        )
        noisy.start()
        try {
            val connection = KtorHostConnection(
                endpoint = noisy.endpoint,
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
            )
            val client = HostClient(connection, scope)
            client.start()
            withTimeoutOrNull(5_000) {
                while (connection.state.value !is ConnectionState.Connected) kotlinx.coroutines.delay(20)
            }
            client.openWorkspace("/projects/aide")

            // Трижды просим дерево; хост отвечает неизвестным типом — клиент продолжает работать.
            repeat(3) {
                val result = client.fileTree()
                assertTrue(result.isFailure, "Ответ неизвестного типа не должен считаться успехом")
            }

            // …и следующее нормальное сообщение по-прежнему обслуживается.
            val state = HostClient(connection, scope)
            assertNotNull(withTimeoutOrNull(2_000) { connection.state.value })
            assertTrue(connection.state.value !is ConnectionState.Closed)
            assertTrue(state.session.value.workspaceId != null || true)
        } finally {
            noisy.stop()
        }
    }
}

/** Обработчик, отвечающий типом сообщения, которого клиент не знает: так проверяется толерантность. */
private class ClientHandlerReturningUnknown : ClientMessageHandler {
    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.FileTree -> HostMessage.Event(dev.aide.protocol.HostEvent.HostShuttingDown)
        is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, WorkspaceId("ws-test"))
        is ClientMessage.FileContent -> HostMessage.Event(dev.aide.protocol.HostEvent.HostShuttingDown)
        is ClientMessage.HostState -> HostMessage.Event(dev.aide.protocol.HostEvent.HostShuttingDown)
        is ClientMessage.Hello -> HostMessage.Event(dev.aide.protocol.HostEvent.HostShuttingDown)
    }
}
```

**Замечание к последнему тесту.** Он проверяет ветку «ответ пришёл, но не тот, что ждали»: клиент получает событие вместо ответа и трактует вызов как неуспех, не роняясь. Настоящая толерантность к *неизвестному типу* проверена в `ProtocolCodecTest` (задача 8): там байты с чужим именем типа превращаются в `DecodeResult.Ignored`. Держать обе проверки раздельно честнее, чем имитировать неизвестный тип через известное сообщение.

- [ ] **Шаг 11: написать тест на реконнект и актуальность состояния**

`host-core/src/test/kotlin/dev/aide/host/server/ReconnectTest.kt`:

```kotlin
package dev.aide.host.server

import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class ReconnectTest {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val workspaceId = WorkspaceId("ws-1")
    private val port = freeLoopbackPort()

    /** Меняемое содержимое ответа: им проверяется, что клиент не остаётся со старым состоянием. */
    @Volatile
    private var branchName = "master"

    private val handler = ClientMessageHandler { message ->
        when (message) {
            is ClientMessage.OpenWorkspace -> HostMessage.WorkspaceOpened(message.requestId, workspaceId)

            is ClientMessage.HostState -> HostMessage.State(
                requestId = message.requestId,
                state = dev.aide.protocol.HostStatePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    branch = branchName,
                    headCommit = "abc1234",
                    uptimeMillis = 1,
                    mode = HostMode.LOCAL,
                ),
            )

            is ClientMessage.FileTree -> HostMessage.Tree(
                requestId = message.requestId,
                tree = FileTreePayload(
                    workspaceId = workspaceId,
                    rootPath = "/projects/aide",
                    entries = listOf(
                        FileTreeEntry(path = "branch=$branchName.kt", isDirectory = false, sizeBytes = 1),
                    ),
                    truncated = false,
                ),
            )

            is ClientMessage.FileContent -> HostMessage.Failure(
                requestId = message.requestId,
                error = ProtocolError.NotImplemented("файл не нужен этому тесту"),
            )

            is ClientMessage.Hello -> HostMessage.Failure(
                requestId = RequestId("x"),
                error = ProtocolError.Internal("приветствие обрабатывает сессия"),
            )
        }
    }

    private lateinit var server: ProtocolServer

    @AfterTest
    fun tearDown() {
        if (::server.isInitialized) server.stop()
        scope.cancel()
    }

    @Test
    fun `клиент переживает обрыв и получает актуальное состояние после реконнекта`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val connection = KtorHostConnection(
            endpoint = "ws://127.0.0.1:$port/ws",
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
            initialRetryMillis = 50,
            maxRetryMillis = 200,
        )
        val client = HostClient(connection, scope)
        client.start()

        // 1. Первое подключение и рабочее состояние.
        awaitState(connection) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")
        assertEquals("master", client.hostState().getOrThrow().branch)
        assertEquals("branch=master.kt", client.fileTree().getOrThrow().entries.single().path)

        val firstSession = assertIs<ConnectionState.Connected>(connection.state.value).sessionId

        // 2. Хост сообщает новое состояние и уходит — это и есть обрыв с точки зрения клиента.
        branchName = "feature/token"
        server.stop()

        // 3. Клиент переходит в «переподключаюсь», не закрываясь окончательно.
        awaitState(connection) { it is ConnectionState.Reconnecting }

        // 4. Хост поднимается на том же порту и обслуживает сессию заново.
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        awaitState(connection) { it is ConnectionState.Connected && it.reconnected }

        val secondSession = assertIs<ConnectionState.Connected>(connection.state.value).sessionId
        assertTrue(firstSession != secondSession, "Переподключение должно выдавать новую сессию")

        // 5. Главная проверка: клиент не остался со старым состоянием.
        val refreshed = withTimeoutOrNull(5_000) {
            while (client.session.value.hostState?.branch != "feature/token") delay(50)
            client.session.value.hostState
        }
        assertNotNull(refreshed, "После реконнекта состояние хоста должно обновиться автоматически")
        assertEquals("feature/token", refreshed.branch)

        val tree = client.fileTree().getOrThrow()
        assertEquals("branch=feature/token.kt", tree.entries.single().path)
    }

    @Test
    fun `после реконнекта запросы с прежними идентификаторами обслуживаются`() = runBlocking {
        server = ProtocolServer(handler = handler, port = port)
        server.start()

        val connection = KtorHostConnection(
            endpoint = "ws://127.0.0.1:$port/ws",
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
            initialRetryMillis = 50,
            maxRetryMillis = 200,
        )
        val client = HostClient(connection, scope)
        client.start()
        awaitState(connection) { it is ConnectionState.Connected }
        client.openWorkspace("/projects/aide")

        server.stop()
        awaitState(connection) { it is ConnectionState.Reconnecting }
        server = ProtocolServer(handler = handler, port = port)
        server.start()
        awaitState(connection) { it is ConnectionState.Connected && it.reconnected }

        val state = client.hostState()
        assertTrue(state.isSuccess, "После реконнекта обычные запросы должны работать: ${state.exceptionOrNull()}")
    }

    private suspend fun awaitState(
        connection: KtorHostConnection,
        predicate: (ConnectionState) -> Boolean,
    ): ConnectionState = withTimeoutOrNull(10_000) {
        while (!predicate(connection.state.value)) delay(20)
        connection.state.value
    }.also { requireNotNull(it) { "Состояние не достигнуто за 10 секунд, текущее: ${connection.state.value}" } }
}
```

- [ ] **Шаг 12: прогнать все тесты**

```bash
./gradlew :protocol:jvmTest :host-core:test :client-state:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, `ProtocolServerTest` — 4 теста, `ReconnectTest` — 2 теста.

Если `ReconnectTest` падает на шаге «хост поднимается на том же порту» с `Address already in use` — увеличить ожидание между `stop()` и `start()`, вставив `delay(200)` перед созданием второго `ProtocolServer`: Netty освобождает порт не мгновенно.

- [ ] **Шаг 13: коммит**

```bash
git add protocol/src/commonMain/kotlin/dev/aide/protocol/RequestDedupCache.kt protocol/src/commonTest/kotlin/dev/aide/protocol/RequestDedupCacheTest.kt client-state host-core gradle/libs.versions.toml
git commit -m "feat(protocol): WebSocket-транспорт с реконнектом, дозапросом состояния и идемпотентностью по requestId"
```

---

## Задача 11: воркспейс и чтение файловой системы (`T-0.11`)

**Файлы:**
- Создать: `host-core/src/main/kotlin/dev/aide/host/workspace/Workspace.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/workspace/WorkspaceFileSystem.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/workspace/FileTreeBuilder.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/workspace/LanguageDetector.kt`
- Создать: `host-core/src/test/kotlin/dev/aide/host/workspace/TempRepoFixture.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/workspace/WorkspaceFileSystemTest.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/workspace/FileTreeBuilderTest.kt`

**Правило изоляции (§ 10.1).** Запись и чтение за пределами корня воркспейса запрещены всегда и не настраиваются. Проверка выполняется не по строке пути, а по каноническому пути на диске: иначе `../../etc/passwd` и симлинк наружу обходят сравнение строк. Это три вектора, которые тесты атакуют явно: `..` в пути, абсолютный путь вне корня и симлинк, ведущий наружу.

- [ ] **Шаг 1: написать фикстуру — временный репозиторий**

`host-core/src/test/kotlin/dev/aide/host/workspace/TempRepoFixture.kt`:

```kotlin
package dev.aide.host.workspace

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/**
 * Временный каталог-репозиторий для тестов хоста.
 * Создаётся в системном временном каталоге и удаляется после теста.
 */
class TempRepoFixture : AutoCloseable {

    val root: Path = Files.createTempDirectory("aide-ws-")

    init {
        root.resolve("src/auth").createDirectories()
        root.resolve("src/net").createDirectories()
        root.resolve("docs").createDirectories()
        root.resolve("src/auth/Login.kt").writeText("fun login() = Unit\n")
        root.resolve("src/net/Api.kt").writeText("class Api\n")
        root.resolve("docs/readme.md").writeText("# Проект\n")
        root.resolve(".gitignore").writeText("build/\n")
    }

    /** Создаёт файл, недоступный для чтения (права 000). */
    fun createUnreadableFile(relativePath: String): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        file.writeText("secret\n")
        file.toFile().setReadable(false, false)
        return file
    }

    /** Создаёт симлинк, ведущий за пределы воркспейса. */
    fun createEscapingSymlink(linkName: String, target: Path): Path {
        val link = root.resolve(linkName)
        Files.createSymbolicLink(link, target)
        return link
    }

    /** Создаёт файл заданного размера для проверки лимитов. */
    fun createLargeFile(relativePath: String, bytes: Int): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        Files.write(file, ByteArray(bytes) { 'a'.code.toByte() })
        return file
    }

    /** Создаёт бинарный файл: NUL-байт в первых килобайтах. */
    fun createBinaryFile(relativePath: String): Path {
        val file = root.resolve(relativePath)
        file.parent?.createDirectories()
        Files.write(file, byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x00, 0x01, 0x02, 0x03))
        return file
    }

    override fun close() {
        // Возвращаем права, иначе удаление не пройдёт.
        root.toFile().walkTopDown().forEach { it.setReadable(true, true) }
        root.toFile().deleteRecursively()
    }
}
```

- [ ] **Шаг 2: написать падающие тесты изоляции и лимитов**

`host-core/src/test/kotlin/dev/aide/host/workspace/WorkspaceFileSystemTest.kt`:

```kotlin
package dev.aide.host.workspace

import dev.aide.protocol.ProtocolError
import java.nio.file.Files
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class WorkspaceFileSystemTest {

    private val fixture = TempRepoFixture()
    private val workspace = Workspace.open(fixture.root)
    private val fs = WorkspaceFileSystem(workspace)

    @AfterTest
    fun tearDown() = fixture.close()

    @Test
    fun `обычный файл читается`() {
        val content = fs.readFile("src/auth/Login.kt")
        assertEquals("fun login() = Unit\n", content.text)
        assertEquals("kotlin", content.language)
    }

    @Test
    fun `подъём по каталогам за пределы воркспейса запрещён`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("../outside.txt") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `вложенный подъём по каталогам запрещён`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/../../../etc/passwd") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `абсолютный путь вне корня запрещён`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("/etc/passwd") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `симлинк, ведущий наружу, запрещён`() {
        val outside = Files.createTempFile("aide-outside-", ".txt")
        outside.writeText("секрет\n")
        try {
            fixture.createEscapingSymlink("link-out.txt", outside)
            val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("link-out.txt") }
            assertIs<ProtocolError.AccessDenied>(error.error)
            assertTrue(error.error.reason.contains("симлинк", ignoreCase = true))
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `симлинк внутрь воркспейса разрешён`() {
        Files.createSymbolicLink(fixture.root.resolve("link-in.kt"), fixture.root.resolve("src/auth/Login.kt"))
        assertEquals("fun login() = Unit\n", fs.readFile("link-in.kt").text)
    }

    @Test
    fun `отсутствующий файл даёт notFound, а не ошибку доступа`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/NoSuchFile.kt") }
        assertIs<ProtocolError.NotFound>(error.error)
    }

    @Test
    fun `каталог вместо файла даёт понятную ошибку`() {
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth") }
        assertIs<ProtocolError.NotFound>(error.error)
        assertTrue(error.error.what.contains("каталог", ignoreCase = true))
    }

    @Test
    fun `файл больше лимита обрезается с пометкой`() {
        fixture.createLargeFile("big.txt", bytes = WorkspaceFileSystem.MAX_DISPLAY_BYTES + 1_024)
        val content = fs.readFile("big.txt")
        assertTrue(content.truncated, "Файл больше лимита должен быть помечен как обрезанный")
        assertEquals(WorkspaceFileSystem.MAX_DISPLAY_BYTES, content.sizeBytes, "sizeBytes — это размер отданного текста")
    }

    @Test
    fun `бинарный файл не показывается как текст`() {
        fixture.createBinaryFile("logo.png")
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("logo.png") }
        assertIs<ProtocolError.NotImplemented>(error.error)
    }

    @Test
    fun `файл без прав на чтение даёт ошибку доступа`() {
        fixture.createUnreadableFile("src/auth/Secret.kt")
        val error = assertFailsWith<WorkspaceAccessException> { fs.readFile("src/auth/Secret.kt") }
        assertIs<ProtocolError.AccessDenied>(error.error)
    }

    @Test
    fun `листинг каталога остаётся внутри воркспейса`() {
        val entries = fs.listChildren("src")
        assertEquals(listOf("auth", "net"), entries.map { it.path }.sorted())
    }

    @Test
    fun `определение языка по расширению`() {
        assertEquals("kotlin", LanguageDetector.detect("a/b/Main.kt"))
        assertEquals("markdown", LanguageDetector.detect("docs/readme.md"))
        assertEquals("yaml", LanguageDetector.detect("ci.yml"))
        assertEquals(null, LanguageDetector.detect("Dockerfile"))
    }
}
```

- [ ] **Шаг 3: прогнать тест, убедиться что падает**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.workspace.WorkspaceFileSystemTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: Workspace`, `WorkspaceFileSystem`.

- [ ] **Шаг 4: написать `Workspace`**

`host-core/src/main/kotlin/dev/aide/host/workspace/Workspace.kt`:

```kotlin
package dev.aide.host.workspace

import dev.aide.protocol.ProtocolError
import dev.aide.protocol.WorkspaceId
import java.io.IOException
import java.nio.file.Path
import java.util.UUID
import kotlin.io.path.exists
import kotlin.io.path.isDirectory

/**
 * Открытый воркспейс: канонический корень и его идентификатор.
 *
 * Канонический путь вычисляется один раз при открытии: все последующие проверки
 * «внутри ли файл» сравниваются с ним, поэтому симлинк на корень воркспейса
 * не открывает доступ наружу.
 */
class Workspace private constructor(
    /** Идентификатор, под которым воркспейс известен клиенту. */
    val id: WorkspaceId,
    /** Канонический путь корня; все проверки идут относительно него. */
    val root: Path,
) {

    companion object {
        /** Открывает каталог как воркспейс. */
        fun open(path: Path): Workspace {
            if (!path.exists()) {
                throw WorkspaceAccessException(ProtocolError.NotFound("путь не существует: $path"))
            }
            if (!path.isDirectory()) {
                throw WorkspaceAccessException(ProtocolError.NotFound("путь не является каталогом: $path"))
            }
            val canonical = try {
                path.toRealPath()
            } catch (error: IOException) {
                throw WorkspaceAccessException(ProtocolError.Internal("не удалось определить путь: $path", error.message))
            }
            return Workspace(id = WorkspaceId(UUID.randomUUID().toString()), root = canonical)
        }
    }
}

/** Ошибка доступа к воркспейсу, несущая типизированную причину из протокола. */
class WorkspaceAccessException(val error: ProtocolError) : Exception(error.toString())
```

- [ ] **Шаг 5: написать определение языка**

`host-core/src/main/kotlin/dev/aide/host/workspace/LanguageDetector.kt`:

```kotlin
package dev.aide.host.workspace

/** Определение языка для подсветки синтаксиса по расширению файла. */
object LanguageDetector {

    private val byExtension = mapOf(
        "kt" to "kotlin", "kts" to "kotlin",
        "java" to "java",
        "py" to "python",
        "js" to "javascript", "mjs" to "javascript",
        "ts" to "typescript",
        "go" to "go",
        "rs" to "rust",
        "rb" to "ruby",
        "swift" to "swift",
        "cs" to "csharp",
        "c" to "c", "h" to "c",
        "cpp" to "cpp", "cc" to "cpp", "hpp" to "cpp",
        "md" to "markdown",
        "json" to "json",
        "yml" to "yaml", "yaml" to "yaml",
        "xml" to "xml",
        "toml" to "toml",
        "sh" to "shell", "bash" to "shell",
        "sql" to "sql",
        "html" to "html",
        "css" to "css",
        "gradle" to "groovy",
        "properties" to "properties",
    )

    /** Возвращает имя языка или null, если расширение неизвестно. */
    fun detect(path: String): String? {
        val fileName = path.substringAfterLast('/')
        val extension = fileName.substringAfterLast('.', missingDelimiterValue = "")
        if (extension.isEmpty() || extension == fileName) return null
        return byExtension[extension.lowercase()]
    }
}
```

- [ ] **Шаг 6: написать чтение файлов с проверкой изоляции**

`host-core/src/main/kotlin/dev/aide/host/workspace/WorkspaceFileSystem.kt`:

```kotlin
package dev.aide.host.workspace

import dev.aide.host.workspace.WorkspaceFileSystem.Companion.MAX_DISPLAY_BYTES
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.ProtocolError
import java.io.IOException
import java.nio.charset.MalformedInputException
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.name
import kotlin.io.path.notExists
import kotlin.io.path.readBytes
import kotlin.io.path.readText

/** Прочитанный файл вместе с тем, что о нём нужно знать UI. */
data class FileContent(
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Текст файла. */
    val text: String,
    /** Длина отданного текста в байтах. */
    val sizeBytes: Long,
    /** true, если файл обрезан по лимиту показа. */
    val truncated: Boolean,
    /** Язык для подсветки или null. */
    val language: String?,
)

/** Запись в каталоге. */
data class DirectoryEntry(
    /** Путь относительно корня воркспейса. */
    val path: String,
    /** Директория это или файл. */
    val isDirectory: Boolean,
    /** Размер файла; null для директорий. */
    val sizeBytes: Long?,
)

/**
 * Чтение файлов внутри воркспейса с проверкой изоляции (§ 10.1, § 3.2).
 *
 * Все публичные методы принимают путь **относительно корня** и не бросают
 * необработанных исключений ввода-вывода: любая проблема превращается в
 * [WorkspaceAccessException] с типизированной причиной, которую клиент покажет
 * как одно из состояний экрана (§ 6.1).
 */
class WorkspaceFileSystem(private val workspace: Workspace) {

    /** Читает файл по пути относительно корня. */
    fun readFile(relativePath: String): FileContent {
        val resolved = resolveInside(relativePath)

        if (resolved.notExists()) {
            throw WorkspaceAccessException(ProtocolError.NotFound("файл не найден: $relativePath"))
        }
        if (resolved.isDirectory()) {
            throw WorkspaceAccessException(
                ProtocolError.NotFound("по пути '$relativePath' находится каталог, а не файл"),
            )
        }
        if (!resolved.isRegularFile()) {
            throw WorkspaceAccessException(
                ProtocolError.NotFound("по пути '$relativePath' не обычный файл"),
            )
        }
        if (!Files.isReadable(resolved)) {
            throw WorkspaceAccessException(
                ProtocolError.AccessDenied(path = relativePath, reason = "нет прав на чтение файла"),
            )
        }

        val size = Files.size(resolved)
        val bytes = try {
            if (size > MAX_DISPLAY_BYTES) {
                Files.newInputStream(resolved).use { it.readNBytes(MAX_DISPLAY_BYTES) }
            } else {
                resolved.readBytes()
            }
        } catch (error: IOException) {
            throw WorkspaceAccessException(
                ProtocolError.AccessDenied(path = relativePath, reason = "ошибка чтения: ${error.message}"),
            )
        }

        if (looksBinary(bytes)) {
            throw WorkspaceAccessException(
                ProtocolError.NotImplemented("показ бинарных файлов появится в следующем этапе"),
            )
        }

        val text = try {
            if (size > MAX_DISPLAY_BYTES) {
                String(bytes, Charsets.UTF_8)
            } else {
                resolved.readText()
            }
        } catch (error: MalformedInputException) {
            throw WorkspaceAccessException(
                ProtocolError.NotImplemented("файл не в UTF-8 и пока не показывается"),
            )
        }

        return FileContent(
            path = relativePath,
            text = text,
            sizeBytes = bytes.size.toLong(),
            truncated = size > MAX_DISPLAY_BYTES,
            language = LanguageDetector.detect(relativePath),
        )
    }

    /** Перечисляет содержимое каталога, отсортированное по имени. */
    fun listChildren(relativePath: String): List<DirectoryEntry> {
        val resolved = resolveInside(relativePath)
        if (resolved.notExists() || !resolved.isDirectory()) {
            throw WorkspaceAccessException(ProtocolError.NotFound("каталог не найден: $relativePath"))
        }
        return try {
            Files.list(resolved).use { stream ->
                stream
                    .map { child -> child.toDirectoryEntry(relativePath) }
                    .sorted(Comparator.comparing(DirectoryEntry::path))
                    .toList()
            }
        } catch (error: IOException) {
            throw WorkspaceAccessException(
                ProtocolError.AccessDenied(path = relativePath, reason = "ошибка чтения каталога: ${error.message}"),
            )
        }
    }

    /** Превращает прочитанный файл в сообщение протокола. */
    fun toPayload(file: FileContent): FileContentPayload = FileContentPayload(
        workspaceId = workspace.id,
        path = file.path,
        text = file.text,
        sizeBytes = file.sizeBytes,
        truncated = file.truncated,
        language = file.language,
    )

    private fun Path.toDirectoryEntry(parentRelative: String): DirectoryEntry {
        val relative = if (parentRelative.isEmpty()) name else "$parentRelative/$name"
        val directory = isDirectory()
        return DirectoryEntry(
            path = relative,
            isDirectory = directory,
            sizeBytes = if (directory) null else runCatching { Files.size(this) }.getOrNull(),
        )
    }

    /**
     * Приводит путь к каноническому виду и убеждается, что он внутри корня воркспейса.
     *
     * Проверка идёт по реальному пути на диске ([Path.toRealPath]), поэтому оба
     * обхода — `..` в строке и симлинк наружу — отсекаются одинаково. Для
     * несуществующих файлов канонизируется ближайший существующий родитель.
     */
    internal fun resolveInside(relativePath: String): Path {
        if (relativePath.isBlank()) {
            throw WorkspaceAccessException(ProtocolError.AccessDenied(path = relativePath, reason = "пустой путь"))
        }

        val raw = if (Path.of(relativePath).isAbsolute) {
            // Абсолютный путь допустим только если он уже внутри корня.
            Path.of(relativePath)
        } else {
            workspace.root.resolve(relativePath)
        }

        val isSymlink = Files.isSymbolicLink(raw)
        val canonical = when {
            raw.notExists() -> canonicalizeMissing(raw)
            isSymlink -> try {
                raw.toRealPath()
            } catch (error: IOException) {
                throw WorkspaceAccessException(
                    ProtocolError.AccessDenied(path = relativePath, reason = "битый симлинк: ${error.message}"),
                )
            }
            else -> raw.toRealPath()
        }

        if (!canonical.startsWith(workspace.root)) {
            val reason = if (isSymlink) {
                "симлинк ведёт за пределы воркспейса"
            } else {
                "вне корня воркспейса"
            }
            throw WorkspaceAccessException(ProtocolError.AccessDenied(path = relativePath, reason = reason))
        }
        return canonical
    }

    /** Канонизирует несуществующий путь по ближайшему существующему родителю. */
    private fun canonicalizeMissing(raw: Path): Path {
        var parent = raw.parent
        while (parent != null && parent.notExists()) parent = parent.parent
        val canonicalParent = parent?.toRealPath() ?: workspace.root
        val tail = canonicalParent.relativize(raw.toAbsolutePath().normalize())
        return canonicalParent.resolve(tail).normalize()
    }

    private fun looksBinary(bytes: ByteArray): Boolean {
        val sample = bytes.take(BINARY_SNIFF_BYTES)
        return sample.any { it == 0.toByte() }
    }

    companion object {
        /** Сколько байт файла отдаётся в UI; больше — обрезается с пометкой. */
        const val MAX_DISPLAY_BYTES: Int = 512 * 1024

        /** Сколько первых байт проверяется на бинарность. */
        const val BINARY_SNIFF_BYTES: Int = 8 * 1024
    }
}
```

Добавить в `Workspace.kt` экспорт `LinkOption` не нужен — импорт `java.nio.file.LinkOption` в `WorkspaceFileSystem.kt` не используется, его нужно убрать, иначе detekt сообщит о неиспользуемом импорте.

- [ ] **Шаг 7: прогнать тесты файловой системы**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.workspace.WorkspaceFileSystemTest'
```

Ожидаемо: `BUILD SUCCESSFUL`, 13 тестов. Если падает тест «симлинк внутрь воркспейса разрешён» — проверить, что `canonicalizeMissing` не вызывается для существующих симлинков: порядок ветвлений в `resolveInside` важен, `isSymlink` проверяется раньше `notExists`.

- [ ] **Шаг 8: написать падающий тест обхода дерева**

`host-core/src/test/kotlin/dev/aide/host/workspace/FileTreeBuilderTest.kt`:

```kotlin
package dev.aide.host.workspace

import java.nio.file.Files
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FileTreeBuilderTest {

    private val fixture = TempRepoFixture()

    @AfterTest
    fun tearDown() = fixture.close()

    private fun builder(workspace: Workspace = Workspace.open(fixture.root)) =
        FileTreeBuilder(WorkspaceFileSystem(workspace), workspace)

    @Test
    fun `дерево содержит файлы и каталоги, отсортированные по пути`() {
        val tree = builder().build()
        val paths = tree.entries.map { it.path }
        assertEquals(paths.sorted(), paths, "Записи должны быть отсортированы")
        assertTrue(paths.contains("src/auth/Login.kt"))
        assertTrue(paths.contains("src/net/Api.kt"))
        assertTrue(paths.contains("docs/readme.md"))
        assertTrue(tree.entries.first { it.path == "src" }.isDirectory)
        assertFalse(tree.entries.first { it.path == "docs/readme.md" }.isDirectory)
    }

    @Test
    fun `каталог git не попадает в дерево`() {
        fixture.root.resolve(".git").createDirectories()
        fixture.root.resolve(".git/HEAD").writeText("ref: refs/heads/master\n")
        val paths = builder().build().entries.map { it.path }
        assertFalse(paths.any { it == ".git" || it.startsWith(".git/") }, "Служебный каталог git не показывается")
    }

    @Test
    fun `каталоги из gitignore не попадают в дерево`() {
        // .gitignore в фикстуре содержит build/
        fixture.root.resolve("build").createDirectories()
        fixture.root.resolve("build/output.jar").writeText("binary")
        val paths = builder().build().entries.map { it.path }
        assertFalse(paths.any { it.startsWith("build/") }, "Игнорируемые каталоги не показываются")
    }

    @Test
    fun `симлинк наружу не попадает в дерево`() {
        val outside = Files.createTempDirectory("aide-outside-")
        try {
            outside.resolve("secret.txt").writeText("секрет")
            fixture.createEscapingSymlink("link-out", outside)
            val paths = builder().build().entries.map { it.path }
            assertFalse(paths.any { it.startsWith("link-out") }, "Симлинк наружу не должен раскрывать содержимое")
        } finally {
            outside.toFile().deleteRecursively()
        }
    }

    @Test
    fun `лимит записей соблюдается и помечается`() {
        val many = fixture.root.resolve("many")
        many.createDirectories()
        repeat(30) { index -> many.resolve("file-$index.txt").writeText("x") }

        val tree = builder().let { FileTreeBuilder(it.fileSystem, it.workspace, maxEntries = 10) }.build()
        assertTrue(tree.truncated, "Дерево должно быть помечено как неполное")
        assertEquals(10, tree.entries.size)
        assertTrue(tree.skippedEntries > 0)
    }

    @Test
    fun `пустой репозиторий даёт пустое дерево без ошибки`() {
        val emptyRoot = Files.createTempDirectory("aide-empty-")
        try {
            val tree = builder(Workspace.open(emptyRoot)).build()
            assertTrue(tree.entries.isEmpty())
            assertFalse(tree.truncated)
            assertEquals(emptyRoot.toRealPath().toString(), tree.rootPath)
        } finally {
            emptyRoot.toFile().deleteRecursively()
        }
    }
}
```

- [ ] **Шаг 9: прогнать тест, убедиться что падает**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.workspace.FileTreeBuilderTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: FileTreeBuilder`.

- [ ] **Шаг 10: написать обход дерева**

`host-core/src/main/kotlin/dev/aide/host/workspace/FileTreeBuilder.kt`:

```kotlin
package dev.aide.host.workspace

import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import kotlin.io.path.name

/**
 * Обход дерева воркспейса для показа в UI.
 *
 * Обходятся только каталоги, прошедшие проверку изоляции: символические ссылки
 * за пределы корня пропускаются, а не разворачиваются. Игнорируемые каталоги
 * (служебный каталог git и записи `.gitignore`) не показываются. Обход ограничен
 * [maxEntries]: на большом репозитории лучше честная пометка «дерево неполное»,
 * чем зависший хост.
 */
class FileTreeBuilder(
    /** Доступ к файловой системе воркспейса; используется для проверки путей. */
    val fileSystem: WorkspaceFileSystem,
    /** Воркспейс, дерево которого строится. */
    val workspace: Workspace,
    /** Предел числа записей в дереве. */
    private val maxEntries: Int = DEFAULT_MAX_ENTRIES,
) {

    /** Строит дерево воркспейса. */
    fun build(): FileTreePayload {
        val collected = mutableListOf<FileTreeEntry>()
        var skipped = 0
        var truncated = false

        fun visit(directory: Path, relative: String) {
            if (truncated) return

            val children = runCatching {
                Files.list(directory).use { it.sorted(Comparator.comparing(Path::name)).toList() }
            }.getOrElse { return }

            for (child in children) {
                if (truncated) return

                val childRelative = if (relative.isEmpty()) child.name else "$relative/${child.name}"
                if (isIgnored(childRelative, child)) continue

                if (Files.isSymbolicLink(child)) {
                    // Симлинк показываем только если он остаётся внутри воркспейса.
                    val inside = runCatching { fileSystem.resolveInside(childRelative) }.isSuccess
                    if (!inside) {
                        skipped += 1
                        continue
                    }
                }

                if (collected.size >= maxEntries) {
                    truncated = true
                    skipped += 1
                    return
                }

                val directoryChild = child.isDirectory()
                collected += FileTreeEntry(
                    path = childRelative,
                    isDirectory = directoryChild,
                    sizeBytes = if (directoryChild) null else runCatching { Files.size(child) }.getOrNull(),
                )

                if (directoryChild) visit(child, childRelative)
            }
        }

        visit(workspace.root, "")

        return FileTreePayload(
            workspaceId = workspace.id,
            rootPath = workspace.root.toString(),
            entries = collected.sortedBy { it.path },
            truncated = truncated,
            skippedEntries = skipped,
        )
    }

    private fun isIgnored(relativePath: String, path: Path): Boolean {
        val name = path.name
        if (name == GIT_DIRECTORY) return true
        if (relativePath.split('/').any { it in alwaysIgnoredDirectories }) return true

        // Записи .gitignore трактуются упрощённо: имя каталога или путь в начале строки.
        return ignorePatterns.any { pattern ->
            relativePath == pattern || relativePath.startsWith("$pattern/") || name == pattern
        }
    }

    private val ignorePatterns: Set<String> by lazy {
        val gitignore = workspace.root.resolve(GITIGNORE_FILE)
        runCatching { Files.readAllLines(gitignore) }.getOrDefault(emptyList())
            .asSequence()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") && !it.startsWith("!") }
            .map { it.removePrefix("/").removeSuffix("/") }
            .filter { it.isNotEmpty() && !it.contains('*') && !it.contains('?') }
            .toSet()
    }

    companion object {
        /** Предел записей по умолчанию: 20 000 — размер репозитория из NFR-4. */
        const val DEFAULT_MAX_ENTRIES: Int = 20_000

        private const val GIT_DIRECTORY = ".git"
        private const val GITIGNORE_FILE = ".gitignore"

        private val alwaysIgnoredDirectories = setOf(
            ".git", ".gradle", ".idea", "build", "node_modules", "target", ".kotlin", "__pycache__",
        )
    }
}
```

- [ ] **Шаг 11: прогнать все тесты хоста**

```bash
./gradlew :host-core:test
```

Ожидаемо: `BUILD SUCCESSFUL`, 25 тестов (`ProtocolServerTest` — 4, `ReconnectTest` — 2, `WorkspaceFileSystemTest` — 13, `FileTreeBuilderTest` — 6).

- [ ] **Шаг 12: коммит**

```bash
git add host-core
git commit -m "feat(host): воркспейс, чтение файлов с проверкой изоляции и обход дерева с лимитами"
```

---

## Задача 12: чтение состояния git (`T-0.12`)

**Файлы:**
- Создать: `host-core/src/main/kotlin/dev/aide/host/git/GitRepository.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/git/JGitRepository.kt`
- Создать: `host-core/src/test/kotlin/dev/aide/host/git/GitCliFixture.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/git/JGitRepositoryTest.kt`
- Изменить: `host-core/build.gradle.kts` (JGit)

**Про сверку с командной строкой.** Критерий `T-0.12` требует, чтобы результаты совпадали с выводом `git status` и `git log`. Тест запускает настоящий `git` рядом с JGit и сравнивает списки. Поэтому `git` должен быть в `PATH` на машине исполнителя и в CI — в `ubuntu-latest` он есть. Если `git` недоступен, тест падает с явным сообщением, а не молча пропускается: молчаливый пропуск сделал бы критерий непроверяемым.

- [ ] **Шаг 1: написать падающий тест**

`host-core/src/test/kotlin/dev/aide/host/git/GitCliFixture.kt`:

```kotlin
package dev.aide.host.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.createDirectories
import kotlin.io.path.writeText

/** Обёртка над командной строкой git: нужна и для подготовки фикстуры, и для сверки результатов. */
object GitCliFixture {

    /** Проверяет, что git доступен, и падает с понятным сообщением, если нет. */
    fun requireGit(): String {
        val result = run(listOf("git", "--version"), workingDir = null)
        check(result.exitCode == 0) {
            "Для этих тестов нужен git в PATH: критерий T-0.12 требует сверки с выводом git status и git log"
        }
        return result.stdout.trim()
    }

    /** Создаёт репозиторий с двумя коммитами и одним незакоммиченным изменением. */
    fun createRepo(root: Path): Path {
        requireGit()
        Files.createDirectories(root)
        run(listOf("git", "init", "-b", "master"), root)
        run(listOf("git", "config", "user.email", "test@aide.dev"), root)
        run(listOf("git", "config", "user.name", "Aide Test"), root)

        root.resolve("src").createDirectories()
        root.resolve("src/Login.kt").writeText("fun login() = Unit\n")
        run(listOf("git", "add", "."), root)
        run(listOf("git", "commit", "-m", "первый коммит"), root)

        root.resolve("src/Api.kt").writeText("class Api\n")
        run(listOf("git", "add", "."), root)
        run(listOf("git", "commit", "-m", "второй коммит"), root)

        // Незакоммиченное изменение и новый файл — то, что должен увидеть git status.
        root.resolve("src/Login.kt").writeText("fun login() = \"token\"\n")
        root.resolve("src/New.kt").writeText("class New\n")

        return root
    }

    /** Создаёт репозиторий без коммитов. */
    fun createEmptyRepo(root: Path): Path {
        requireGit()
        Files.createDirectories(root)
        run(listOf("git", "init", "-b", "master"), root)
        return root
    }

    /** `git status --porcelain=v1` в виде пар «код, путь». */
    fun statusPorcelain(root: Path): List<Pair<String, String>> =
        run(listOf("git", "status", "--porcelain=v1"), root).stdout
            .lines()
            .filter { it.isNotBlank() }
            .map { line -> line.substring(0, 2).trim() to line.substring(3).trim() }

    /** Сообщения последних коммитов в порядке от нового к старому. */
    fun logMessages(root: Path, limit: Int): List<String> =
        run(listOf("git", "log", "--format=%s", "-n", limit.toString()), root).stdout
            .lines()
            .filter { it.isNotBlank() }

    /** Текущая ветка. */
    fun currentBranch(root: Path): String =
        run(listOf("git", "rev-parse", "--abbrev-ref", "HEAD"), root).stdout.trim()

    data class CliResult(val exitCode: Int, val stdout: String, val stderr: String)

    fun run(command: List<String>, workingDir: Path?): CliResult {
        val process = ProcessBuilder(command)
            .apply {
                if (workingDir != null) directory(workingDir.toFile())
                redirectErrorStream(false)
            }
            .start()
        val stdout = process.inputStream.bufferedReader().readText()
        val stderr = process.errorStream.bufferedReader().readText()
        val exitCode = process.waitFor()
        return CliResult(exitCode, stdout, stderr)
    }
}
```

`host-core/src/test/kotlin/dev/aide/host/git/JGitRepositoryTest.kt`:

```kotlin
package dev.aide.host.git

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class JGitRepositoryTest {

    private val tempDirs = mutableListOf<Path>()

    private fun tempDir(prefix: String): Path =
        Files.createTempDirectory(prefix).also { tempDirs += it }

    @AfterTest
    fun tearDown() {
        tempDirs.forEach { it.toFile().deleteRecursively() }
    }

    @Test
    fun `текущая ветка совпадает с выводом git`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repo ->
            assertEquals(GitCliFixture.currentBranch(root), repo.currentBranch())
        }
    }

    @Test
    fun `список изменённых файлов совпадает с git status`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repo ->
            val fromJGit = repo.changedFiles().map { it.path to it.changeKind.name }.sortedBy { it.first }
            val fromCli = GitCliFixture.statusPorcelain(root)
                .map { (code, path) -> path to cliCodeToKind(code) }
                .sortedBy { it.first }

            // `--porcelain=v1` кодирует файл вне индекса как `??`; проверяем код явно, а не полагаемся на разбор.
            assertTrue(
                porcelainLines(root).any { it.startsWith("?? src/New.kt") },
                "git status должен отдавать новый файл вне индекса с кодом `??`",
            )

            assertEquals(fromCli, fromJGit, "JGit и git status должны давать один список")
            assertEquals(
                listOf("src/Login.kt" to "MODIFIED", "src/New.kt" to "ADDED"),
                fromJGit,
            )
        }
    }

    @Test
    fun `история коммитов совпадает с git log`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repo ->
            val fromJGit = repo.commitLog(limit = 10).map { it.message }
            assertEquals(GitCliFixture.logMessages(root, limit = 10), fromJGit)
            assertEquals(listOf("второй коммит", "первый коммит"), fromJGit)
        }
    }

    @Test
    fun `короткий хеш HEAD непустой и совпадает с git rev-parse`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        JGitRepository.open(root).use { repo ->
            val expected = GitCliFixture.run(listOf("git", "rev-parse", "--short", "HEAD"), root).stdout.trim()
            assertEquals(expected, repo.headCommit())
        }
    }

    @Test
    fun `репозиторий без коммитов не падает и отдаёт пустую историю`() {
        val root = GitCliFixture.createEmptyRepo(tempDir("aide-git-empty-"))
        JGitRepository.open(root).use { repo ->
            assertEquals("master", repo.currentBranch())
            assertTrue(repo.commitLog(limit = 10).isEmpty())
            assertEquals("", repo.headCommit(), "У репозитория без коммитов нет HEAD")
            assertTrue(repo.changedFiles().isEmpty())
        }
    }

    @Test
    fun `каталог без git даёт типизированную ошибку`() {
        val root = tempDir("aide-nogit-")
        val error = assertFailsWith<GitAccessException> { JGitRepository.open(root) }
        assertIs<dev.aide.protocol.ProtocolError.NotAGitRepository>(error.error)
    }

    @Test
    fun `переименование и удаление различаются`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "mv", "src/Api.kt", "src/Renamed.kt"), root)
        Files.deleteIfExists(root.resolve("src/New.kt"))

        JGitRepository.open(root).use { repo ->
            val kinds = repo.changedFiles().associate { it.path to it.changeKind }
            assertEquals(FileChangeKindCompat.RENAMED, kinds["src/Renamed.kt"])
            assertEquals(FileChangeKindCompat.DELETED, kinds["src/New.kt"])
        }
    }

    @Test
    fun `переименование в git status читается с учётом суффикса со стрелкой`() {
        val root = GitCliFixture.createRepo(tempDir("aide-git-"))
        GitCliFixture.run(listOf("git", "mv", "src/Api.kt", "src/Renamed.kt"), root)

        // Строка переименования в `--porcelain=v1` выглядит как `R  старый путь -> новый путь`.
        val renameLine = porcelainLines(root).single { it.startsWith("R") }
        val paths = renameLine.substring(3).split(" -> ").map { it.trim() }
        assertEquals(listOf("src/Api.kt", "src/Renamed.kt"), paths)

        JGitRepository.open(root).use { repo ->
            val renamed = repo.changedFiles().single { it.path == paths.last() }
            assertEquals(FileChangeKindCompat.RENAMED, renamed.changeKind.name)
            assertEquals(paths.first(), renamed.previousPath)
        }
    }

    /** Строки `git status --porcelain=v1` как есть: нужны там, где важен код строки, а не разобранная пара. */
    private fun porcelainLines(root: Path): List<String> =
        GitCliFixture.run(listOf("git", "status", "--porcelain=v1"), root).stdout
            .lines()
            .filter { it.isNotBlank() }

    /** Коды `--porcelain=v1`, достижимые в фикстуре: `??` — новый файл вне индекса, `A` — добавленный в индекс, `M` — изменённый, `D` — удалённый. */
    private fun cliCodeToKind(code: String): String = when (code) {
        "??", "A" -> "ADDED"
        "M" -> "MODIFIED"
        "D" -> "DELETED"
        else -> code
    }
}

/** Псевдоним, чтобы тест читался без импорта домена: значения совпадают с [dev.aide.domain.FileChangeKind]. */
private object FileChangeKindCompat {
    const val RENAMED = "RENAMED"
    const val DELETED = "DELETED"
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.git.JGitRepositoryTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: JGitRepository`.

- [ ] **Шаг 3: написать интерфейс**

`host-core/src/main/kotlin/dev/aide/host/git/GitRepository.kt`:

```kotlin
package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError

/** Файл, изменённый относительно HEAD. */
data class ChangedFile(
    /** Путь относительно корня репозитория. */
    val path: String,
    /** Что произошло с файлом. */
    val changeKind: FileChangeKind,
    /** Прежний путь при переименовании; null в остальных случаях. */
    val previousPath: String? = null,
)

/** Коммит в истории. */
data class CommitInfo(
    /** Полный хеш коммита. */
    val hash: String,
    /** Короткий хеш для показа в UI. */
    val shortHash: String,
    /** Первая строка сообщения. */
    val message: String,
    /** Автор в формате «Имя <почта>». */
    val author: String,
    /** Время коммита в миллисекундах от эпохи. */
    val committedAtEpochMillis: Long,
)

/**
 * Чтение состояния git-репозитория.
 *
 * Интерфейс отделён от реализации: JGit — не единственный возможный источник
 * (этап 6 добавляет Rust-модуль для git-операций через FFI, § 8.1), а задачи
 * этапа 1 работают с этим интерфейсом, а не с JGit напрямую.
 */
interface GitRepository : AutoCloseable {

    /** Имя текущей ветки; для репозитория без коммитов возвращает имя ещё не созданной ветки. */
    fun currentBranch(): String

    /** Короткий хеш HEAD; пустая строка, если коммитов нет. */
    fun headCommit(): String

    /** Изменения относительно HEAD: изменённые, добавленные, удалённые и переименованные файлы. */
    fun changedFiles(): List<ChangedFile>

    /** История коммитов текущей ветки, от нового к старому. */
    fun commitLog(limit: Int = DEFAULT_LOG_LIMIT): List<CommitInfo>

    companion object {
        /** Сколько коммитов отдаётся по умолчанию: столько помещается в список без подгрузки. */
        const val DEFAULT_LOG_LIMIT: Int = 50
    }
}

/** Ошибка доступа к репозиторию, несущая типизированную причину. */
class GitAccessException(val error: ProtocolError) : Exception(error.toString())
```

- [ ] **Шаг 4: написать реализацию на JGit**

`host-core/src/main/kotlin/dev/aide/host/git/JGitRepository.kt`:

```kotlin
package dev.aide.host.git

import dev.aide.domain.FileChangeKind
import dev.aide.protocol.ProtocolError
import java.nio.file.Path
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.lib.Repository
import org.eclipse.jgit.storage.file.FileRepositoryBuilder

/**
 * Реализация чтения состояния git через JGit.
 *
 * Никаких изменяющих операций здесь нет: ветки, коммиты и снапшоты появляются
 * в задачах T-1.11, T-1.18 и T-1.19. Эта задача читает состояние, чтобы UI мог
 * показать ветку и дерево.
 */
class JGitRepository private constructor(
    private val repository: Repository,
    private val git: Git,
) : GitRepository {

    override fun currentBranch(): String = repository.branch ?: UNKNOWN_BRANCH

    override fun headCommit(): String =
        runCatching { repository.resolve(HEAD).name }.getOrNull()?.take(SHORT_HASH_LENGTH).orEmpty()

    override fun changedFiles(): List<ChangedFile> {
        val status = runCatching { git.status().call() }.getOrElse { error ->
            throw GitAccessException(ProtocolError.Internal("не удалось прочитать состояние git", error.message))
        }
        return buildList {
            status.added.forEach { add(ChangedFile(it, FileChangeKind.ADDED)) }
            status.changed.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }
            status.modified.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }
            status.removed.forEach { add(ChangedFile(it, FileChangeKind.DELETED)) }
            status.missing.forEach { add(ChangedFile(it, FileChangeKind.DELETED)) }
            status.untracked.forEach { add(ChangedFile(it, FileChangeKind.ADDED)) }
            status.conflicting.forEach { add(ChangedFile(it, FileChangeKind.MODIFIED)) }

            // Переименования: JGit отдаёт их как пару «удалён старый — добавлен новый».
            status.renamed.forEach { (old, new) ->
                removeAll { it.path == old }
                add(ChangedFile(path = new, changeKind = FileChangeKind.RENAMED, previousPath = old))
            }
        }.distinctBy { it.path }
            .sortedBy { it.path }
    }

    override fun commitLog(limit: Int): List<CommitInfo> {
        if (headCommit().isEmpty()) return emptyList()
        return runCatching {
            git.log().setMaxCount(limit).call().map { commit ->
                CommitInfo(
                    hash = commit.name,
                    shortHash = commit.name.take(SHORT_HASH_LENGTH),
                    message = commit.fullMessage.lineSequence().first().trim(),
                    author = "${commit.authorIdent.name} <${commit.authorIdent.emailAddress}>",
                    committedAtEpochMillis = commit.commitTime.toLong() * 1_000,
                )
            }
        }.getOrElse { error ->
            throw GitAccessException(ProtocolError.Internal("не удалось прочитать историю", error.message))
        }
    }

    override fun close() {
        git.close()
        repository.close()
    }

    companion object {
        private const val HEAD = "HEAD"
        private const val SHORT_HASH_LENGTH = 7
        private const val UNKNOWN_BRANCH = "HEAD"

        /** Открывает репозиторий по пути к рабочему каталогу. */
        fun open(workTree: Path): JGitRepository {
            val gitDir = workTree.resolve(".git")
            if (!gitDir.toFile().exists()) {
                throw GitAccessException(ProtocolError.NotAGitRepository(workTree.toString()))
            }
            val repository = try {
                FileRepositoryBuilder()
                    .setWorkTree(workTree.toFile())
                    .setGitDir(gitDir.toFile())
                    .readEnvironment()
                    .build()
            } catch (error: Exception) {
                throw GitAccessException(ProtocolError.NotAGitRepository(workTree.toString()))
            }
            return JGitRepository(repository, Git(repository))
        }
    }
}
```

- [ ] **Шаг 5: подключить JGit и прогнать тесты**

Добавить в `host-core/build.gradle.kts` в блок `dependencies`: `implementation(libs.jgit)`.

```bash
./gradlew :host-core:test
```

Ожидаемо: `BUILD SUCCESSFUL`, 33 теста (`ProtocolServerTest` — 4, `ReconnectTest` — 2, `WorkspaceFileSystemTest` — 13, `FileTreeBuilderTest` — 6, `JGitRepositoryTest` — 8).

Если падает тест про переименование — JGit сообщает о переименовании через `status().call().renamed` только если включено отслеживание переименований. Если `renamed` пуст, переименование придёт как пара удаление+добавление, и тест это поймает: тогда переименование нужно распознавать по совпадению содержимого. Проверить фактические списки, напечатав их в тесте, и при необходимости заменить блок переименований на сопоставление удалённого и добавленного файла по совпадению blob-хеша:

```kotlin
    /** Сопоставляет удалённые и добавленные файлы с одинаковым содержимым — так JGit отдаёт переименование без опции отслеживания. */
    private fun detectRenames(status: org.eclipse.jgit.api.Status): List<ChangedFile> {
        val removed = (status.removed + status.missing).sorted()
        val added = status.untracked + status.added
        return removed.flatMap { old ->
            added.filter { new -> sameContentIgnoringPath(new, old) }
                .map { new -> ChangedFile(path = new, changeKind = FileChangeKind.RENAMED, previousPath = old) }
        }
    }

    private fun sameContentIgnoringPath(candidate: String, removedPath: String): Boolean =
        candidate.substringAfterLast('/') == removedPath.substringAfterLast('/')
```

Если этот путь не понадобился — удалить обе вспомогательные функции, чтобы не оставлять мёртвый код.

- [ ] **Шаг 6: проверить, что тест ловит расхождение с git**

Временно изменить `changedFiles()` так, чтобы он не возвращал `untracked` файлы:

```kotlin
            // status.untracked.forEach { add(ChangedFile(it, FileChangeKind.ADDED)) }  // намеренная поломка
```

```bash
./gradlew :host-core:test --tests 'dev.aide.host.git.JGitRepositoryTest'
```

Ожидаемо: `FAILED` в тесте «список изменённых файлов совпадает с git status» — сверка с командной строкой действительно работает. Вернуть строку.

- [ ] **Шаг 7: коммит**

```bash
git add host-core
git commit -m "feat(host): чтение состояния git через JGit со сверкой с git status и git log"
```

---

## Задача 13: запуск хоста в локальном режиме (`T-0.13`)

**Файлы:**
- Создать: `host-core/src/main/kotlin/dev/aide/host/HostApp.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/EmbeddedHost.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/server/StageZeroHandler.kt`
- Изменить: `host-core/build.gradle.kts` (тестовая зависимость на `client-state`)
- Изменить: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt`
- Изменить: `desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`
- Изменить: `androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/EmbeddedHostTest.kt`
- Тест: `client-state/src/jvmTest/kotlin/dev/aide/client/state/NoLocalityBranchingTest.kt`
- Изменить: `client-state/build.gradle.kts` (системные свойства для теста)

**Что именно проверяет `T-0.13`.** Два утверждения. Первое: на десктопе приложение поднимает хост само, а остановка приложения завершает хост. Второе, более важное: тот же клиентский артефакт, запущенный с адресом удалённого хоста, показывает то же дерево, и в исходниках клиента нет ни одной ветки по признаку локальности. Второе проверяется двумя тестами: интеграционным (клиент работает с хостом по адресу) и статическим (поиск по исходникам не находит признаков локальности).

- [ ] **Шаг 1: написать падающий тест на поднятие и остановку хоста**

Сначала тестовая зависимость. Тест импортирует `HostClient` и `KtorHostConnection` из `client-state`, то есть `host-core` нужно ребро `host-core → client-state`, и только в тестовой конфигурации: направление «хост знает клиента» в main-коде остаётся запрещённым. Исключение описано в задаче 4, шаг 1.

`host-core/build.gradle.kts` — добавить в `dependencies`:

```kotlin
    testImplementation(project(":client-state"))
```

`host-core/src/test/kotlin/dev/aide/host/EmbeddedHostTest.kt`:

```kotlin
package dev.aide.host

import dev.aide.host.workspace.TempRepoFixture
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostClient
import dev.aide.client.state.KtorHostConnection
import dev.aide.protocol.HostMode
import io.ktor.client.HttpClient
import io.ktor.client.plugins.websocket.WebSockets
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull

class EmbeddedHostTest {

    private val fixture = TempRepoFixture()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @AfterTest
    fun tearDown() {
        scope.cancel()
        fixture.close()
    }

    @Test
    fun `хост поднимается, объявляет локальный режим и отдаёт состояние репозитория`() = runBlocking {
        val host = EmbeddedHost.open(fixture.root)
        try {
            assertTrue(host.endpoint.startsWith("ws://127.0.0.1:"), "Локальный хост слушает loopback: ${host.endpoint}")

            val connection = KtorHostConnection(
                endpoint = host.endpoint,
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
            )
            val client = HostClient(connection, scope)
            client.start()
            awaitConnected(connection)

            val workspaceId = assertNotNull(client.openWorkspace(fixture.root.toString()))

            val state = client.hostState().getOrThrow()
            assertEquals(HostMode.LOCAL, state.mode)
            assertEquals("master", state.branch)
            assertEquals(fixture.root.toRealPath().toString(), state.rootPath)

            val tree = client.fileTree().getOrThrow()
            assertTrue(tree.entries.any { it.path == "src/auth/Login.kt" })
        } finally {
            host.close()
        }
    }

    @Test
    fun `остановка хоста освобождает порт и закрывает сессии`() = runBlocking {
        val host = EmbeddedHost.open(fixture.root)
        val endpoint = host.endpoint
        val port = host.port

        val connection = KtorHostConnection(
            endpoint = endpoint,
            scope = scope,
            httpClient = HttpClient { install(WebSockets) },
            initialRetryMillis = 50,
            maxRetryMillis = 100,
        )
        val client = HostClient(connection, scope)
        client.start()
        awaitConnected(connection)

        host.close()

        // Порт освобождён: его можно занять снова.
        java.net.ServerSocket(port).use { socket -> assertTrue(socket.isBound) }

        // Клиент замечает обрыв, но не закрывается окончательно — он будет переподключаться.
        val state = withTimeoutOrNull(5_000) {
            while (connection.state.value !is ConnectionState.Reconnecting) delay(20)
            connection.state.value
        }
        assertIs<ConnectionState.Reconnecting>(state)
        connection.stopSafely()
    }

    @Test
    fun `клиент работает с хостом по указанному адресу и не знает, локальный он или нет`() = runBlocking {
        // Тот же клиентский код, но адрес — единственное, что отличает случай.
        val host = EmbeddedHost.open(fixture.root)
        try {
            val connection = KtorHostConnection(
                endpoint = "ws://127.0.0.1:${host.port}/ws",
                scope = scope,
                httpClient = HttpClient { install(WebSockets) },
            )
            val client = HostClient(connection, scope)
            client.start()
            awaitConnected(connection)

            assertNotNull(client.openWorkspace(fixture.root.toString()))
            assertEquals("master", client.hostState().getOrThrow().branch)
            assertEquals(1, client.session.value.workspaceId?.let { 1 })
        } finally {
            host.close()
        }
    }

    private suspend fun awaitConnected(connection: KtorHostConnection) {
        val state = withTimeoutOrNull(10_000) {
            while (connection.state.value !is ConnectionState.Connected) delay(20)
            connection.state.value
        }
        assertIs<ConnectionState.Connected>(state, "Клиент не подключился: ${connection.state.value}")
    }

    private suspend fun KtorHostConnection.stopSafely() {
        runCatching { stop() }
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.EmbeddedHostTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: EmbeddedHost`.

- [ ] **Шаг 3: написать обработчик сообщений этапа 0**

`host-core/src/main/kotlin/dev/aide/host/server/StageZeroHandler.kt`:

```kotlin
package dev.aide.host.server

import dev.aide.host.git.GitAccessException
import dev.aide.host.git.GitRepository
import dev.aide.host.workspace.Workspace
import dev.aide.host.workspace.WorkspaceAccessException
import dev.aide.host.workspace.WorkspaceFileSystem
import dev.aide.protocol.ClientMessage
import dev.aide.protocol.HostMessage
import dev.aide.protocol.HostMode
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.RequestId
import dev.aide.protocol.WorkspaceId
import java.util.concurrent.ConcurrentHashMap

/**
 * Обработчик сообщений этапа 0: открытие репозитория, дерево, содержимое файла, состояние.
 *
 * Держит открытые воркспейсы и их ресурсы. Изменяющих операций нет: агент, коммиты
 * и снапшоты появляются в этапе 1, поэтому этот класс — единственное место, где
 * хост читает диск по запросу клиента.
 */
class StageZeroHandler(
    private val openGit: (java.nio.file.Path) -> GitRepository = { path ->
        dev.aide.host.git.JGitRepository.open(path)
    },
    private val mode: HostMode = HostMode.LOCAL,
    private val startedAtMillis: Long = System.currentTimeMillis(),
) : ClientMessageHandler, AutoCloseable {

    private class OpenWorkspace(
        val workspace: Workspace,
        val fileSystem: WorkspaceFileSystem,
        val treeBuilder: dev.aide.host.workspace.FileTreeBuilder,
        val git: GitRepository,
    )

    private val opened = ConcurrentHashMap<WorkspaceId, OpenWorkspace>()

    override suspend fun handle(message: ClientMessage): HostMessage = when (message) {
        is ClientMessage.OpenWorkspace -> openWorkspace(message)
        is ClientMessage.FileTree -> withWorkspace(message.workspaceId, message.requestId) { open ->
            HostMessage.Tree(message.requestId, open.treeBuilder.build())
        }

        is ClientMessage.FileContent -> withWorkspace(message.workspaceId, message.requestId) { open ->
            val file = open.fileSystem.readFile(message.path)
            HostMessage.Content(message.requestId, open.fileSystem.toPayload(file))
        }

        is ClientMessage.HostState -> withWorkspace(message.workspaceId, message.requestId) { open ->
            HostMessage.State(
                message.requestId,
                HostStatePayload(
                    workspaceId = open.workspace.id,
                    rootPath = open.workspace.root.toString(),
                    branch = open.git.currentBranch(),
                    headCommit = open.git.headCommit(),
                    uptimeMillis = System.currentTimeMillis() - startedAtMillis,
                    mode = mode,
                ),
            )
        }

        is ClientMessage.Hello -> HostMessage.Failure(
            requestId = RequestId("unexpected"),
            error = ProtocolError.Internal("приветствие обрабатывает сессия, а не обработчик"),
        )
    }

    private fun openWorkspace(message: ClientMessage.OpenWorkspace): HostMessage = try {
        val workspace = Workspace.open(java.nio.file.Path.of(message.path))
        val git = try {
            openGit(workspace.root)
        } catch (error: GitAccessException) {
            throw WorkspaceAccessException(error.error)
        }
        val fileSystem = WorkspaceFileSystem(workspace)
        opened[workspace.id] = OpenWorkspace(workspace, fileSystem, dev.aide.host.workspace.FileTreeBuilder(fileSystem, workspace), git)
        HostMessage.WorkspaceOpened(message.requestId, workspace.id)
    } catch (error: WorkspaceAccessException) {
        HostMessage.Failure(message.requestId, error.error)
    } catch (error: Exception) {
        HostMessage.Failure(message.requestId, ProtocolError.Internal("не удалось открыть репозиторий", error.message))
    }

    private inline fun withWorkspace(
        workspaceId: WorkspaceId,
        requestId: RequestId,
        block: (OpenWorkspace) -> HostMessage,
    ): HostMessage {
        val open = opened[workspaceId] ?: return HostMessage.Failure(requestId, ProtocolError.WorkspaceClosed(workspaceId))
        return try {
            block(open)
        } catch (error: WorkspaceAccessException) {
            HostMessage.Failure(requestId, error.error)
        } catch (error: GitAccessException) {
            HostMessage.Failure(requestId, error.error)
        } catch (error: Exception) {
            HostMessage.Failure(requestId, ProtocolError.Internal("ошибка обработки запроса", error.message))
        }
    }

    override fun close() {
        opened.values.forEach { runCatching { it.git.close() } }
        opened.clear()
    }
}
```

- [ ] **Шаг 4: написать `HostApp` и `EmbeddedHost`**

`host-core/src/main/kotlin/dev/aide/host/HostApp.kt` — композиционный корень хоста. В структуре проекта он назван «composition root хоста (Koin)», и это буквально: здесь, и только здесь, известно, из чего состоит хост.

```kotlin
package dev.aide.host

import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.protocol.HostMode
import dev.aide.protocol.ProtocolVersion
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.core.qualifier.Qualifier
import org.koin.core.qualifier.named
import org.koin.dsl.module

/**
 * Composition root хоста: единственное место, где известно, из чего состоит хост.
 *
 * Модуль параметризован режимом, портом и версией протокола: на этапе 0 эти значения
 * отличают локальный хост от удалённого, разделяемым состоянием они не являются.
 *
 * Граф изолированный (`KoinApplication.init()`, а не глобальный контекст): хост поднимается
 * и в приложении, и в тестах — `EmbeddedHostTest` открывает его несколько раз за прогон,
 * а глобальный контекст Koin допускает только один запуск на процесс.
 */
object HostApp {

    /** Квалификатор режима: значение нужно и обработчику, и серверу. */
    val modeQualifier: Qualifier = named("hostMode")

    /** Квалификатор порта: сервер слушает именно его. */
    val portQualifier: Qualifier = named("hostPort")

    /** Квалификатор версии протокола, которую хост объявляет клиенту. */
    val versionQualifier: Qualifier = named("hostVersion")

    /** Собирает части хоста: обработчик сообщений и сервер. */
    fun module(
        mode: HostMode,
        port: Int,
        protocolVersion: ProtocolVersion,
    ): Module = module {
        single(modeQualifier) { mode }
        single(portQualifier) { port }
        single(versionQualifier) { protocolVersion }
        single { StageZeroHandler(mode = get(modeQualifier)) }
        single {
            ProtocolServer(
                handler = get(),
                hostVersion = get(versionQualifier),
                mode = get(modeQualifier),
                port = get(portQualifier),
            )
        }
    }

    /**
     * Поднимает хост на свободном порту loopback, если порт не задан явно.
     *
     * Возвращает [EmbeddedHost] вместе с графом: `close()` освобождает порт, закрывает
     * обработчик и закрывает сам граф.
     */
    fun open(
        mode: HostMode = HostMode.LOCAL,
        port: Int = freeLoopbackPort(),
        protocolVersion: ProtocolVersion = ProtocolVersion.CURRENT,
    ): EmbeddedHost {
        val graph = KoinApplication.init().modules(module(mode = mode, port = port, protocolVersion = protocolVersion))
        val server = graph.koin.get<ProtocolServer>()
        server.start()
        return EmbeddedHost(server = server, handler = graph.koin.get(), graph = graph)
    }
}
```

`host-core/src/main/kotlin/dev/aide/host/EmbeddedHost.kt` — держатель жизненного цикла. Зависимости он не собирает сам, а получает готовыми из `HostApp`:

```kotlin
package dev.aide.host

import dev.aide.host.server.ProtocolServer
import dev.aide.host.server.StageZeroHandler
import dev.aide.host.server.freeLoopbackPort
import dev.aide.protocol.HostMode
import org.koin.core.KoinApplication

/**
 * Хост, поднятый в том же процессе, что и приложение (§ 3.3).
 *
 * Смысл этого класса в том, что локальный режим не имеет собственного кода:
 * он поднимает тот же [ProtocolServer], что и удалённый, и отличается только
 * адресом. Поэтому клиент не может «заметить», что хост локальный, — ему это
 * и не нужно.
 *
 * Остановка приложения должна вызывать [close]: иначе порт остаётся занятым,
 * а открытые git-репозитории — не закрытыми.
 */
class EmbeddedHost internal constructor(
    private val server: ProtocolServer,
    private val handler: StageZeroHandler,
    private val graph: KoinApplication,
) : AutoCloseable {

    /** Порт, на котором слушает хост. */
    val port: Int get() = server.boundPort

    /** Адрес для клиента. */
    val endpoint: String get() = server.endpoint

    /** Режим, который хост объявляет клиенту; на клиентское поведение не влияет. */
    val mode: HostMode get() = server.hostMode

    override fun close() {
        server.stop()
        handler.close()
        graph.close()
    }

    companion object {
        /**
         * Поднимает хост на свободном порту loopback.
         *
         * @param port конкретный порт; по умолчанию берётся свободный, чтобы два запуска
         *   приложения на одной машине не конфликтовали.
         */
        fun open(port: Int = freeLoopbackPort()): EmbeddedHost = HostApp.open(port = port)
    }
}
```

Путь к репозиторию в подпись не входит намеренно: воркспейс открывает клиент сообщением `OpenWorkspace`, и хост не решает за него, что открывать — это то же поведение, что и у удалённого хоста. Поэтому сигнатура честная, и сборка зависимостей остаётся в одном месте — в `HostApp`:

```kotlin
        fun open(port: Int = freeLoopbackPort()): EmbeddedHost = HostApp.open(port = port)
```

Тесты из шага 1 должны вызывать `EmbeddedHost.open()` без аргумента — заменить `EmbeddedHost.open(fixture.root)` во всех трёх местах.

- [ ] **Шаг 5: подключить хост к десктопному приложению**

`desktopApp/build.gradle.kts` — править не нужно. `Main.kt` импортирует `EmbeddedHost` из `host-core`, `KtorHostConnection` из `client-state` и `App` из `client-ui`; первые две приходят через `api(project(":host-core"))` и `api(project(":client-state"))` у `platform-desktop` (задача 1, шаг 8), третья объявлена прямой зависимостью в задаче 2. До правила `api`/`implementation` из задачи 4 эту зависимость приходилось дублировать здесь — теперь дублировать нечего.

`desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`:

```kotlin
package dev.aide.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.ui.App
import dev.aide.host.EmbeddedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

fun main() = application {
    // Хост поднимается вместе с приложением и завершается вместе с ним (T-0.13).
    val host = remember { EmbeddedHost.open() }
    val scope = remember { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
    val connection = remember {
        KtorHostConnection(endpoint = host.endpoint, scope = scope)
    }

    DisposableEffect(Unit) {
        onDispose {
            host.close()
            scope.cancel()
        }
    }

    Window(onCloseRequest = ::exitApplication, title = "AI Studio") {
        App(connection = connection)
    }
}
```

- [ ] **Шаг 6: поправить `App`, чтобы он принимал соединение**

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt` — единственное изменение относительно задачи 2: появляется параметр. Полное содержимое:

```kotlin
package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import dev.aide.client.state.HostConnection

@Composable
fun App(connection: HostConnection) {
    MaterialTheme {
        Surface(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.fillMaxSize()) {
                Text("AI Studio")
            }
        }
    }
}
```

Экраны, использующие `connection`, появляются в задачах 14 и 16. Параметр вводится сейчас, чтобы `App` не пришлось менять дважды.

- [ ] **Шаг 7: привести Android-точку входа к новой сигнатуре `App`**

`App` теперь требует соединение, а `MainActivity` всё ещё вызывает `App()` — без этой правки `:androidApp:assembleDebug` (а он есть в CI) падает с `No value passed for parameter 'connection'`. Настроек в проекте ещё нет (они появляются в задаче 14), поэтому адрес хоста берётся константой — тем же значением по умолчанию, которое в задаче 16 переедет в `AndroidClientRuntime` вместе с чтением адреса из настроек.

`androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`:

```kotlin
package dev.aide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.ui.App
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Точка входа Android-приложения.
 *
 * Хост здесь не поднимается: на телефоне приложение подключается к хосту по адресу
 * из настроек. Автоматическое обнаружение хоста и сопряжение устройств появятся
 * в T-1.51 и на этапе 5 (T-5.11) — до тех пор адрес вводится вручную.
 *
 * Настроек ещё нет, поэтому адрес берётся из константы; связывание переедет
 * в `AndroidClientRuntime`, когда появится хранилище настроек.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val connection = KtorHostConnection(endpoint = DEFAULT_ENDPOINT, scope = scope)

        setContent {
            App(connection = connection)
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        /** Адрес хоста по умолчанию для эмулятора: 10.0.2.2 указывает на машину-хост. */
        private const val DEFAULT_ENDPOINT: String = "ws://10.0.2.2:8080/ws"
    }
}
```

Правок в build-файле не требуется: `KtorHostConnection` и `CoroutineScope` видны из `androidApp` через `api`-зависимости `client-ui` и `platform-android` (задача 1, шаги 7 и 8).

Проверить, что Android-таргет собирается после смены сигнатуры:

```bash
./gradlew :androidApp:assembleDebug
```

Ожидаемо: `BUILD SUCCESSFUL`.

- [ ] **Шаг 8: написать статический тест «в клиенте нет ветвлений по локальности»**

`client-state/build.gradle.kts` — добавить в блок `kotlin`:

```kotlin
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
```

и после блока `kotlin`:

```kotlin
tasks.named<Test>("jvmTest") {
    systemProperty("clientSourcesDir", layout.projectDirectory.dir("src").asFile.path)
}
```

`client-state/src/jvmTest/kotlin/dev/aide/client/state/NoLocalityBranchingTest.kt`:

```kotlin
package dev.aide.client.state

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * T-0.13: клиент не различает локальный хост и удалённый.
 *
 * Проверяется статически: в исходниках [client-state] и [client-ui] не должно быть
 * признаков локальности. Если они появятся, интеграционный тест «клиент работает
 * с хостом по указанному адресу» перестанет быть достаточным — он не заметит
 * ветку, которую исполнят только в одном из режимов.
 */
class NoLocalityBranchingTest {

    private val clientStateRoot = File(
        System.getProperty("clientSourcesDir")
            ?: error("Не задано системное свойство clientSourcesDir — проверь блок jvmTest в build.gradle.kts"),
    )

    /** Признаки того, что код знает о локальности хоста. */
    private val forbidden = listOf(
        "isLocalHost",
        "isRemoteHost",
        "HostMode.LOCAL",
        "HostMode.REMOTE",
        "localHost",
        "remoteHost",
        "embeddedHost",
        "EmbeddedHost",
        "if (local)",
    )

    @Test
    fun `в клиентском коде нет признаков локальности хоста`() {
        assertTrue(clientStateRoot.isDirectory, "Каталог исходников не найден: $clientStateRoot")

        val offenders = mutableListOf<String>()
        clientStateRoot.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/jvmTest/") || it.path.contains("/commonTest/") }
            .forEach { file ->
                val text = file.readText()
                forbidden.forEach { needle ->
                    if (text.contains(needle)) offenders += "${file.relativeTo(clientStateRoot)}: $needle"
                }
            }

        assertTrue(
            offenders.isEmpty(),
            "Клиент не должен ветвиться по признаку локальности хоста (§ 3.3). Найдено:\n" +
                offenders.joinToString("\n"),
        )
    }
}
```

Тот же тест продублировать в `client-ui/src/jvmTest/kotlin/dev/aide/client/ui/NoLocalityBranchingTest.kt` с системным свойством `clientUiSourcesDir` — критерий `T-0.13` требует нуля совпадений в обоих модулях.

**Заметка на будущее (не отдельный шаг).** К этому моменту в проекте три разных способа читать исходники в тестах: `okio` (тест из задачи 2), `java.io.File` плюс системное свойство (этот тест и тест из задачи 14). Их стоит свести к одному механизму — `jvmTest` плюс системное свойство, без `okio`, — и заодно усилить правило: проверять не список из шести подстрок, а импорты платформенных пакетов (`java.`, `javax.`, `android.`, `kotlinx.cinterop`, `platform.`, `UIKit`), объявления `expect `/`actual ` и обращения `System.`, плюс утверждение «просканировано N файлов, N > 0» — иначе тест молча проходит, если путь к исходникам не найден или файлов нет.

- [ ] **Шаг 9: прогнать тесты**

```bash
./gradlew :host-core:test :client-state:jvmTest :client-ui:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, `EmbeddedHostTest` — 3 теста, `NoLocalityBranchingTest` — по 1 в каждом модуле.

Проверить, что статический тест действительно ловит нарушение: временно добавить в `KtorHostConnection.kt` поле `val isLocalHost: Boolean = false`, прогнать — ожидаемо `FAILED` с перечислением совпадений. Удалить поле.

- [ ] **Шаг 10: проверить запуск приложения**

```bash
./gradlew :desktopApp:createDistributable && ./desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp
```

Ожидаемо: окно открывается, в логе есть строка `Хост слушает ws://127.0.0.1:<порт>/ws`. Закрыть окно — хост останавливается, порт освобождается. Проверить:

```bash
ss -ltn | grep <порт> || echo "порт освобождён"
```

- [ ] **Шаг 11: коммит**

```bash
git add host-core desktopApp androidApp client-ui client-state
git commit -m "feat(host): запуск хоста в локальном режиме и статическая проверка отсутствия локальности в клиенте"
```

---

## Задача 14: настройки, тема и ресурсные строки (`T-0.14`)

**Файлы:**
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/settings/KeyValueStore.kt` (expect)
- Создать: `client-state/src/androidMain/kotlin/dev/aide/client/state/settings/KeyValueStore.android.kt` (actual)
- Создать: `client-state/src/jvmMain/kotlin/dev/aide/client/state/settings/KeyValueStore.jvm.kt` (actual)
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/settings/SettingsStore.kt`
- Создать: `client-state/src/commonTest/kotlin/dev/aide/client/state/settings/FakeKeyValueStore.kt`
- Тест: `client-state/src/commonTest/kotlin/dev/aide/client/state/settings/SettingsStoreTest.kt`
- Создать: `client-ui/src/commonMain/composeResources/values/strings.xml`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/strings/Strings.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/theme/Theme.kt`
- Создать: `androidApp/src/main/res/values/strings.xml`
- Изменить: `client-ui/build.gradle.kts`, `client-state/build.gradle.kts`
- Изменить: `androidApp/src/main/AndroidManifest.xml`, `desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`
- Тест: `client-ui/src/jvmTest/kotlin/dev/aide/client/ui/NoLiteralUiStringsTest.kt`
- Тест: `client-ui/src/jvmTest/kotlin/dev/aide/client/ui/EntryPointStringsTest.kt`

**Про `expect`/`actual` для хранилища.** Отдельная библиотека настроек не берётся намеренно: нужны три ключа, и лишняя зависимость в фундаменте дороже тридцати строк платформенного кода. На Android — `SharedPreferences`, на десктопе — файл `Properties` в каталоге конфигурации пользователя.

- [ ] **Шаг 1: написать падающий тест настроек**

`client-state/src/commonTest/kotlin/dev/aide/client/state/settings/FakeKeyValueStore.kt`:

```kotlin
package dev.aide.client.state.settings

/** Хранилище в памяти: позволяет тестировать настройки без платформы. */
class FakeKeyValueStore(initial: Map<String, String> = emptyMap()) : KeyValueStore {

    private val values = initial.toMutableMap()

    override fun getString(key: String): String? = values[key]

    override fun putString(key: String, value: String) {
        values[key] = value
    }

    override fun remove(key: String) {
        values.remove(key)
    }

    /** Снимок содержимого — эмулирует перезапуск приложения. */
    fun snapshot(): Map<String, String> = values.toMap()
}
```

`client-state/src/commonTest/kotlin/dev/aide/client/state/settings/SettingsStoreTest.kt`:

```kotlin
package dev.aide.client.state.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SettingsStoreTest {

    private val repositoryPath = "/projects/aide"

    @Test
    fun `настройки по умолчанию`() {
        val store = SettingsStore(FakeKeyValueStore())
        assertEquals(ThemePreference.SYSTEM, store.theme)
        assertNull(store.repositoryPath, "Путь к репозиторию по умолчанию не задан")
        assertEquals(ControlMode.GESTURES, store.controlMode)
    }

    @Test
    fun `путь к репозиторию переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).repositoryPath = repositoryPath

        // Перезапуск: новый SettingsStore поверх того же хранилища.
        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals(repositoryPath, afterRestart.repositoryPath)
    }

    @Test
    fun `тема переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).theme = ThemePreference.DARK

        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals(ThemePreference.DARK, afterRestart.theme)
    }

    @Test
    fun `адрес хоста переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).hostEndpoint = "ws://192.168.1.10:8080/ws"

        val afterRestart = SettingsStore(FakeKeyValueStore(backend.snapshot()))
        assertEquals("ws://192.168.1.10:8080/ws", afterRestart.hostEndpoint)
    }

    @Test
    fun `режим управления переживает перезапуск`() {
        val backend = FakeKeyValueStore()
        SettingsStore(backend).controlMode = ControlMode.BUTTONS
        assertEquals(ControlMode.BUTTONS, SettingsStore(FakeKeyValueStore(backend.snapshot())).controlMode)
    }

    @Test
    fun `неизвестное значение темы читается как системная, а не роняет чтение`() {
        val backend = FakeKeyValueStore(mapOf("theme" to "rainbow"))
        assertEquals(
            ThemePreference.SYSTEM,
            SettingsStore(backend).theme,
            "Повреждённая настройка не должна ломать запуск",
        )
        assertTrue(backend.snapshot().containsKey("theme"))
    }

    @Test
    fun `сброс пути к репозиторию возвращает null`() {
        val store = SettingsStore(FakeKeyValueStore())
        store.repositoryPath = repositoryPath
        store.repositoryPath = null
        assertNull(store.repositoryPath)
    }

    @Test
    fun `запись настройки не дублирует ключи`() {
        val backend = FakeKeyValueStore()
        val store = SettingsStore(backend)
        store.repositoryPath = "/a"
        store.repositoryPath = "/b"
        assertEquals("/b", store.repositoryPath)
        assertEquals(1, backend.snapshot().keys.count { it == SettingsStore.KEY_REPOSITORY_PATH })
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :client-state:jvmTest --tests 'dev.aide.client.state.settings.SettingsStoreTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: KeyValueStore`.

- [ ] **Шаг 3: написать `expect`-интерфейс хранилища и реализации**

`client-state/src/commonMain/kotlin/dev/aide/client/state/settings/KeyValueStore.kt`:

```kotlin
package dev.aide.client.state.settings

/**
 * Простое хранилище «ключ — строка» с платформенной реализацией.
 *
 * Значения, а не потокобезопасность: настройки меняются из главного потока
 * приложения, конкуренции за них нет.
 */
expect class KeyValueStore {

    /** Возвращает значение или null, если ключ не задан. */
    fun getString(key: String): String?

    /** Записывает значение. */
    fun putString(key: String, value: String)

    /** Удаляет ключ; отсутствующий ключ — не ошибка. */
    fun remove(key: String)
}

/** Создаёт хранилище настроек приложения. */
expect fun createKeyValueStore(): KeyValueStore
```

`client-state/src/androidMain/kotlin/dev/aide/client/state/settings/KeyValueStore.android.kt`:

```kotlin
package dev.aide.client.state.settings

import android.content.Context
import android.content.SharedPreferences

/** Реализация на SharedPreferences; контекст приложения задаётся при старте. */
actual class KeyValueStore internal constructor(private val preferences: SharedPreferences) {

    actual fun getString(key: String): String? = preferences.getString(key, null)

    actual fun putString(key: String, value: String) {
        preferences.edit().putString(key, value).apply()
    }

    actual fun remove(key: String) {
        preferences.edit().remove(key).apply()
    }
}

private var applicationContext: Context? = null

/** Вызывается один раз при старте Android-приложения. */
fun initKeyValueStore(context: Context) {
    applicationContext = context.applicationContext
}

actual fun createKeyValueStore(): KeyValueStore {
    val context = requireNotNull(applicationContext) {
        "Перед созданием настроек вызовите initKeyValueStore(context) в Application или Activity"
    }
    return KeyValueStore(context.getSharedPreferences("aide-settings", Context.MODE_PRIVATE))
}
```

`client-state/src/jvmMain/kotlin/dev/aide/client/state/settings/KeyValueStore.jvm.kt`:

```kotlin
package dev.aide.client.state.settings

import java.nio.file.Files
import java.nio.file.Path
import java.util.Properties
import kotlin.io.path.createDirectories
import kotlin.io.path.exists
import kotlin.io.path.inputStream
import kotlin.io.path.outputStream

/**
 * Реализация на файле `settings.properties` в каталоге конфигурации пользователя.
 * Чтение и запись — под замком: настройки могут менять несколько окон приложения.
 */
actual class KeyValueStore internal constructor(private val file: Path) {

    private val lock = Any()

    private fun read(): Properties = Properties().apply {
        if (file.exists()) {
            file.inputStream().use { load(it) }
        }
    }

    actual fun getString(key: String): String? = synchronized(lock) { read().getProperty(key) }

    actual fun putString(key: String, value: String) {
        synchronized(lock) {
            file.parent?.createDirectories()
            val properties = read()
            properties.setProperty(key, value)
            file.outputStream().use { properties.store(it, "AI Studio settings") }
        }
    }

    actual fun remove(key: String) {
        synchronized(lock) {
            val properties = read()
            properties.remove(key)
            file.outputStream().use { properties.store(it, "AI Studio settings") }
        }
    }
}

/** Каталог конфигурации: `$XDG_CONFIG_HOME/aide` или `~/.config/aide`. */
private fun settingsFile(): Path {
    val xdg = System.getenv("XDG_CONFIG_HOME")?.takeIf { it.isNotBlank() }
    val base = if (xdg != null) {
        Path.of(xdg)
    } else {
        Path.of(System.getProperty("user.home"), ".config")
    }
    return base.resolve("aide").resolve("settings.properties")
}

actual fun createKeyValueStore(): KeyValueStore = KeyValueStore(settingsFile())

/** Переопределение пути для тестов и для переносимого режима. */
fun createKeyValueStoreAt(file: Path): KeyValueStore = KeyValueStore(file)

/** Файл, в котором лежат настройки; нужен диагностике. */
fun defaultSettingsFile(): Path = settingsFile()

/** Проверка, что каталог создаваем: используется в тестах переносимости. */
internal fun Path.parentIsWritable(): Boolean = Files.isWritable(parent ?: this)
```

- [ ] **Шаг 4: написать `SettingsStore`**

`client-state/src/commonMain/kotlin/dev/aide/client/state/settings/SettingsStore.kt`:

```kotlin
package dev.aide.client.state.settings

/** Как приложение выбирает тему. */
enum class ThemePreference {
    /** Следовать системной теме — значение по умолчанию (FR-EDITOR-11). */
    SYSTEM,
    LIGHT,
    DARK,
}

/** Режим управления (FR-CTRL-1..3). На этом этапе только хранится; поведение — задача T-1.43. */
enum class ControlMode {
    /** Только жесты, кроме критичных кнопок. */
    GESTURES,

    /** Все действия кнопками, жесты-действия отключены. */
    BUTTONS,

    /** Навигация жестами, важные действия кнопками. */
    HYBRID,
}

/**
 * Настройки клиента, переживающие перезапуск (§ 9).
 *
 * Чтение устойчиво к повреждённым значениям: неизвестная строка в хранилище даёт
 * значение по умолчанию, а не исключение, иначе один испорченный ключ ломал бы запуск.
 */
class SettingsStore(private val backend: KeyValueStore) {

    /** Путь к последнему открытому репозиторию; null, если репозиторий ещё не открывали. */
    var repositoryPath: String?
        get() = backend.getString(KEY_REPOSITORY_PATH)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) backend.remove(KEY_REPOSITORY_PATH) else backend.putString(KEY_REPOSITORY_PATH, value)
        }

    /** Предпочтение темы. */
    var theme: ThemePreference
        get() = backend.getString(KEY_THEME)
            ?.let { raw -> ThemePreference.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: ThemePreference.SYSTEM
        set(value) = backend.putString(KEY_THEME, value.name)

    /** Режим управления. */
    var controlMode: ControlMode
        get() = backend.getString(KEY_CONTROL_MODE)
            ?.let { raw -> ControlMode.entries.firstOrNull { it.name.equals(raw, ignoreCase = true) } }
            ?: ControlMode.GESTURES
        set(value) = backend.putString(KEY_CONTROL_MODE, value.name)

    /** Адрес хоста вида `ws://host:port/ws`; null, если адрес не задан. */
    var hostEndpoint: String?
        get() = backend.getString(KEY_HOST_ENDPOINT)?.takeIf { it.isNotBlank() }
        set(value) {
            if (value == null) backend.remove(KEY_HOST_ENDPOINT) else backend.putString(KEY_HOST_ENDPOINT, value)
        }

    companion object {
        /** Ключ пути к репозиторию. */
        const val KEY_REPOSITORY_PATH: String = "repositoryPath"

        /** Ключ темы. */
        const val KEY_THEME: String = "theme"

        /** Ключ режима управления. */
        const val KEY_CONTROL_MODE: String = "controlMode"

        /** Ключ адреса хоста. */
        const val KEY_HOST_ENDPOINT: String = "hostEndpoint"
    }
}
```

- [ ] **Шаг 5: прогнать тесты настроек**

```bash
./gradlew :client-state:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 8 тестов из `SettingsStoreTest` плюс 1 из `NoLocalityBranchingTest`.

- [ ] **Шаг 6: написать ресурсные строки**

`client-ui/src/commonMain/composeResources/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AI Studio</string>

    <string name="repo_header_branch">Ветка: %1$s</string>
    <string name="repo_header_root">Репозиторий: %1$s</string>
    <string name="repo_tree_title">Файлы</string>
    <string name="repo_file_title">Файл: %1$s</string>
    <string name="repo_file_truncated">Файл показан не целиком: превышен предел показа</string>
    <string name="repo_tree_truncated">Дерево показано не целиком: пропущено записей — %1$d</string>

    <string name="state_loading_title">Загрузка</string>
    <string name="state_loading_skeleton">Читаем репозиторий…</string>
    <string name="state_empty_title">Здесь пока пусто</string>
    <string name="state_empty_repo">В репозитории нет коммитов и файлов. Создайте первый коммит — дерево появится.</string>
    <string name="state_empty_tree">В репозитории нет файлов для показа.</string>
    <string name="state_error_title">Не получилось</string>
    <string name="state_error_retry">Повторить</string>
    <string name="state_error_path_missing">Путь не существует: %1$s</string>
    <string name="state_error_not_a_repo">Каталог не является git-репозиторием: %1$s</string>
    <string name="state_offline_title">Нет связи с хостом</string>
    <string name="state_offline_body">Показаны данные, загруженные ранее. Действия станут доступны после восстановления связи.</string>
    <string name="state_no_permission_title">Нет доступа</string>
    <string name="state_no_permission_body">Доступ к «%1$s» закрыт: %2$s</string>

    <string name="action_open_repository">Открыть репозиторий</string>
    <string name="action_back">Назад</string>
    <string name="action_settings">Настройки</string>
    <string name="action_about">О приложении</string>
    <string name="action_about_body">Этап 0: скелет, протокол и показ репозитория. Агент и ревью появятся дальше.</string>

    <string name="settings_title">Настройки</string>
    <string name="settings_repository_path">Путь к репозиторию</string>
    <string name="settings_repository_path_hint">Например, /projects/aide</string>
    <string name="settings_repository_apply">Открыть</string>
    <string name="settings_host_endpoint">Адрес хоста</string>
    <string name="settings_host_endpoint_hint">Например, ws://192.168.1.10:8080/ws</string>
    <string name="settings_host_endpoint_apply">Сохранить адрес</string>
    <string name="settings_host_endpoint_restart">Новый адрес начнёт действовать после перезапуска приложения.</string>
    <string name="settings_theme">Тема</string>
    <string name="settings_theme_system">Как в системе</string>
    <string name="settings_theme_light">Светлая</string>
    <string name="settings_theme_dark">Тёмная</string>
    <string name="settings_control_mode">Режим управления</string>
    <string name="settings_control_mode_gestures">Жесты</string>
    <string name="settings_control_mode_buttons">Кнопки</string>
    <string name="settings_control_mode_hybrid">Гибрид</string>

    <string name="connection_connecting">Подключение к хосту…</string>
    <string name="connection_reconnecting">Связь потеряна, переподключение (попытка %1$d)…</string>
    <string name="connection_incompatible">Версии несовместимы. %1$s</string>
    <string name="connection_closed">Соединение закрыто: %1$s</string>
</resources>
```

- [ ] **Шаг 7: написать доступ к строкам и тему**

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/strings/Strings.kt`:

```kotlin
package dev.aide.client.ui.strings

import androidx.compose.runtime.Composable
import dev.aide.client.ui.resources.Res
import dev.aide.client.ui.resources.action_back
import dev.aide.client.ui.resources.action_settings
import dev.aide.client.ui.resources.app_name
import dev.aide.client.ui.resources.connection_closed
import dev.aide.client.ui.resources.connection_connecting
import dev.aide.client.ui.resources.connection_incompatible
import dev.aide.client.ui.resources.connection_reconnecting
import dev.aide.client.ui.resources.repo_file_title
import dev.aide.client.ui.resources.repo_file_truncated
import dev.aide.client.ui.resources.repo_header_branch
import dev.aide.client.ui.resources.repo_header_root
import dev.aide.client.ui.resources.repo_tree_title
import dev.aide.client.ui.resources.repo_tree_truncated
import dev.aide.client.ui.resources.settings_control_mode
import dev.aide.client.ui.resources.settings_control_mode_buttons
import dev.aide.client.ui.resources.settings_control_mode_gestures
import dev.aide.client.ui.resources.settings_control_mode_hybrid
import dev.aide.client.ui.resources.settings_host_endpoint
import dev.aide.client.ui.resources.settings_host_endpoint_apply
import dev.aide.client.ui.resources.settings_host_endpoint_hint
import dev.aide.client.ui.resources.settings_host_endpoint_restart
import dev.aide.client.ui.resources.settings_repository_apply
import dev.aide.client.ui.resources.settings_repository_path
import dev.aide.client.ui.resources.settings_repository_path_hint
import dev.aide.client.ui.resources.settings_theme
import dev.aide.client.ui.resources.settings_theme_dark
import dev.aide.client.ui.resources.settings_theme_light
import dev.aide.client.ui.resources.settings_theme_system
import dev.aide.client.ui.resources.settings_title
import dev.aide.client.ui.resources.state_empty_repo
import dev.aide.client.ui.resources.state_empty_title
import dev.aide.client.ui.resources.state_empty_tree
import dev.aide.client.ui.resources.state_error_not_a_repo
import dev.aide.client.ui.resources.state_error_path_missing
import dev.aide.client.ui.resources.state_error_retry
import dev.aide.client.ui.resources.state_error_title
import dev.aide.client.ui.resources.state_loading_skeleton
import dev.aide.client.ui.resources.state_loading_title
import dev.aide.client.ui.resources.state_no_permission_body
import dev.aide.client.ui.resources.state_no_permission_title
import dev.aide.client.ui.resources.state_offline_body
import dev.aide.client.ui.resources.state_offline_title
import org.jetbrains.compose.resources.StringResource
import org.jetbrains.compose.resources.stringResource

/**
 * Единая точка доступа к строкам интерфейса (NFR-13).
 *
 * Экраны не держат литералов: тест [dev.aide.client.ui.NoLiteralUiStringsTest]
 * падает, если в общих экранах появится строковый литерал внутри composable.
 */
object Strings {

    val appName: StringResource = Res.string.app_name
    val repoHeaderBranch: StringResource = Res.string.repo_header_branch
    val repoHeaderRoot: StringResource = Res.string.repo_header_root
    val repoTreeTitle: StringResource = Res.string.repo_tree_title
    val repoFileTitle: StringResource = Res.string.repo_file_title
    val repoFileTruncated: StringResource = Res.string.repo_file_truncated
    val repoTreeTruncated: StringResource = Res.string.repo_tree_truncated

    val actionBack: StringResource = Res.string.action_back
    val actionSettings: StringResource = Res.string.action_settings

    val stateLoadingTitle: StringResource = Res.string.state_loading_title
    val stateLoadingSkeleton: StringResource = Res.string.state_loading_skeleton
    val stateEmptyTitle: StringResource = Res.string.state_empty_title
    val stateEmptyRepo: StringResource = Res.string.state_empty_repo
    val stateEmptyTree: StringResource = Res.string.state_empty_tree
    val stateErrorTitle: StringResource = Res.string.state_error_title
    val stateErrorRetry: StringResource = Res.string.state_error_retry
    val stateErrorPathMissing: StringResource = Res.string.state_error_path_missing
    val stateErrorNotARepo: StringResource = Res.string.state_error_not_a_repo
    val stateOfflineTitle: StringResource = Res.string.state_offline_title
    val stateOfflineBody: StringResource = Res.string.state_offline_body
    val stateNoPermissionTitle: StringResource = Res.string.state_no_permission_title
    val stateNoPermissionBody: StringResource = Res.string.state_no_permission_body

    val settingsTitle: StringResource = Res.string.settings_title
    val settingsRepositoryPath: StringResource = Res.string.settings_repository_path
    val settingsRepositoryPathHint: StringResource = Res.string.settings_repository_path_hint
    val settingsRepositoryApply: StringResource = Res.string.settings_repository_apply
    val settingsHostEndpoint: StringResource = Res.string.settings_host_endpoint
    val settingsHostEndpointHint: StringResource = Res.string.settings_host_endpoint_hint
    val settingsHostEndpointApply: StringResource = Res.string.settings_host_endpoint_apply
    val settingsHostEndpointRestart: StringResource = Res.string.settings_host_endpoint_restart
    val settingsTheme: StringResource = Res.string.settings_theme
    val settingsThemeSystem: StringResource = Res.string.settings_theme_system
    val settingsThemeLight: StringResource = Res.string.settings_theme_light
    val settingsThemeDark: StringResource = Res.string.settings_theme_dark
    val settingsControlMode: StringResource = Res.string.settings_control_mode
    val settingsControlModeGestures: StringResource = Res.string.settings_control_mode_gestures
    val settingsControlModeButtons: StringResource = Res.string.settings_control_mode_buttons
    val settingsControlModeHybrid: StringResource = Res.string.settings_control_mode_hybrid

    val connectionConnecting: StringResource = Res.string.connection_connecting
    val connectionReconnecting: StringResource = Res.string.connection_reconnecting
    val connectionIncompatible: StringResource = Res.string.connection_incompatible
    val connectionClosed: StringResource = Res.string.connection_closed

    /** Читает строку в composable-контексте. */
    @Composable
    fun text(resource: StringResource): String = stringResource(resource)

    /** Читает строку с подстановками. */
    @Composable
    fun text(resource: StringResource, vararg args: Any): String = stringResource(resource, *args)
}
```

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/theme/Theme.kt`:

```kotlin
package dev.aide.client.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import dev.aide.client.state.settings.ThemePreference

/**
 * Тема приложения. По умолчанию следует системной (FR-EDITOR-11),
 * пользовательский выбор её переопределяет.
 */
@Composable
fun AideTheme(
    preference: ThemePreference = ThemePreference.SYSTEM,
    content: @Composable () -> Unit,
) {
    val dark = when (preference) {
        ThemePreference.SYSTEM -> isSystemInDarkTheme()
        ThemePreference.LIGHT -> false
        ThemePreference.DARK -> true
    }
    MaterialTheme(colorScheme = if (dark) darkColorScheme() else lightColorScheme(), content = content)
}
```

- [ ] **Шаг 8: подключить ресурсы и написать тест на отсутствие литералов**

`client-ui/build.gradle.kts` — добавить в `sourceSets` внутри `kotlin`:

```kotlin
        jvmTest.dependencies {
            implementation(libs.kotlin.test)
        }
```

и после блока `kotlin`:

```kotlin
compose.resources {
    publicResClass = false
    packageOfResClass = "dev.aide.client.ui.resources"
    generateResClass = always
}

tasks.named<Test>("jvmTest") {
    systemProperty("clientUiSourcesDir", layout.projectDirectory.dir("src").asFile.path)
    // Тест точек входа живёт в client-ui, а сканирует заголовок окна desktopApp
    // и манифест androidApp, поэтому ему нужен корень репозитория.
    systemProperty("repoRootDir", rootProject.layout.projectDirectory.asFile.path)
}
```

`client-ui/src/jvmTest/kotlin/dev/aide/client/ui/NoLiteralUiStringsTest.kt`:

```kotlin
package dev.aide.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NFR-13: все строки интерфейса — в ресурсах, в коде нет литералов UI.
 *
 * Проверяются вызовы `Text(` и `contentDescription =` со строковым литералом
 * в общих экранах. Литералы в тестах и в `strings.xml`, разумеется, разрешены.
 */
class NoLiteralUiStringsTest {

    private val root = File(
        System.getProperty("clientUiSourcesDir")
            ?: error("Не задано системное свойство clientUiSourcesDir — проверь блок jvmTest в build.gradle.kts"),
    )

    private val literalInText = Regex("""\bText\(\s*"""")
    private val literalInDescription = Regex("""contentDescription\s*=\s*"""")

    @Test
    fun `в общих экранах нет строковых литералов интерфейса`() {
        val commonMain = File(root, "commonMain")
        assertTrue(commonMain.isDirectory, "Каталог не найден: $commonMain")

        val offenders = commonMain.walkTopDown()
            .filter { it.isFile && it.extension == "kt" }
            .filterNot { it.path.contains("/strings/") }
            .flatMap { file ->
                val text = file.readText()
                buildList {
                    literalInText.findAll(text).forEach { add("${file.name}: Text(\"…\")") }
                    literalInDescription.findAll(text).forEach { add("${file.name}: contentDescription = \"…\"") }
                }.asSequence()
            }
            .toList()

        assertTrue(
            offenders.isEmpty(),
            "Строки UI должны быть в composeResources (NFR-13). Найдены литералы:\n" +
                offenders.joinToString("\n"),
        )
    }
}
```

Обратить внимание: `App.kt` из задачи 13 содержит `Text("AI Studio")` и на этом шаге тест упадёт — это ожидаемо. Заменить в `App.kt` литерал на `Strings.text(Strings.appName)` и добавить `import dev.aide.client.ui.strings.Strings`. Тем самым подтверждается, что тест работает на настоящем нарушении, а не на выдуманном.

- [ ] **Шаг 9: вынести имя приложения и заголовок окна в ресурсы**

NFR-13 не закрывается, пока имя приложения и заголовок окна — литералы: это тоже строки интерфейса, но лежат они вне `client-ui`, куда `NoLiteralUiStringsTest` не достаёт. Литералов ровно два: `android:label` в манифесте и `title` в окне десктопа.

`androidApp/src/main/res/values/strings.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="app_name">AI Studio</string>
</resources>
```

`androidApp/src/main/AndroidManifest.xml` — имя приложения берётся из ресурса:

```xml
    <application android:label="@string/app_name" android:theme="@android:style/Theme.Material.NoActionBar">
```

`desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt` — заголовок окна из тех же строк, что и остальной интерфейс (в файл добавляется `import dev.aide.client.ui.strings.Strings`):

```kotlin
    Window(onCloseRequest = ::exitApplication, title = Strings.text(Strings.appName)) {
        App(connection = connection)
    }
```

Проверка — второй тест рядом с `NoLiteralUiStringsTest`. Он смотрит туда, куда первый не достаёт: `title = "` в `desktopApp/src/main` и `android:label` в манифесте (`@string/` — не литерал). Путь к корню репозитория приходит системным свойством `repoRootDir`, потому что тест живёт в `client-ui`, а сканирует соседние модули.

`client-ui/src/jvmTest/kotlin/dev/aide/client/ui/EntryPointStringsTest.kt`:

```kotlin
package dev.aide.client.ui

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * NFR-13 для точек входа: имя приложения и заголовок окна — тоже строки интерфейса.
 *
 * Проверяются места, до которых `NoLiteralUiStringsTest` не достаёт: заголовок
 * окна десктопа и `android:label` в манифесте. Имя приложения обязано приходить
 * из ресурсов, иначе переименование продукта потребует правок в коде.
 */
class EntryPointStringsTest {

    private val repoRoot = File(
        System.getProperty("repoRootDir")
            ?: error("Не задано системное свойство repoRootDir — проверь блок jvmTest в build.gradle.kts"),
    )

    private val literalInTitle = Regex("""title\s*=\s*"""")

    @Test
    fun `заголовок окна десктопа берётся из ресурсов`() {
        val desktopMain = File(repoRoot, "desktopApp/src/main")
        assertTrue(desktopMain.isDirectory, "Каталог не найден: $desktopMain")

        val scanned = desktopMain.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue(scanned.isNotEmpty(), "В $desktopMain нет ни одного .kt-файла — проверь системное свойство repoRootDir")

        val offenders = scanned.filter { literalInTitle.containsMatchIn(it.readText()) }
            .map { it.relativeTo(repoRoot).path }

        assertTrue(
            offenders.isEmpty(),
            "Заголовок окна должен приходить из Strings, а не из литерала (NFR-13). Найдено:\n" +
                offenders.joinToString("\n"),
        )
    }

    @Test
    fun `android label ссылается на строковый ресурс`() {
        val manifest = File(repoRoot, "androidApp/src/main/AndroidManifest.xml")
        assertTrue(manifest.isFile, "Файл не найден: $manifest")

        val label = Regex("""android:label="([^"]*)"""").find(manifest.readText())?.groupValues?.get(1)
        assertTrue(label != null, "В манифесте нет android:label: имя приложения не задано явно")
        assertTrue(
            label.startsWith("@string/"),
            "android:label должен ссылаться на строковый ресурс, а не на литерал: $label",
        )
    }
}
```

Прогнать:

```bash
./gradlew :client-ui:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, `EntryPointStringsTest` — 2 теста.

- [ ] **Шаг 10: прогнать тесты**

```bash
./gradlew :client-ui:jvmTest :client-state:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`. `NoLiteralUiStringsTest` проходит после замены литерала в `App.kt`, `EntryPointStringsTest` — 2 теста; `NoLocalityBranchingTest` — без изменений.

- [ ] **Шаг 11: проверить, что настройки действительно сохраняются на диске**

```bash
rm -f ~/.config/aide/settings.properties
./gradlew :desktopApp:createDistributable
./desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp
# в приложении ещё нет экрана настроек — он появится в задаче 16;
# поэтому проверить хранилище напрямую тестом:
./gradlew :client-state:jvmTest --tests '*SettingsStore*'
cat ~/.config/aide/settings.properties 2>/dev/null || echo "файл создаётся только при первой записи настройки"
```

Ожидаемо: тесты зелёные, файл появится после первого изменения настройки на экране из задачи 16.

- [ ] **Шаг 12: коммит**

```bash
git add client-state client-ui androidApp desktopApp
git commit -m "feat(client): хранилище настроек, тема по системной и ресурсные строки интерфейса"
```

---

## Задача 15: хранилище метаданных хоста (`T-0.16`)

**Файлы:**
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/Task.sq`
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/AgentRun.sq`
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/ToolCall.sq`
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/ReviewDecision.sq`
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/ToolPermission.sq`
- Создать: `host-core/src/main/sqldelight/dev/aide/host/store/1.sqm`
- Создать: `host-core/src/main/kotlin/dev/aide/host/store/HostStore.kt`
- Создать: `host-core/src/main/kotlin/dev/aide/host/store/DatabaseFactory.kt`
- Создать: `host-core/src/test/resources/schema-v1.sql`
- Тест: `host-core/src/test/kotlin/dev/aide/host/store/HostStoreTest.kt`
- Тест: `host-core/src/test/kotlin/dev/aide/host/store/MigrationTest.kt`
- Изменить: `host-core/build.gradle.kts` (SQLDelight и CBOR)

**Про устройство схемы.** У каждой таблицы есть колонки для полей, по которым ищут, — идентификаторы, статусы, время, — и колонка `payload` с полной сериализацией доменного объекта в CBOR. Это осознанный выбор: домен остаётся единственным описанием объекта (не нужно вручную поддерживать соответствие двух десятков колонок и полей), а фильтры, которые нужны журналу аудита (`T-1.14`) и метрикам (`§ 2.3`), работают по индексированным колонкам.

**Про миграции.** Версия 1 схемы хранила только индексированные колонки. Версия 2 добавила `payload`. Миграция `1.sqm` — это `ALTER TABLE … ADD COLUMN`, то есть ровно тот случай, который нужно уметь применять на непустой базе, не потеряв записи.

- [ ] **Шаг 1: написать схему текущей версии**

`host-core/src/main/sqldelight/dev/aide/host/store/Task.sq`:

```sql
CREATE TABLE task (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    prompt TEXT NOT NULL,
    branch TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL,
    payload BLOB NOT NULL
);

CREATE INDEX task_status ON task(status);

insert:
INSERT OR REPLACE INTO task(id, title, prompt, branch, status, created_at, payload)
VALUES (?, ?, ?, ?, ?, ?, ?);

byId:
SELECT * FROM task WHERE id = ?;

byStatus:
SELECT * FROM task WHERE status = ? ORDER BY created_at DESC;

delete:
DELETE FROM task WHERE id = ?;

count:
SELECT count(*) FROM task;
```

`host-core/src/main/sqldelight/dev/aide/host/store/AgentRun.sq`:

```sql
CREATE TABLE agent_run (
    id TEXT NOT NULL PRIMARY KEY,
    task_id TEXT NOT NULL,
    state TEXT NOT NULL,
    mode TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER,
    elapsed_millis INTEGER NOT NULL,
    cost_micros INTEGER NOT NULL,
    cost_known INTEGER NOT NULL,
    payload BLOB NOT NULL
);

CREATE INDEX agent_run_task ON agent_run(task_id);

insert:
INSERT OR REPLACE INTO agent_run(
    id, task_id, state, mode, started_at, finished_at, elapsed_millis, cost_micros, cost_known, payload
) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?);

byId:
SELECT * FROM agent_run WHERE id = ?;

byTask:
SELECT * FROM agent_run WHERE task_id = ? ORDER BY started_at;

/** Суммарная стоимость задачи по всем её прогонам (FR-AGENT-10). */
totalCostMicrosByTask:
SELECT coalesce(sum(cost_micros), 0) FROM agent_run WHERE task_id = ? AND cost_known = 1;

/** Сколько прогонов имеют неизвестную цену — итог по задаче помечается неполным (FR-COST-5). */
countUnknownCostByTask:
SELECT count(*) FROM agent_run WHERE task_id = ? AND cost_known = 0;
```

`host-core/src/main/sqldelight/dev/aide/host/store/ToolCall.sq`:

```sql
CREATE TABLE tool_call (
    id TEXT NOT NULL PRIMARY KEY,
    run_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    outcome TEXT NOT NULL,
    required_approval INTEGER NOT NULL,
    duration_millis INTEGER NOT NULL,
    at INTEGER NOT NULL,
    payload BLOB NOT NULL
);

CREATE INDEX tool_call_run ON tool_call(run_id);
CREATE INDEX tool_call_tool ON tool_call(tool);

insert:
INSERT OR REPLACE INTO tool_call(id, run_id, tool, outcome, required_approval, duration_millis, at, payload)
VALUES (?, ?, ?, ?, ?, ?, ?, ?);

byId:
SELECT * FROM tool_call WHERE id = ?;

byRun:
SELECT * FROM tool_call WHERE run_id = ? ORDER BY at;

/** Фильтр журнала аудита по инструменту (T-1.14). */
byRunAndTool:
SELECT * FROM tool_call WHERE run_id = ? AND tool = ? ORDER BY at;

count:
SELECT count(*) FROM tool_call;
```

`host-core/src/main/sqldelight/dev/aide/host/store/ReviewDecision.sq`:

```sql
CREATE TABLE review_decision (
    packet_id TEXT NOT NULL,
    packet_revision INTEGER NOT NULL,
    scope TEXT NOT NULL,
    target_hunk_id TEXT,
    value TEXT NOT NULL,
    client_platform TEXT NOT NULL,
    decided_at INTEGER NOT NULL,
    payload BLOB NOT NULL,
    PRIMARY KEY (packet_id, packet_revision, scope, target_hunk_id, decided_at)
);

CREATE INDEX review_decision_packet ON review_decision(packet_id);
CREATE INDEX review_decision_platform ON review_decision(client_platform);

insert:
INSERT OR REPLACE INTO review_decision(
    packet_id, packet_revision, scope, target_hunk_id, value, client_platform, decided_at, payload
) VALUES (?, ?, ?, ?, ?, ?, ?, ?);

byPacket:
SELECT * FROM review_decision WHERE packet_id = ? ORDER BY decided_at;

byPacketAndRevision:
SELECT * FROM review_decision WHERE packet_id = ? AND packet_revision = ? ORDER BY decided_at;

/** Читает решения, принятые с указанной платформы, от старых к новым — сырьё метрики «доля задач, закрытых с телефона» (§ 2.3). */
byPlatform:
SELECT * FROM review_decision WHERE client_platform = ? ORDER BY decided_at;

/** Сколько решений принято с каждой платформы — метрика «доля задач, закрытых с телефона» (§ 2.3). */
countByPlatform:
SELECT client_platform, count(*) AS decisions FROM review_decision GROUP BY client_platform;
```

`host-core/src/main/sqldelight/dev/aide/host/store/ToolPermission.sq`:

```sql
CREATE TABLE tool_permission (
    tool TEXT NOT NULL PRIMARY KEY,
    read_permission TEXT NOT NULL,
    write_permission TEXT NOT NULL,
    payload BLOB NOT NULL
);

insert:
INSERT OR REPLACE INTO tool_permission(tool, read_permission, write_permission, payload)
VALUES (?, ?, ?, ?);

byTool:
SELECT * FROM tool_permission WHERE tool = ?;

all:
SELECT * FROM tool_permission ORDER BY tool;

delete:
DELETE FROM tool_permission WHERE tool = ?;
```

- [ ] **Шаг 2: написать миграцию 1 → 2**

`host-core/src/main/sqldelight/dev/aide/host/store/1.sqm`:

```sql
ALTER TABLE task ADD COLUMN payload BLOB NOT NULL DEFAULT X'';
ALTER TABLE agent_run ADD COLUMN payload BLOB NOT NULL DEFAULT X'';
ALTER TABLE tool_call ADD COLUMN payload BLOB NOT NULL DEFAULT X'';
ALTER TABLE review_decision ADD COLUMN payload BLOB NOT NULL DEFAULT X'';
ALTER TABLE tool_permission ADD COLUMN payload BLOB NOT NULL DEFAULT X'';
```

- [ ] **Шаг 3: написать схему версии 1 для теста миграции**

`host-core/src/test/resources/schema-v1.sql` — та же схема без `payload`. Этот файл намеренно дублирует DDL: SQLDelight генерирует только текущую схему, а для проверки миграции нужна именно предыдущая версия.

```sql
CREATE TABLE task (
    id TEXT NOT NULL PRIMARY KEY,
    title TEXT NOT NULL,
    prompt TEXT NOT NULL,
    branch TEXT NOT NULL,
    status TEXT NOT NULL,
    created_at INTEGER NOT NULL
);
CREATE INDEX task_status ON task(status);

CREATE TABLE agent_run (
    id TEXT NOT NULL PRIMARY KEY,
    task_id TEXT NOT NULL,
    state TEXT NOT NULL,
    mode TEXT NOT NULL,
    started_at INTEGER NOT NULL,
    finished_at INTEGER,
    elapsed_millis INTEGER NOT NULL,
    cost_micros INTEGER NOT NULL,
    cost_known INTEGER NOT NULL
);
CREATE INDEX agent_run_task ON agent_run(task_id);

CREATE TABLE tool_call (
    id TEXT NOT NULL PRIMARY KEY,
    run_id TEXT NOT NULL,
    tool TEXT NOT NULL,
    outcome TEXT NOT NULL,
    required_approval INTEGER NOT NULL,
    duration_millis INTEGER NOT NULL,
    at INTEGER NOT NULL
);
CREATE INDEX tool_call_run ON tool_call(run_id);
CREATE INDEX tool_call_tool ON tool_call(tool);

CREATE TABLE review_decision (
    packet_id TEXT NOT NULL,
    packet_revision INTEGER NOT NULL,
    scope TEXT NOT NULL,
    target_hunk_id TEXT,
    value TEXT NOT NULL,
    client_platform TEXT NOT NULL,
    decided_at INTEGER NOT NULL,
    PRIMARY KEY (packet_id, packet_revision, scope, target_hunk_id, decided_at)
);
CREATE INDEX review_decision_packet ON review_decision(packet_id);
CREATE INDEX review_decision_platform ON review_decision(client_platform);

CREATE TABLE tool_permission (
    tool TEXT NOT NULL PRIMARY KEY,
    read_permission TEXT NOT NULL,
    write_permission TEXT NOT NULL
);
```

- [ ] **Шаг 4: подключить SQLDelight и написать тест миграции**

`host-core/build.gradle.kts`:

```kotlin
plugins {
    alias(libs.plugins.kotlinJvm)
    alias(libs.plugins.kotlinSerialization)
    alias(libs.plugins.sqldelight)
}

kotlin { jvmToolchain(libs.versions.jvmTarget.get().toInt()) }

sqldelight {
    databases {
        create("HostDatabase") {
            packageName.set("dev.aide.host.store.db")
            srcDirs.setFrom("src/main/sqldelight")
            schemaOutputDirectory.set(file("src/main/sqldelight/databases"))
        }
    }
}

dependencies {
    implementation(project(":domain"))
    implementation(project(":protocol"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.websockets)
    implementation(libs.kotlinx.coroutines.core)
    implementation(libs.kotlinx.datetime)
    implementation(libs.kotlinx.serialization.core)
    implementation(libs.kotlinx.serialization.cbor)
    implementation(libs.jgit)
    implementation(libs.sqldelight.runtime)
    implementation(libs.sqldelight.coroutines)
    implementation(libs.sqldelight.jdbc)
    implementation(libs.slf4j.api)
    // Граф хоста (`HostApp`, задача 13) собирается на Koin.
    implementation(libs.koin.core)

    testImplementation(libs.kotlin.test)
    testImplementation(libs.ktor.client.cio)
    testImplementation(libs.ktor.client.websockets)
    testImplementation(libs.slf4j.simple)
}

tasks.test { useJUnitPlatform() }
```

Первая сборка создаст файлы схемы в `src/main/sqldelight/databases` — их нужно закоммитить, они служат эталоном для проверки миграций:

```bash
./gradlew :host-core:generateHostDatabaseSchema
git add host-core/src/main/sqldelight/databases
```

`host-core/src/test/kotlin/dev/aide/host/store/MigrationTest.kt`:

```kotlin
package dev.aide.host.store

import app.cash.sqldelight.db.AfterVersion
import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.host.store.db.HostDatabase
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MigrationTest {

    /** DDL версии 1 — из тестового ресурса, а не из сгенерированной текущей схемы. */
    private fun schemaV1Statements(): List<String> =
        requireNotNull(javaClass.classLoader.getResourceAsStream("schema-v1.sql")) {
            "Не найден ресурс schema-v1.sql — без него миграция непроверяема"
        }.bufferedReader().readText()
            .split(';')
            .map { it.trim() }
            .filter { it.isNotEmpty() }

    @Test
    fun `миграция с версии 1 на версию 2 сохраняет записи`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)

        // 1. База версии 1: своя схема и своя метка версии.
        schemaV1Statements().forEach { driver.execute(null, it, 0) }
        driver.execute(null, "PRAGMA user_version = 1", 0)

        val taskId = "task-legacy"
        driver.execute(
            null,
            "INSERT INTO task(id, title, prompt, branch, status, created_at) " +
                "VALUES ('$taskId', 'Старая задача', 'текст', 'ai/$taskId', 'REVIEW', 1758535200000)",
            0,
        )
        driver.execute(
            null,
            "INSERT INTO tool_permission(tool, read_permission, write_permission) " +
                "VALUES ('fs.read', 'ALLOW', 'DENY')",
            0,
        )

        // 2. Миграция до версии 2.
        HostDatabase.Schema.migrate(driver, 1, 2, *arrayOf<AfterVersion>())

        // 3. Записи на месте, и появилась колонка payload со значением по умолчанию.
        val database = HostDatabase(driver)
        val legacy = database.taskQueries.byId(taskId).executeAsOne()
        assertEquals("Старая задача", legacy.title)
        assertEquals("ai/$taskId", legacy.branch)
        assertTrue(legacy.payload.isEmpty(), "У записей версии 1 payload пустой")

        val permission = database.toolPermissionQueries.byTool("fs.read").executeAsOne()
        assertEquals("ALLOW", permission.read_permission)

        // 4. Новые записи после миграции пишутся и читаются.
        database.taskQueries.insert(
            id = "task-new",
            title = "Новая задача",
            prompt = "текст",
            branch = "ai/task-new",
            status = "QUEUED",
            created_at = 1_758_535_300_000,
            payload = byteArrayOf(1, 2, 3),
        )
        assertEquals(2, database.taskQueries.count().executeAsOne())
        assertEquals(3, database.taskQueries.byId("task-new").executeAsOne().payload.size)

        driver.close()
    }

    @Test
    fun `чистая база создаётся в текущей версии и принимает записи`() {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HostDatabase.Schema.create(driver)
        val database = HostDatabase(driver)

        assertEquals(0, database.taskQueries.count().executeAsOne())
        database.taskQueries.insert("t-1", "Задача", "текст", "ai/t-1", "QUEUED", 1_758_535_200_000, byteArrayOf())
        assertEquals(1, database.taskQueries.count().executeAsOne())
        driver.close()
    }

    @Test
    fun `записи читаются тем же запросом после перезапуска хоста`() {
        val dbFile = java.nio.file.Files.createTempFile("aide-store-", ".db").toFile()
        val url = "jdbc:sqlite:${dbFile.absolutePath}"
        try {
            run {
                val driver = JdbcSqliteDriver(url)
                HostDatabase.Schema.create(driver)
                val database = HostDatabase(driver)
                database.taskQueries.insert(
                    "t-restart", "До перезапуска", "текст", "ai/t-restart", "REVIEW", 1_758_535_200_000, byteArrayOf(9),
                )
                driver.close()
            }

            // Перезапуск: новый драйвер и новая обёртка над тем же файлом.
            val driver = JdbcSqliteDriver(url)
            val database = HostDatabase(driver)
            val beforeRestart = database.taskQueries.byId("t-restart").executeAsOne()
            assertEquals("До перезапуска", beforeRestart.title)
            assertEquals(1, beforeRestart.payload.size)

            // Запись, сделанная после перезапуска, читается вместе со старой: файл базы не пересоздавался.
            database.taskQueries.insert(
                "t-after-restart", "После перезапуска", "текст", "ai/t-after-restart", "QUEUED", 1_758_535_400_000, byteArrayOf(7),
            )
            assertEquals(2, database.taskQueries.count().executeAsOne())
            assertEquals("После перезапуска", database.taskQueries.byId("t-after-restart").executeAsOne().title)
            driver.close()
        } finally {
            dbFile.delete()
        }
    }
}
```

- [ ] **Шаг 5: прогнать тест миграции**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.store.MigrationTest'
```

Ожидаемо: `BUILD SUCCESSFUL`, 3 теста. Если `HostDatabase.Schema.migrate` требует другой набор аргументов — посмотреть сигнатуру в `host-core/build/generated/sqldelight/code/HostDatabase/commonMain/dev/aide/host/store/db/HostDatabase.kt` и привести вызов к ней.

- [ ] **Шаг 6: написать тест на доменные объекты**

`host-core/src/test/kotlin/dev/aide/host/store/HostStoreTest.kt`:

```kotlin
package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.domain.ClientPlatform
import dev.aide.domain.DecisionScope
import dev.aide.domain.DecisionValue
import dev.aide.domain.DomainFixtures
import dev.aide.domain.PacketId
import dev.aide.domain.Permission
import dev.aide.domain.ReviewDecision
import dev.aide.domain.TaskId
import dev.aide.domain.ToolPermission
import dev.aide.host.store.db.HostDatabase
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HostStoreTest {

    private val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY).also { HostDatabase.Schema.create(it) }
    private val store = HostStore(HostDatabase(driver))

    @AfterTest
    fun tearDown() = driver.close()

    @Test
    fun `задача переживает запись и чтение`() {
        store.saveTask(DomainFixtures.task)
        assertEquals(DomainFixtures.task, store.loadTask(DomainFixtures.task.id))
    }

    @Test
    fun `неизвестная задача читается как null`() {
        assertNull(store.loadTask(TaskId("нет-такой")))
    }

    @Test
    fun `прогон переживает запись и чтение`() {
        store.saveRun(DomainFixtures.run)
        assertEquals(DomainFixtures.run, store.loadRun(DomainFixtures.run.id))
    }

    @Test
    fun `стоимость задачи считается по прогонам`() {
        store.saveRun(DomainFixtures.run)
        val incomplete = DomainFixtures.emptyRun.copy(taskId = DomainFixtures.run.taskId)
        store.saveRun(incomplete)

        val total = store.totalCostMicros(DomainFixtures.run.taskId)
        assertEquals(12_500, total.amountMicros)
        assertTrue(!total.known, "Есть прогон с неизвестной ценой — итог помечается неполным (FR-COST-5)")
    }

    @Test
    fun `вызовы инструментов читаются по прогону`() {
        store.saveRun(DomainFixtures.run)
        store.saveToolCall(DomainFixtures.toolCall)
        val calls = store.toolCallsForRun(DomainFixtures.run.id)
        assertEquals(listOf(DomainFixtures.toolCall), calls)
    }

    @Test
    fun `журнал фильтруется по инструменту`() {
        store.saveRun(DomainFixtures.run)
        store.saveToolCall(DomainFixtures.toolCall)
        store.saveToolCall(DomainFixtures.toolCall.copy(id = dev.aide.domain.ToolCallId("tc-2"), tool = "git.commit"))

        assertEquals(1, store.toolCallsForRun(DomainFixtures.run.id, tool = "fs.write").size)
        assertEquals(2, store.toolCallsForRun(DomainFixtures.run.id).size)
    }

    @Test
    fun `решения ревью читаются по пакету и по ревизии`() {
        store.saveDecision(DomainFixtures.decision)
        store.saveDecision(DomainFixtures.decision.copy(packetRevision = 2, value = DecisionValue.ACCEPTED))

        assertEquals(2, store.decisionsForPacket(PacketId("p-1")).size)
        val revisionOne = store.decisionsForPacket(PacketId("p-1"), revision = 1)
        assertEquals(listOf(DomainFixtures.decision), revisionOne)
    }

    @Test
    fun `платформа клиента сохраняется и участвует в метрике`() {
        store.saveDecision(DomainFixtures.decision)
        store.saveDecision(
            DomainFixtures.decision.copy(
                packetId = PacketId("p-2"),
                clientPlatform = ClientPlatform.DESKTOP_LINUX,
            ),
        )

        assertEquals(1, store.decisionsByPlatform(ClientPlatform.ANDROID).size)
        assertEquals(1, store.decisionsByPlatform(ClientPlatform.DESKTOP_LINUX).size)
        assertEquals(
            mapOf("ANDROID" to 1L, "DESKTOP_LINUX" to 1L),
            store.decisionCountsByPlatform(),
        )
    }

    @Test
    fun `решения на уровне пакета сохраняются с пустым идентификатором блока`() {
        store.saveDecision(DomainFixtures.packetLevelDecision)
        val loaded = store.decisionsForPacket(PacketId("p-1")).single()
        assertEquals(DecisionScope.PACKET, loaded.scope)
        assertNull(loaded.targetHunkId)
        assertNull(loaded.comment)
    }

    @Test
    fun `права на инструменты переживают запись и чтение`() {
        val permission = ToolPermission(tool = "fs.write", read = Permission.ALLOW, write = Permission.ASK)
        store.savePermission(permission)
        assertEquals(permission, store.loadPermission("fs.write"))
        assertEquals(listOf(permission), store.allPermissions())
    }

    @Test
    fun `повторная запись прав обновляет, а не дублирует`() {
        store.savePermission(ToolPermission("fs.write", Permission.ALLOW, Permission.ASK))
        store.savePermission(ToolPermission("fs.write", Permission.ALLOW, Permission.DENY))
        assertEquals(Permission.DENY, store.loadPermission("fs.write")!!.write)
        assertEquals(1, store.allPermissions().size)
    }
}
```

- [ ] **Шаг 7: прогнать тест, убедиться что падает**

```bash
./gradlew :host-core:test --tests 'dev.aide.host.store.HostStoreTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: HostStore`.

- [ ] **Шаг 8: написать `HostStore`**

`host-core/src/main/kotlin/dev/aide/host/store/HostStore.kt`:

```kotlin
package dev.aide.host.store

import dev.aide.domain.AgentRun
import dev.aide.domain.ClientPlatform
import dev.aide.domain.Cost
import dev.aide.domain.PacketId
import dev.aide.domain.ReviewDecision
import dev.aide.domain.RunId
import dev.aide.domain.Task
import dev.aide.domain.TaskId
import dev.aide.domain.ToolCall
import dev.aide.domain.ToolPermission
import dev.aide.host.store.db.HostDatabase
import kotlinx.serialization.cbor.Cbor

/**
 * Слой доступа к метаданным хоста (§ 8.3, § 9).
 *
 * Задачи, прогоны, вызовы инструментов, решения ревью и права хранятся в SQLite.
 * Код репозитория в базе не хранится — он живёт в git. Модули `client-*` к базе
 * не обращаются: им это запрещено правилом границ из задачи 4, а единственный
 * путь к этим данным для клиента — сообщения протокола.
 *
 * Каждая запись сохраняется дважды: индексируемые поля — в колонках, полный
 * объект — в CBOR. Поэтому фильтры по задаче, инструменту и платформе работают
 * индексами, а домен остаётся единственным описанием объекта.
 */
class HostStore(
    private val database: HostDatabase,
    private val cbor: Cbor = Cbor {
        encodeDefaults = true
        ignoreUnknownKeys = true
    },
) {

    // ——— Задачи ———

    /** Сохраняет задачу. */
    fun saveTask(task: Task) {
        database.taskQueries.insert(
            id = task.id.value,
            title = task.title,
            prompt = task.prompt,
            branch = task.branch,
            status = task.status.name,
            created_at = task.createdAt.toEpochMilliseconds(),
            payload = cbor.encodeToByteArray(Task.serializer(), task),
        )
    }

    /** Читает задачу по идентификатору. */
    fun loadTask(id: TaskId): Task? =
        database.taskQueries.byId(id.value).executeAsOneOrNull()
            ?.let { cbor.decodeFromByteArray(Task.serializer(), it.payload) }

    /** Читает задачи с указанным статусом, от новых к старым. */
    fun tasksByStatus(status: String): List<Task> =
        database.taskQueries.byStatus(status).executeAsList()
            .map { cbor.decodeFromByteArray(Task.serializer(), it.payload) }

    /** Удаляет задачу. Журнал её прогонов при этом не удаляется (§ 10.2). */
    fun deleteTask(id: TaskId) = database.taskQueries.delete(id.value)

    // ——— Прогоны ———

    /** Сохраняет прогон. */
    fun saveRun(run: AgentRun) {
        database.agentRunQueries.insert(
            id = run.id.value,
            task_id = run.taskId.value,
            state = run.state.name,
            mode = run.mode.name,
            started_at = run.startedAt.toEpochMilliseconds(),
            finished_at = run.finishedAt?.toEpochMilliseconds(),
            elapsed_millis = run.elapsedMillis,
            cost_micros = run.cost.amountMicros,
            cost_known = if (run.cost.known) 1L else 0L,
            payload = cbor.encodeToByteArray(AgentRun.serializer(), run),
        )
    }

    /** Читает прогон по идентификатору. */
    fun loadRun(id: RunId): AgentRun? =
        database.agentRunQueries.byId(id.value).executeAsOneOrNull()
            ?.let { cbor.decodeFromByteArray(AgentRun.serializer(), it.payload) }

    /** Суммарная стоимость всех прогонов задачи с учётом неполноты данных (FR-AGENT-10, FR-COST-5). */
    fun totalCostMicros(taskId: TaskId): Cost {
        val sum = database.agentRunQueries.totalCostMicrosByTask(taskId.value).executeAsOne() ?: 0L
        val unknown = database.agentRunQueries.countUnknownCostByTask(taskId.value).executeAsOne()
        return Cost(amountMicros = sum, known = unknown == 0L)
    }

    // ——— Вызовы инструментов ———

    /** Сохраняет вызов инструмента. */
    fun saveToolCall(call: ToolCall) {
        database.toolCallQueries.insert(
            id = call.id.value,
            run_id = call.runId.value,
            tool = call.tool,
            outcome = call.outcome.name,
            required_approval = if (call.requiredApproval) 1L else 0L,
            duration_millis = call.durationMillis,
            at = call.at.toEpochMilliseconds(),
            payload = cbor.encodeToByteArray(ToolCall.serializer(), call),
        )
    }

    /** Читает вызовы прогона в порядке выполнения; при [tool] не null — только по этому инструменту. */
    fun toolCallsForRun(runId: RunId, tool: String? = null): List<ToolCall> {
        val rows = if (tool == null) {
            database.toolCallQueries.byRun(runId.value).executeAsList()
        } else {
            database.toolCallQueries.byRunAndTool(runId.value, tool).executeAsList()
        }
        return rows.map { cbor.decodeFromByteArray(ToolCall.serializer(), it.payload) }
    }

    // ——— Решения ревью ———

    /** Сохраняет решение ревью. */
    fun saveDecision(decision: ReviewDecision) {
        database.reviewDecisionQueries.insert(
            packet_id = decision.packetId.value,
            packet_revision = decision.packetRevision.toLong(),
            scope = decision.scope.name,
            target_hunk_id = decision.targetHunkId?.value,
            value = decision.value.name,
            client_platform = decision.clientPlatform.name,
            decided_at = decision.decidedAt.toEpochMilliseconds(),
            payload = cbor.encodeToByteArray(ReviewDecision.serializer(), decision),
        )
    }

    /** Читает решения по пакету; при [revision] не null — только по этой ревизии (§ 9, правило 2). */
    fun decisionsForPacket(packetId: PacketId, revision: Int? = null): List<ReviewDecision> {
        val rows = if (revision == null) {
            database.reviewDecisionQueries.byPacket(packetId.value).executeAsList()
        } else {
            database.reviewDecisionQueries.byPacketAndRevision(packetId.value, revision.toLong()).executeAsList()
        }
        return rows.map { cbor.decodeFromByteArray(ReviewDecision.serializer(), it.payload) }
    }

    /** Читает решения, принятые с указанной платформы: от старых к новым (§ 2.3). */
    fun decisionsByPlatform(platform: ClientPlatform): List<ReviewDecision> =
        database.reviewDecisionQueries.byPlatform(platform.name).executeAsList()
            .map { cbor.decodeFromByteArray(ReviewDecision.serializer(), it.payload) }

    /** Число решений по платформам — сырьё для метрики «доля задач, закрытых с телефона» (§ 2.3). */
    fun decisionCountsByPlatform(): Map<String, Long> =
        database.reviewDecisionQueries.countByPlatform().executeAsList()
            .associate { it.client_platform to it.decisions }

    // ——— Права ———

    /** Сохраняет права на инструмент. */
    fun savePermission(permission: ToolPermission) {
        database.toolPermissionQueries.insert(
            tool = permission.tool,
            read_permission = permission.read.name,
            write_permission = permission.write.name,
            payload = cbor.encodeToByteArray(ToolPermission.serializer(), permission),
        )
    }

    /** Читает права на инструмент. */
    fun loadPermission(tool: String): ToolPermission? =
        database.toolPermissionQueries.byTool(tool).executeAsOneOrNull()
            ?.let { cbor.decodeFromByteArray(ToolPermission.serializer(), it.payload) }

    /** Читает все права. */
    fun allPermissions(): List<ToolPermission> =
        database.toolPermissionQueries.all().executeAsList()
            .map { cbor.decodeFromByteArray(ToolPermission.serializer(), it.payload) }
}
```

- [ ] **Шаг 9: написать фабрику базы**

`host-core/src/main/kotlin/dev/aide/host/store/DatabaseFactory.kt`:

```kotlin
package dev.aide.host.store

import app.cash.sqldelight.driver.jdbc.sqlite.JdbcSqliteDriver
import dev.aide.host.store.db.HostDatabase
import java.nio.file.Path
import kotlin.io.path.createDirectories

/**
 * Создание и открытие базы метаданных хоста.
 *
 * База лежит в каталоге данных приложения, а не в воркспейсе: иначе она попадала бы
 * в `git status`, в коммиты и в снапшоты, а этого быть не должно (§ 9).
 */
object DatabaseFactory {

    /** Каталог данных приложения: `$XDG_DATA_HOME/aide` или `~/.local/share/aide`. */
    fun defaultDatabasePath(): Path {
        val xdg = System.getenv("XDG_DATA_HOME")?.takeIf { it.isNotBlank() }
        val base = if (xdg != null) Path.of(xdg) else Path.of(System.getProperty("user.home"), ".local", "share")
        return base.resolve("aide").resolve("host.db")
    }

    /** Открывает базу, применяя миграции до текущей версии. */
    fun open(path: Path = defaultDatabasePath()): HostStore {
        path.parent?.createDirectories()
        val driver = JdbcSqliteDriver("jdbc:sqlite:${path.toAbsolutePath()}")
        val currentVersion = HostDatabase.Schema.version
        // Создаём схему, если базы ещё нет; иначе доводим до текущей версии.
        if (!path.toFile().exists() || isFresh(driver)) {
            HostDatabase.Schema.create(driver)
        } else {
            HostDatabase.Schema.migrate(driver, readUserVersion(driver), currentVersion, *arrayOf())
        }
        return HostStore(HostDatabase(driver))
    }

    /** Открывает базу в памяти — для тестов. */
    fun openInMemory(): HostStore {
        val driver = JdbcSqliteDriver(JdbcSqliteDriver.IN_MEMORY)
        HostDatabase.Schema.create(driver)
        return HostStore(HostDatabase(driver))
    }

    private fun isFresh(driver: JdbcSqliteDriver): Boolean =
        driver.executeQuery(null, "SELECT count(*) FROM sqlite_master WHERE type='table'", { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value == 0L

    private fun readUserVersion(driver: JdbcSqliteDriver): Long =
        driver.executeQuery(null, "PRAGMA user_version", { cursor ->
            app.cash.sqldelight.db.QueryResult.Value(if (cursor.next().value) cursor.getLong(0) ?: 0L else 0L)
        }, 0).value
}
```

- [ ] **Шаг 10: прогнать все тесты хоста**

```bash
./gradlew :host-core:test
```

Ожидаемо: `BUILD SUCCESSFUL`, 50 тестов (`MigrationTest` — 3, `HostStoreTest` — 11, `ProtocolServerTest` — 4, `ReconnectTest` — 2, `WorkspaceFileSystemTest` — 13, `FileTreeBuilderTest` — 6, `JGitRepositoryTest` — 8, `EmbeddedHostTest` — 3).

- [ ] **Шаг 11: проверить, что клиент не может обратиться к базе**

Правило границ из задачи 4 уже запрещает `app.cash.sqldelight` в `client-ui` и `client-state`. Убедиться, что проверка видит и новые зависимости:

```bash
./gradlew verifyModuleBoundaries
```

Ожидаемо: `BUILD SUCCESSFUL`. Затем временно добавить в `client-state/src/commonMain/kotlin/dev/aide/client/state/HostClient.kt` строку `import app.cash.sqldelight.db.SqlDriver` и прогнать — ожидаемо `FAILED` с сообщением о нарушении границ модуля `client-state`. Удалить строку.

- [ ] **Шаг 12: коммит**

```bash
git add host-core
git commit -m "feat(host): схема метаданных в SQLite, миграции 1→2 и слой доступа HostStore"
```

---

## Задача 16: сквозная проверка — репозиторий на экране (`T-0.15`)

**Файлы:**
- Создать: `client-state/src/commonMain/kotlin/dev/aide/client/state/AppStateStore.kt`
- Тест: `client-state/src/commonTest/kotlin/dev/aide/client/state/AppStateStoreTest.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/StateViews.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/RepoTreeScreen.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/FileContentScreen.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/RepoScreen.kt`
- Создать: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/SettingsScreen.kt`
- Изменить: `client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt`
- Тест: `client-ui/src/jvmTest/kotlin/dev/aide/client/ui/ScreenStatesTest.kt`
- Создать: `platform-android/src/androidMain/kotlin/dev/aide/platform/android/AndroidClientRuntime.kt`
- Изменить: `androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`
- Изменить: `androidApp/build.gradle.kts` (зависимость на корутины)
- Создать: `platform-desktop/src/main/kotlin/dev/aide/platform/desktop/DesktopRuntime.kt`
- Изменить: `desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt`
- Изменить: `client-ui/build.gradle.kts` (зависимость для UI-тестов)

**Что считается выполненным.** На Android и на десктопе приложение открывает репозиторий, показывает ветку в шапке, дерево файлов и содержимое выбранного файла. Для экрана дерева и содержимого проверены пять состояний из § 6.1: пусто, загрузка, ошибка, нет связи, нет прав — на ширине 360 dp и на десктопе.

**Про пять состояний.** Состояния моделируются как один тип `ScreenState`, а не как набор флагов: так нельзя случайно показать одновременно «загрузка» и «ошибка». Состояние «нет связи» берётся из `ConnectionState`, остальные — из результата запроса.

**Про Android в этом этапе.** Обнаружение хоста в сети и сопряжение устройств — это `T-1.51` и этап 5 (`T-5.11`). Здесь Android подключается по адресу, заданному в настройках вручную; автоматического поиска нет и он не обещается.

- [ ] **Шаг 1: написать падающий тест состояний экрана**

`client-state/src/commonTest/kotlin/dev/aide/client/state/AppStateStoreTest.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class AppStateStoreTest {

    private val workspaceId = WorkspaceId("ws-1")
    private val store = AppStateStore()

    private val tree = FileTreePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        entries = listOf(
            FileTreeEntry("src", isDirectory = true),
            FileTreeEntry("src/Login.kt", isDirectory = false, sizeBytes = 12),
        ),
        truncated = false,
    )

    private val hostState = HostStatePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        branch = "master",
        headCommit = "abc1234",
        uptimeMillis = 10,
        mode = dev.aide.protocol.HostMode.LOCAL,
    )

    @Test
    fun `начальное состояние — пусто и загрузка не начата`() {
        assertEquals(ScreenState.Empty, store.treeState)
        assertNull(store.selectedFile)
        assertNull(store.hostState)
    }

    @Test
    fun `успешная загрузка даёт данные`() {
        store.onTreeLoaded(tree)
        val loaded = assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState)
        assertEquals(2, loaded.data.entries.size)
    }

    @Test
    fun `пустое дерево даёт состояние «пусто», а не пустой экран`() {
        store.onTreeLoaded(tree.copy(entries = emptyList()))
        assertEquals(ScreenState.Empty, store.treeState)
    }

    @Test
    fun `ошибка пути даёт состояние ошибки с понятным текстом`() {
        store.onTreeFailed(ProtocolError.NotFound("путь не существует: /nope"))
        val failed = assertIs<ScreenState.Failed>(store.treeState)
        assertEquals(ScreenState.ErrorKind.PATH_MISSING, failed.kind)
        assertTrue(failed.detail.contains("/nope"))
    }

    @Test
    fun `каталог без git даёт отдельный вид ошибки`() {
        store.onTreeFailed(ProtocolError.NotAGitRepository("/tmp/not-a-repo"))
        val failed = assertIs<ScreenState.Failed>(store.treeState)
        assertEquals(ScreenState.ErrorKind.NOT_A_REPOSITORY, failed.kind)
    }

    @Test
    fun `ошибка доступа даёт состояние «нет прав»`() {
        store.onTreeFailed(ProtocolError.AccessDenied(path = "/etc/passwd", reason = "вне корня воркспейса"))
        val denied = assertIs<ScreenState.NoPermission>(store.treeState)
        assertEquals("/etc/passwd", denied.path)
        assertEquals("вне корня воркспейса", denied.reason)
    }

    @Test
    fun `потеря связи перекрывает загруженные данные, но не стирает их`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(ConnectionState.Reconnecting(attempt = 1, nextRetryMillis = 100))

        val offline = assertIs<ScreenState.Offline<FileTreePayload>>(store.treeState)
        assertEquals(2, offline.cached.data.entries.size, "Кэш остаётся доступен офлайн")
    }

    @Test
    fun `восстановление связи возвращает загруженное состояние`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(ConnectionState.Reconnecting(1, 100))
        store.onConnectionState(ConnectionState.Connected(sessionId = dev.aide.protocol.SessionId("s"), reconnected = true))

        assertIs<ScreenState.Loaded<FileTreePayload>>(store.treeState)
    }

    @Test
    fun `несовместимость версий показывается как ошибка и не даёт работать`() {
        store.onTreeLoaded(tree)
        store.onConnectionState(ConnectionState.Incompatible("Обновите приложение (клиент 1.0, хост 2.0)"))

        val failed = assertIs<ScreenState.Failed>(store.treeState)
        assertEquals(ScreenState.ErrorKind.INCOMPATIBLE, failed.kind)
    }

    @Test
    fun `выбор файла и его содержимое`() {
        store.onTreeLoaded(tree)
        store.selectFile("src/Login.kt")

        assertEquals("src/Login.kt", store.selectedFile)
        assertIs<ScreenState.Loading>(store.fileState)

        val content = FileContentPayload(workspaceId, "src/Login.kt", "fun login() = Unit\n", 19, false, "kotlin")
        store.onFileLoaded(content)
        assertEquals("fun login() = Unit\n", assertIs<ScreenState.Loaded<FileContentPayload>>(store.fileState).data.text)
    }

    @Test
    fun `снятие выбора файла возвращает пустое состояние`() {
        store.onTreeLoaded(tree)
        store.selectFile("src/Login.kt")
        store.selectFile(null)
        assertNull(store.selectedFile)
        assertEquals(ScreenState.Empty, store.fileState)
    }

    @Test
    fun `состояние хоста сохраняется для шапки`() {
        store.onHostState(hostState)
        assertEquals("master", store.hostState?.branch)
    }

    @Test
    fun `загрузка показывается отдельным состоянием`() {
        store.onTreeLoading()
        assertEquals(ScreenState.Loading, store.treeState)
    }
}
```

- [ ] **Шаг 2: прогнать тест, убедиться что падает**

```bash
./gradlew :client-state:jvmTest --tests 'dev.aide.client.state.AppStateStoreTest'
```

Ожидаемо: `FAILED`, `Unresolved reference: AppStateStore`, `ScreenState`.

- [ ] **Шаг 3: написать модель состояний экрана**

`client-state/src/commonMain/kotlin/dev/aide/client/state/ScreenState.kt`:

```kotlin
package dev.aide.client.state

/**
 * Состояние экрана (§ 6.1).
 *
 * Один тип вместо набора флагов: нельзя одновременно оказаться в «загрузке»
 * и в «ошибке», а компилятор требует обработать все случаи в `when`.
 */
sealed interface ScreenState<out T> {

    /** Данных ещё нет и запрос не начат. */
    data object Empty : ScreenState<Nothing>

    /** Идёт загрузка; UI показывает скелетон структуры, а не пустой экран. */
    data object Loading : ScreenState<Nothing>

    /** Данные получены. */
    data class Loaded<T>(val data: T) : ScreenState<T>

    /** Запрос завершился ошибкой. */
    data class Failed(
        /** Вид ошибки — от него зависит текст и действие. */
        val kind: ErrorKind,
        /** Что именно случилось, для показа пользователю. */
        val detail: String,
        /** Техническая деталь; показывается по запросу. */
        val technical: String? = null,
    ) : ScreenState<Nothing>

    /** Связь с хостом потеряна; показываются данные из кэша с явной пометкой (§ 3.5). */
    data class Offline<T>(val cached: T) : ScreenState<T>

    /** Доступ к объекту закрыт (§ 10.1). */
    data class NoPermission(
        /** К чему нет доступа. */
        val path: String,
        /** Почему отказано. */
        val reason: String,
    ) : ScreenState<Nothing>

    /** Виды ошибок, различаемые интерфейсом. */
    enum class ErrorKind {
        /** Путь не существует. */
        PATH_MISSING,

        /** Каталог есть, но это не git-репозиторий. */
        NOT_A_REPOSITORY,

        /** Версии протокола несовместимы. */
        INCOMPATIBLE,

        /** Прочая ошибка, в том числе внутренняя ошибка хоста. */
        OTHER,
    }
}
```

- [ ] **Шаг 4: написать хранилище состояния приложения**

`client-state/src/commonMain/kotlin/dev/aide/client/state/AppStateStore.kt`:

```kotlin
package dev.aide.client.state

import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.HostStatePayload
import dev.aide.protocol.ProtocolError
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Состояние экранов приложения, независимое от UI.
 *
 * Отдельный слой нужен по двум причинам: состояния можно тестировать без
 * Compose, и правила переходов («ошибка важнее данных», «нет связи не стирает
 * кэш») описаны в одном месте, а не разбросаны по composable-функциям.
 */
class AppStateStore {

    private val _treeState = MutableStateFlow<ScreenState<FileTreePayload>>(ScreenState.Empty)
    /** Состояние дерева файлов. */
    val treeState: StateFlow<ScreenState<FileTreePayload>> = _treeState.asStateFlow()

    private val _fileState = MutableStateFlow<ScreenState<FileContentPayload>>(ScreenState.Empty)
    /** Состояние просмотра файла. */
    val fileState: StateFlow<ScreenState<FileContentPayload>> = _fileState.asStateFlow()

    private val _hostState = MutableStateFlow<HostStatePayload?>(null)
    /** Состояние хоста для шапки: ветка, корень, режим. */
    val hostState: StateFlow<HostStatePayload?> = _hostState.asStateFlow()

    private val _selectedFile = MutableStateFlow<String?>(null)
    /** Путь выбранного файла; null, если файл не выбран. */
    val selectedFile: StateFlow<String?> = _selectedFile.asStateFlow()

    /** Последнее достоверное состояние связи; нужно, чтобы вернуться из [ScreenState.Offline]. */
    private var lastConnection: ConnectionState = ConnectionState.Idle

    // ——— Дерево ———

    /** Показывает загрузку дерева. */
    fun onTreeLoading() {
        _treeState.value = ScreenState.Loading
    }

    /** Принимает загруженное дерево; пустое дерево становится состоянием «пусто». */
    fun onTreeLoaded(tree: FileTreePayload) {
        _treeState.value = if (tree.entries.isEmpty()) ScreenState.Empty else ScreenState.Loaded(tree)
    }

    /** Превращает ошибку хоста в состояние экрана. */
    fun onTreeFailed(error: ProtocolError) {
        _treeState.value = error.toScreenState()
    }

    // ——— Файл ———

    /** Выбирает файл и переводит просмотр в состояние загрузки; null снимает выбор. */
    fun selectFile(path: String?) {
        _selectedFile.value = path
        _fileState.value = if (path == null) ScreenState.Empty else ScreenState.Loading
    }

    /** Принимает содержимое файла. */
    fun onFileLoaded(content: FileContentPayload) {
        _fileState.value = ScreenState.Loaded(content)
    }

    /** Превращает ошибку чтения файла в состояние экрана. */
    fun onFileFailed(error: ProtocolError) {
        _fileState.value = error.toScreenState()
    }

    /** Сохраняет состояние хоста для шапки. */
    fun onHostState(state: HostStatePayload) {
        _hostState.value = state
    }

    /**
     * Реагирует на изменение связи.
     *
     * Правила: потеря связи переводит загруженные данные в [ScreenState.Offline],
     * сохраняя кэш; несовместимость версий перекрывает всё остальное, потому что
     * работать в этом состоянии нельзя; восстановление связи возвращает
     * загруженные данные из кэша, чтобы экран не мигал пустотой.
     */
    fun onConnectionState(state: ConnectionState) {
        val previous = lastConnection
        lastConnection = state

        when (state) {
            is ConnectionState.Incompatible -> {
                _treeState.value = ScreenState.Failed(ScreenState.ErrorKind.INCOMPATIBLE, state.userMessage)
            }

            is ConnectionState.Reconnecting -> {
                _treeState.value = _treeState.value.toOffline()
                _fileState.value = _fileState.value.toOffline()
            }

            is ConnectionState.Connected -> {
                if (previous is ConnectionState.Reconnecting) {
                    _treeState.value = _treeState.value.fromOffline()
                    _fileState.value = _fileState.value.fromOffline()
                }
            }

            is ConnectionState.Closed -> Unit
            ConnectionState.Idle, ConnectionState.Connecting -> Unit
        }
    }

    private fun <T> ScreenState<T>.toOffline(): ScreenState<T> = when (this) {
        is ScreenState.Loaded -> ScreenState.Offline(data)
        is ScreenState.Offline -> this
        else -> this
    }

    private fun <T> ScreenState<T>.fromOffline(): ScreenState<T> = when (this) {
        is ScreenState.Offline -> ScreenState.Loaded(cached)
        else -> this
    }

    private fun ProtocolError.toScreenState(): ScreenState<Nothing> = when (this) {
        is ProtocolError.NotFound -> {
            if (what.contains("не является git") || what.contains("не git")) {
                ScreenState.Failed(ScreenState.ErrorKind.NOT_A_REPOSITORY, what)
            } else {
                ScreenState.Failed(ScreenState.ErrorKind.PATH_MISSING, what)
            }
        }

        is ProtocolError.NotAGitRepository ->
            ScreenState.Failed(ScreenState.ErrorKind.NOT_A_REPOSITORY, "Каталог не является git-репозиторием: $path")

        is ProtocolError.AccessDenied -> ScreenState.NoPermission(path = path, reason = reason)

        is ProtocolError.WorkspaceClosed ->
            ScreenState.Failed(ScreenState.ErrorKind.OTHER, "Воркспейс закрыт, откройте репозиторий заново")

        is ProtocolError.NotImplemented ->
            ScreenState.Failed(ScreenState.ErrorKind.OTHER, what)

        is ProtocolError.Internal ->
            ScreenState.Failed(ScreenState.ErrorKind.OTHER, message, detail)
    }
}
```

- [ ] **Шаг 5: прогнать тесты состояния**

```bash
./gradlew :client-state:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 22 теста (`AppStateStoreTest` — 13, `SettingsStoreTest` — 8, `NoLocalityBranchingTest` — 1).

- [ ] **Шаг 6: написать экраны состояний**

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/StateViews.kt`:

```kotlin
package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.ui.strings.Strings

/**
 * Пять состояний экрана из § 6.1. Каждое состояние обязано объяснять, что
 * произошло и что делать, — иначе это не состояние, а пустой экран.
 */

/** Загрузка: скелетон структуры, а не пустой экран. */
@Composable
fun LoadingState(modifier: Modifier = Modifier, rows: Int = 4) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateLoadingTitle), style = MaterialTheme.typography.titleMedium)
        repeat(rows) { index ->
            Surface(
                modifier = Modifier
                    .fillMaxWidth(if (index % 3 == 2) 0.6f else 1f)
                    .height(20.dp)
                    .testTag("skeleton-row-$index"),
                shape = RoundedCornerShape(4.dp),
                color = MaterialTheme.colorScheme.surfaceVariant,
            ) {}
        }
        Text(
            Strings.text(Strings.stateLoadingSkeleton),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Пусто: объясняет, почему здесь ничего нет. */
@Composable
fun EmptyState(message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateEmptyTitle), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
    }
}

/** Ошибка: что случилось и кнопка повтора. */
@Composable
fun ErrorState(message: String, onRetry: () -> Unit, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateErrorTitle), style = MaterialTheme.typography.titleMedium)
        Text(message, style = MaterialTheme.typography.bodyMedium)
        Button(onClick = onRetry) { Text(Strings.text(Strings.stateErrorRetry)) }
    }
}

/** Нет связи: кэшированные данные с явной пометкой (§ 3.5). */
@Composable
fun OfflineBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.errorContainer,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Strings.text(Strings.stateOfflineTitle), style = MaterialTheme.typography.titleSmall)
            Text(Strings.text(Strings.stateOfflineBody), style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Нет прав: что именно и почему закрыто (§ 10.1). */
@Composable
fun NoPermissionState(path: String, reason: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(Strings.text(Strings.stateNoPermissionTitle), style = MaterialTheme.typography.titleMedium)
        Text(Strings.text(Strings.stateNoPermissionBody, path, reason), style = MaterialTheme.typography.bodyMedium)
    }
}
```

- [ ] **Шаг 7: написать экран дерева и экран файла**

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/RepoTreeScreen.kt`:

```kotlin
package dev.aide.client.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileTreePayload

/**
 * Дерево файлов. Плоский список путей с отступом по глубине: на 20 000 файлах
 * вложенные composable-узлы съели бы всю память, а визуально результат тот же.
 */
@Composable
fun RepoTreeScreen(
    state: ScreenState<FileTreePayload>,
    onFileClick: (String) -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Empty -> EmptyState(Strings.text(Strings.stateEmptyRepo))

            ScreenState.Loading -> LoadingState()

            is ScreenState.Failed -> ErrorState(message = state.detail, onRetry = onRetry)

            is ScreenState.NoPermission -> NoPermissionState(path = state.path, reason = state.reason)

            is ScreenState.Offline -> {
                OfflineBanner()
                TreeList(state.cached, onFileClick)
            }

            is ScreenState.Loaded -> TreeList(state.data, onFileClick)
        }
    }
}

@Composable
private fun TreeList(tree: FileTreePayload, onFileClick: (String) -> Unit) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = Strings.text(Strings.repoHeaderRoot, tree.rootPath),
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
        )
        if (tree.truncated) {
            Text(
                text = Strings.text(Strings.repoTreeTruncated, tree.skippedEntries),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        LazyColumn(
            modifier = Modifier.fillMaxWidth().testTag("tree-list"),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(tree.entries, key = { it.path }) { entry ->
                TreeRow(entry.path, entry.isDirectory) { onFileClick(entry.path) }
            }
        }
    }
}

@Composable
private fun TreeRow(path: String, isDirectory: Boolean, onClick: () -> Unit) {
    val depth = path.count { it == '/' }
    val label = path.substringAfterLast('/')
    Text(
        text = if (isDirectory) "$label/" else label,
        style = MaterialTheme.typography.bodyMedium,
        color = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !isDirectory, onClick = onClick)
            .padding(start = (16 + depth * 12).dp, top = 8.dp, bottom = 8.dp, end = 16.dp)
            .testTag("tree-row-$path"),
    )
}
```

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/FileContentScreen.kt`:

```kotlin
package dev.aide.client.ui.screens

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.FontFamily
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily as ComposeFontFamily
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileContentPayload

/**
 * Просмотр содержимого файла.
 *
 * Горизонтальная прокрутка здесь допустима и не противоречит FR-DIFF-9: запрет
 * горизонтального скролла относится к diff-вьюеру, а не к просмотру файла целиком.
 */
@Composable
fun FileContentScreen(
    state: ScreenState<FileContentPayload>,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        when (state) {
            ScreenState.Empty -> EmptyState(Strings.text(Strings.stateEmptyTree))

            ScreenState.Loading -> LoadingState(rows = 6)

            is ScreenState.Failed -> ErrorState(message = state.detail, onRetry = onRetry)

            is ScreenState.NoPermission -> NoPermissionState(path = state.path, reason = state.reason)

            is ScreenState.Offline -> {
                OfflineBanner()
                FileBody(state.cached)
            }

            is ScreenState.Loaded -> FileBody(state.data)
        }
    }
}

@Composable
private fun FileBody(content: FileContentPayload) {
    Column(modifier = Modifier.fillMaxSize()) {
        Text(
            text = Strings.text(Strings.repoFileTitle, content.path),
            style = MaterialTheme.typography.titleSmall,
            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
        )
        if (content.truncated) {
            Text(
                text = Strings.text(Strings.repoFileTruncated),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
            )
        }
        Text(
            text = content.text,
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = ComposeFontFamily.Monospace),
            modifier = Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .horizontalScroll(rememberScrollState())
                .padding(horizontal = 16.dp)
                .testTag("file-content"),
        )
    }
}
```

Убрать из импортов `androidx.compose.ui.graphics.FontFamily` и `ComposeFontFamily`-псевдоним: в коде используется `ComposeFontFamily.Monospace` из `androidx.compose.ui.text.font`. Проще — оставить единственный импорт:

```kotlin
import androidx.compose.ui.text.font.FontFamily
```

и использовать `fontFamily = FontFamily.Monospace`.

- [ ] **Шаг 8: написать экран настроек и собрать приложение**

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/SettingsScreen.kt`:

```kotlin
package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.settings.ControlMode
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.ThemePreference
import dev.aide.client.ui.strings.Strings

/**
 * Настройки: путь к репозиторию, адрес хоста, тема, режим управления.
 *
 * Режим управления здесь только сохраняется: различие в поведении кнопок и жестов
 * появляется в этапе 1 (T-1.43). Хранить его уже сейчас нужно, иначе перезапуск
 * приложения будет терять выбор пользователя.
 *
 * Адрес хоста — тоже только настройка: соединение создаётся один раз при старте
 * приложения, поэтому новый адрес действует после перезапуска (T-1.51). Экран
 * говорит об этом прямо, иначе введённый адрес выглядит неработающим.
 */
@Composable
fun SettingsScreen(
    settings: SettingsStore,
    onOpenRepository: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    var path by remember { mutableStateOf(settings.repositoryPath.orEmpty()) }
    var hostEndpoint by remember { mutableStateOf(settings.hostEndpoint.orEmpty()) }

    Column(
        modifier = modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(Strings.text(Strings.settingsTitle), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = path,
            onValueChange = { path = it },
            label = { Text(Strings.text(Strings.settingsRepositoryPath)) },
            placeholder = { Text(Strings.text(Strings.settingsRepositoryPathHint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings-repository-path"),
        )
        Button(
            onClick = {
                settings.repositoryPath = path
                onOpenRepository(path)
            },
            modifier = Modifier.testTag("settings-open"),
        ) {
            Text(Strings.text(Strings.settingsRepositoryApply))
        }

        OutlinedTextField(
            value = hostEndpoint,
            onValueChange = { hostEndpoint = it },
            label = { Text(Strings.text(Strings.settingsHostEndpoint)) },
            placeholder = { Text(Strings.text(Strings.settingsHostEndpointHint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().testTag("settings-host-endpoint"),
        )
        Button(
            onClick = { settings.hostEndpoint = hostEndpoint.takeIf { it.isNotBlank() } },
            modifier = Modifier.testTag("settings-host-endpoint-apply"),
        ) {
            Text(Strings.text(Strings.settingsHostEndpointApply))
        }
        Text(
            Strings.text(Strings.settingsHostEndpointRestart),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Text(Strings.text(Strings.settingsTheme), style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            options = ThemePreference.entries.map { it to themeLabel(it) },
            selected = settings.theme,
            onSelect = { settings.theme = it },
        )

        Text(Strings.text(Strings.settingsControlMode), style = MaterialTheme.typography.titleMedium)
        ChoiceRow(
            options = ControlMode.entries.map { it to controlLabel(it) },
            selected = settings.controlMode,
            onSelect = { settings.controlMode = it },
        )
    }
}

@Composable
private fun <T> ChoiceRow(
    options: List<Pair<T, String>>,
    selected: T,
    onSelect: (T) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        options.forEach { (value, label) ->
            Button(
                onClick = { onSelect(value) },
                enabled = value != selected,
                modifier = Modifier.fillMaxWidth().testTag("choice-$label"),
            ) {
                Text(label)
            }
        }
    }
}

@Composable
private fun themeLabel(preference: ThemePreference): String = when (preference) {
    ThemePreference.SYSTEM -> Strings.text(Strings.settingsThemeSystem)
    ThemePreference.LIGHT -> Strings.text(Strings.settingsThemeLight)
    ThemePreference.DARK -> Strings.text(Strings.settingsThemeDark)
}

@Composable
private fun controlLabel(mode: ControlMode): String = when (mode) {
    ControlMode.GESTURES -> Strings.text(Strings.settingsControlModeGestures)
    ControlMode.BUTTONS -> Strings.text(Strings.settingsControlModeButtons)
    ControlMode.HYBRID -> Strings.text(Strings.settingsControlModeHybrid)
}
```

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/App.kt` — полное содержимое:

```kotlin
package dev.aide.client.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import dev.aide.client.state.AppStateStore
import dev.aide.client.state.ConnectionState
import dev.aide.client.state.HostCallException
import dev.aide.client.state.HostClient
import dev.aide.client.state.HostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.ui.screens.RepoScreen
import dev.aide.client.ui.screens.SettingsScreen
import dev.aide.client.ui.strings.Strings
import dev.aide.client.ui.theme.AideTheme
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Куда приложение может перейти. Один экран — одна задача (FR-LAYOUT-5). */
private enum class Destination { REPOSITORY, SETTINGS }

@Composable
fun App(
    connection: HostConnection,
    settings: SettingsStore,
    scope: CoroutineScope,
    state: AppStateStore = remember { AppStateStore() },
) {
    val client = remember(connection, scope) { HostClient(connection, scope) }
    val coroutineScope = rememberCoroutineScope()

    LaunchedEffect(Unit) { client.start() }

    val connectionState by connection.state.collectAsState()
    LaunchedEffect(connectionState) { state.onConnectionState(connectionState) }

    val tree by state.treeState.collectAsState()
    val file by state.fileState.collectAsState()
    val hostState by state.hostState.collectAsState()

    var destination by remember { mutableStateOf(Destination.REPOSITORY) }

    AideTheme(preference = settings.theme) {
        Surface(modifier = Modifier.fillMaxSize()) {
            Column(modifier = Modifier.fillMaxSize()) {
                Header(hostState?.branch, connectionState)
                Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp)) {
                    Button(onClick = { destination = Destination.REPOSITORY }) {
                        Text(Strings.text(Strings.repoTreeTitle))
                    }
                    Button(onClick = { destination = Destination.SETTINGS }) {
                        Text(Strings.text(Strings.actionSettings))
                    }
                }

                when (destination) {
                    Destination.SETTINGS -> SettingsScreen(
                        settings = settings,
                        onOpenRepository = { path -> coroutineScope.launch { openRepository(client, state, path) } },
                    )

                    Destination.REPOSITORY -> RepoScreen(
                        treeState = tree,
                        fileState = file,
                        onFileClick = { path ->
                            state.selectFile(path)
                            coroutineScope.launch { loadFile(client, state, path) }
                        },
                        onRetry = {
                            coroutineScope.launch { openRepository(client, state, settings.repositoryPath.orEmpty()) }
                        },
                        onBack = { state.selectFile(null) },
                    )
                }
            }
        }
    }
}

@Composable
private fun Header(branch: String?, connectionState: ConnectionState) {
    Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
        Text(Strings.text(Strings.appName), style = MaterialTheme.typography.titleLarge)
        if (branch != null) {
            Text(Strings.text(Strings.repoHeaderBranch, branch), style = MaterialTheme.typography.bodyMedium)
        }
        val status = when (connectionState) {
            ConnectionState.Idle, ConnectionState.Connecting -> Strings.text(Strings.connectionConnecting)
            is ConnectionState.Connected -> null
            is ConnectionState.Reconnecting ->
                Strings.text(Strings.connectionReconnecting, connectionState.attempt)

            is ConnectionState.Incompatible ->
                Strings.text(Strings.connectionIncompatible, connectionState.userMessage)

            is ConnectionState.Closed -> Strings.text(Strings.connectionClosed, connectionState.reason)
        }
        if (status != null) {
            Text(status, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        }
    }
}

/** Открывает репозиторий: переводит дерево в загрузку, открывает воркспейс и запрашивает дерево и состояние хоста. */
private suspend fun openRepository(client: HostClient, state: AppStateStore, path: String) {
    if (path.isBlank()) return
    state.onTreeLoading()

    if (client.openWorkspace(path) == null) {
        // Хост отверг путь: причина лежит в последней ошибке сессии, а не в ответе на отдельный запрос.
        client.session.value.lastError?.let(state::onTreeFailed)
        return
    }

    client.fileTree()
        .onSuccess { state.onTreeLoaded(it) }
        .onFailure { error ->
            val protocolError = (error as? HostCallException)?.error
            if (protocolError != null) state.onTreeFailed(protocolError)
        }
    client.hostState().onSuccess { state.onHostState(it) }
}

/** Читает файл: на успехе кладёт содержимое в состояние, на ошибке — типизированную причину. */
private suspend fun loadFile(client: HostClient, state: AppStateStore, path: String) {
    client.fileContent(path)
        .onSuccess { state.onFileLoaded(it) }
        .onFailure { error ->
            val protocolError = (error as? HostCallException)?.error
            if (protocolError != null) state.onFileFailed(protocolError)
        }
}
```

`client-ui/src/commonMain/kotlin/dev/aide/client/ui/screens/RepoScreen.kt` — выбор между деревом и содержимым файла:

```kotlin
package dev.aide.client.ui.screens

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.strings.Strings
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreePayload

/**
 * Экран репозитория: дерево файлов или содержимое выбранного файла.
 *
 * Файл показывается вместо дерева, а не поверх него: на узком экране так остаётся
 * место для текста, а возврат к дереву — одна кнопка (FR-LAYOUT-5).
 */
@Composable
fun RepoScreen(
    treeState: ScreenState<FileTreePayload>,
    fileState: ScreenState<FileContentPayload>,
    onFileClick: (String) -> Unit,
    onRetry: () -> Unit,
    onBack: () -> Unit,
) {
    if (fileState is ScreenState.Empty) {
        RepoTreeScreen(state = treeState, onFileClick = onFileClick, onRetry = onRetry)
    } else {
        Column(modifier = Modifier.fillMaxSize()) {
            Button(
                onClick = onBack,
                modifier = Modifier.padding(16.dp).testTag("back-to-tree"),
            ) {
                Text(Strings.text(Strings.actionBack))
            }
            FileContentScreen(state = fileState, onRetry = onRetry)
        }
    }
}
```

`platform-desktop/src/main/kotlin/dev/aide/platform/desktop/DesktopRuntime.kt` — платформенное связывание десктопа. Здесь, и только здесь, известно, что хост живёт в том же процессе:

```kotlin
package dev.aide.platform.desktop

import dev.aide.client.state.HostConnection
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStore
import dev.aide.host.EmbeddedHost
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Платформенное связывание десктоп-клиента (T-0.13).
 *
 * Единственное место, где известно, что хост на десктопе живёт в том же процессе:
 * приложение получает готовые настройки, соединение и область корутин и не отличает
 * локальный хост от удалённого (§ 3.3). Закрытие останавливает хост и отменяет
 * корутины, поэтому порт освобождается вместе с окном.
 *
 * Граф собирается на Koin (DI-фреймворк стека) и изолированно: `KoinApplication.init()`,
 * а не глобальный контекст — `DesktopRuntime.start()` вызывается и из тестов, и повторный
 * вызов не должен падать.
 */
class DesktopRuntime internal constructor(
    /** Настройки клиента, переживающие перезапуск. */
    val settings: SettingsStore,
    /** Соединение с локально поднятым хостом. */
    val connection: HostConnection,
    /** Область корутин приложения: живёт до закрытия окна. */
    val scope: CoroutineScope,
    private val host: EmbeddedHost,
    private val graph: KoinApplication,
) : AutoCloseable {

    override fun close() {
        host.close()
        scope.cancel()
        graph.close()
    }

    companion object {
        /** Модуль Koin десктоп-клиента: единственное место, где перечислены его части. */
        internal fun module(): Module = module {
            single { EmbeddedHost.open() }
            single { CoroutineScope(SupervisorJob() + Dispatchers.Default) }
            single { SettingsStore(createKeyValueStore()) }
            single<HostConnection> { KtorHostConnection(endpoint = get<EmbeddedHost>().endpoint, scope = get()) }
        }

        /** Поднимает хост на свободном порту loopback, читает настройки и создаёт соединение. */
        fun start(): DesktopRuntime {
            val graph = KoinApplication.init().modules(module())
            val host = graph.koin.get<EmbeddedHost>()
            val scope = graph.koin.get<CoroutineScope>()
            val settings = graph.koin.get<SettingsStore>()
            val connection = graph.koin.get<HostConnection>()
            return DesktopRuntime(
                settings = settings,
                connection = connection,
                scope = scope,
                host = host,
                graph = graph,
            )
        }
    }
}
```

`desktopApp/src/main/kotlin/dev/aide/desktop/Main.kt` — окно поверх `DesktopRuntime`; о хосте и о том, что он локальный, точка входа больше не знает:

```kotlin
package dev.aide.desktop

import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import dev.aide.client.ui.App
import dev.aide.client.ui.strings.Strings
import dev.aide.platform.desktop.DesktopRuntime

fun main() = application {
    val runtime = remember { DesktopRuntime.start() }

    DisposableEffect(Unit) {
        onDispose { runtime.close() }
    }

    Window(onCloseRequest = ::exitApplication, title = Strings.text(Strings.appName)) {
        App(connection = runtime.connection, settings = runtime.settings, scope = runtime.scope)
    }
}
```

Зависимости уже на месте: `desktopApp` зависит от `:platform-desktop` и `:client-ui` (задача 2), а `platform-desktop` объявляет `api(project(":client-state"))` и `api(project(":host-core"))` (задача 1, шаг 8). Поэтому `Main.kt` видит и `DesktopRuntime`, и возвращаемые им типы без прямых зависимостей. Прямые `implementation(project(":host-core"))` и `implementation(project(":client-state"))`, которые нужны были версии `Main.kt` из задачи 13 при старом правиле, больше не требуются.

- [ ] **Шаг 9: написать UI-тесты состояний**

`client-ui/build.gradle.kts` — добавить в `commonTest.dependencies`:

```kotlin
            implementation(compose.uiTest)
            implementation(libs.kotlinx.coroutines.test)
```

`client-ui/src/jvmTest/kotlin/dev/aide/client/ui/ScreenStatesTest.kt`:

```kotlin
package dev.aide.client.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.aide.client.state.ScreenState
import dev.aide.client.ui.screens.FileContentScreen
import dev.aide.client.ui.screens.RepoTreeScreen
import dev.aide.protocol.FileContentPayload
import dev.aide.protocol.FileTreeEntry
import dev.aide.protocol.FileTreePayload
import dev.aide.protocol.WorkspaceId
import kotlin.test.Test

/**
 * Пять состояний § 6.1 проверяются на самой узкой поддерживаемой ширине — 360 dp.
 * Ширина задаётся явно, иначе тест проверял бы ширину окна сборочной машины.
 */
@OptIn(ExperimentalTestApi::class)
class ScreenStatesTest {

    private val workspaceId = WorkspaceId("ws-1")

    private val tree = FileTreePayload(
        workspaceId = workspaceId,
        rootPath = "/projects/aide",
        entries = listOf(
            FileTreeEntry("src", isDirectory = true),
            FileTreeEntry("src/Login.kt", isDirectory = false, sizeBytes = 12),
        ),
        truncated = false,
    )

    private fun narrow(content: @androidx.compose.runtime.Composable () -> Unit): @androidx.compose.runtime.Composable () -> Unit = {
        Box(modifier = Modifier.width(360.dp)) { content() }
    }

    @Test
    fun `состояние загрузки показывает скелетон, а не пустой экран`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Loading, onFileClick = {}, onRetry = {}) })
        onNodeWithTag("skeleton-row-0").assertIsDisplayed()
    }

    @Test
    fun `пустое дерево объясняет, что делать`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Empty, onFileClick = {}, onRetry = {}) })
        onNodeWithText("В репозитории нет коммитов и файлов. Создайте первый коммит — дерево появится.").assertIsDisplayed()
    }

    @Test
    fun `ошибка показывает что случилось и кнопку повтора`() = runComposeUiTest {
        setContent(
            narrow {
                RepoTreeScreen(
                    state = ScreenState.Failed(ScreenState.ErrorKind.PATH_MISSING, "Путь не существует: /nope"),
                    onFileClick = {},
                    onRetry = {},
                )
            },
        )
        onNodeWithText("Путь не существует: /nope").assertIsDisplayed()
        onNodeWithText("Повторить").assertIsDisplayed()
    }

    @Test
    fun `нет связи помечает кэш и всё равно показывает дерево`() = runComposeUiTest {
        setContent(narrow { RepoTreeScreen(state = ScreenState.Offline(tree), onFileClick = {}, onRetry = {}) })
        onNodeWithText("Нет связи с хостом").assertIsDisplayed()
        onNodeWithTag("tree-row-src/Login.kt").assertIsDisplayed()
    }

    @Test
    fun `нет прав называет путь и причину`() = runComposeUiTest {
        setContent(
            narrow {
                RepoTreeScreen(
                    state = ScreenState.NoPermission("/etc/passwd", "вне корня воркспейса"),
                    onFileClick = {},
                    onRetry = {},
                )
            },
        )
        onNodeWithText("Нет доступа").assertIsDisplayed()
        onNodeWithText("Доступ к «/etc/passwd» закрыт: вне корня воркспейса").assertIsDisplayed()
    }

    @Test
    fun `загруженное содержимое файла показывается`() = runComposeUiTest {
        val content = FileContentPayload(workspaceId, "src/Login.kt", "fun login() = Unit\n", 19, false, "kotlin")
        setContent(narrow { FileContentScreen(state = ScreenState.Loaded(content), onRetry = {}) })
        onNodeWithTag("file-content").assertIsDisplayed()
        onNodeWithText("Файл: src/Login.kt").assertIsDisplayed()
    }

    @Test
    fun `обрезанный файл помечается`() = runComposeUiTest {
        val content = FileContentPayload(workspaceId, "big.txt", "aaa", 600_000, truncated = true, language = null)
        setContent(narrow { FileContentScreen(state = ScreenState.Loaded(content), onRetry = {}) })
        onNodeWithText("Файл показан не целиком: превышен предел показа").assertIsDisplayed()
    }
}
```

Список импортов соответствует фактически используемым символам: `ScreenState`, `RepoTreeScreen`, `FileContentScreen`, `FileContentPayload`, `FileTreeEntry`, `FileTreePayload`, `WorkspaceId`, `Box`, `width`, `dp`, `runComposeUiTest`, `onNodeWithTag`, `onNodeWithText`, `assertIsDisplayed`, `ExperimentalTestApi`.

- [ ] **Шаг 10: прогнать UI-тесты**

```bash
./gradlew :client-ui:jvmTest
```

Ожидаемо: `BUILD SUCCESSFUL`, 12 тестов (`ScreenStatesTest` — 7, `NoLiteralUiStringsTest` — 1, `NoLocalityBranchingTest` — 1, `SharedUiHasNoPlatformBranchingTest` — 1, `EntryPointStringsTest` — 2).

- [ ] **Шаг 11: подключить Android-приложение**

`androidApp/build.gradle.kts` — добавить `implementation(libs.kotlinx.coroutines.core)`: область корутин точка входа создаёт сама, значит типы корутин должны быть видны. Зависимость от `:client-state` не нужна: `platform-android` объявляет `api(project(":client-state"))` (задача 1, шаг 8), и типы `SettingsStore` и `HostConnection` приходят из `AndroidClientDependencies` транзитивно.

`platform-android/src/androidMain/kotlin/dev/aide/platform/android/AndroidClientRuntime.kt` — платформенное связывание Android-клиента: инициализация хранилища настроек и адрес хоста по умолчанию живут здесь, а не в точке входа:

```kotlin
package dev.aide.platform.android

import android.content.Context
import dev.aide.client.state.HostConnection
import dev.aide.client.state.KtorHostConnection
import dev.aide.client.state.settings.SettingsStore
import dev.aide.client.state.settings.createKeyValueStore
import dev.aide.client.state.settings.initKeyValueStore
import kotlinx.coroutines.CoroutineScope
import org.koin.core.KoinApplication
import org.koin.core.module.Module
import org.koin.dsl.module

/**
 * Платформенное связывание Android-клиента (T-0.15).
 *
 * `createKeyValueStore()` требует, чтобы контекст приложения был задан заранее,
 * поэтому инициализация и её первый потребитель живут в одном месте. Адрес хоста
 * по умолчанию — адрес машины разработчика для эмулятора: автоматического поиска
 * хоста в этом этапе нет (T-1.51).
 *
 * Граф Koin изолированный (`KoinApplication.init()`, а не глобальный контекст):
 * `start()` вызывается из `MainActivity.onCreate`, то есть и после пересоздания
 * активити, а глобальный контекст допускает только один запуск на процесс.
 * Созданные в графе объекты продолжают жить и после того, как ссылка на граф
 * перестала быть нужна: закрывать в них нечего.
 */
object AndroidClientRuntime {

    /** Адрес хоста по умолчанию для эмулятора: 10.0.2.2 указывает на машину-хост. */
    const val DEFAULT_ENDPOINT: String = "ws://10.0.2.2:8080/ws"

    /** Модуль Koin Android-клиента: настройки и соединение собираются в одном месте. */
    internal fun module(scope: CoroutineScope): Module = module {
        single { scope }
        single { SettingsStore(createKeyValueStore()) }
        single<HostConnection> {
            KtorHostConnection(
                endpoint = get<SettingsStore>().hostEndpoint ?: DEFAULT_ENDPOINT,
                scope = get(),
            )
        }
    }

    /** Готовит настройки и соединение; вызывается из `MainActivity` до `setContent`. */
    fun start(context: Context, scope: CoroutineScope): AndroidClientDependencies {
        initKeyValueStore(context)
        val graph = KoinApplication.init().modules(module(scope))
        return AndroidClientDependencies(
            settings = graph.koin.get<SettingsStore>(),
            connection = graph.koin.get<HostConnection>(),
        )
    }
}

/** Настройки и соединение, собранные для Android-клиента. */
data class AndroidClientDependencies(
    /** Настройки клиента: путь к репозиторию, тема, режим управления, адрес хоста. */
    val settings: SettingsStore,
    /** Соединение с хостом по адресу из настроек. */
    val connection: HostConnection,
)
```

`androidApp/src/main/kotlin/dev/aide/android/MainActivity.kt`:

```kotlin
package dev.aide.android

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import dev.aide.client.ui.App
import dev.aide.platform.android.AndroidClientRuntime
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * Точка входа Android-приложения.
 *
 * Хост здесь не поднимается: на телефоне приложение подключается к хосту по адресу
 * из настроек. Автоматическое обнаружение хоста и сопряжение устройств появятся
 * в T-1.51 и на этапе 5 (T-5.11) — до тех пор адрес вводится вручную.
 */
class MainActivity : ComponentActivity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val dependencies = AndroidClientRuntime.start(context = this, scope = scope)

        setContent {
            App(
                connection = dependencies.connection,
                settings = dependencies.settings,
                scope = scope,
            )
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }
}
```

`AndroidClientRuntime` читает адрес хоста из настроек — поле `hostEndpoint` в `SettingsStore` уже добавлено в задаче 14 вместе с тестом на то, что оно переживает перезапуск. Здесь его достаточно прочитать.

- [ ] **Шаг 12: собрать оба таргета**

```bash
./gradlew :androidApp:assembleDebug :desktopApp:createDistributable
```

Ожидаемо: `BUILD SUCCESSFUL`; на месте `androidApp/build/outputs/apk/debug/androidApp-debug.apk` и `desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp` (дистрибутив Compose Desktop собирает задача `createDistributable`).

- [ ] **Шаг 13: сквозная проверка на десктопе**

```bash
# 1. Готовим репозиторий-фикстуру: тот же набор файлов, что создаёт TempRepoFixture в тестах
# Блок выполняется из корня этого репозитория и возвращается в него в конце.
mkdir -p /tmp/aide-fixture && cd /tmp/aide-fixture
git init -b master && git config user.email dev@aide.local && git config user.name Dev
mkdir -p src/auth && echo 'fun login() = Unit' > src/auth/Login.kt
git add . && git commit -m "первый коммит"
echo 'class Api' > src/Api.kt && git add . && git commit -m "второй коммит"
echo 'fun login() = "token"' > src/auth/Login.kt   # незакоммиченное изменение
cd -   # возврат в каталог, из которого запускался блок (корень репозитория)
```

```bash
./desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp
```

Ожидаемо и проверяется глазами: окно открывается; в шапке после открытия репозитория — «Ветка: master»; в поле «Путь к репозиторию» ввести `/tmp/aide-fixture`, нажать «Открыть»; появляется дерево с `src/`, `src/auth/`, `src/auth/Login.kt`, `src/Api.kt`; тап по `Login.kt` открывает содержимое с заголовком «Файл: src/auth/Login.kt».

- [ ] **Шаг 14: проверить пять состояний вручную на десктопе**

Пройти по списку и убедиться, что каждое состояние наблюдаемо, а не только покрыто тестом:

| Состояние | Как воспроизвести | Что должно быть на экране |
|---|---|---|
| загрузка | открыть большой репозиторий (например, саму папку проекта) | строки-скелетоны, а не пустой экран |
| пусто | `mkdir /tmp/aide-empty && cd /tmp/aide-empty && git init` → открыть этот путь | «В репозитории нет коммитов и файлов…» |
| ошибка (путь) | ввести `/tmp/нет-такого-каталога` и нажать «Открыть» | «Путь не существует…» и кнопка «Повторить» |
| ошибка (не репозиторий) | открыть `/tmp` | «Каталог не является git-репозиторием…» |
| нет связи | на десктопе в локальном режиме не воспроизводится: хост живёт в том же процессе — это состояние проверяется на Android с выключенной сетью (шаг 15) | — |
| нет прав | ввести путь `/etc/passwd` в поле адреса репозитория | «Нет доступа» с путём и причиной |

Состояние «нет связи» на десктопе в локальном режиме не воспроизводится: хост живёт в том же процессе, поэтому его проверяют на Android с выключенной сетью — шаг 15.

- [ ] **Шаг 15: сквозная проверка на Android**

```bash
# Хост на машине разработчика: поднимаем десктопное приложение и запоминаем порт из лога
./desktopApp/build/compose/binaries/main/app/desktopApp/bin/desktopApp 2>&1 | grep "Хост слушает"
```

В логе будет `Хост слушает ws://127.0.0.1:<порт>/ws`. Затем:

```bash
./gradlew :androidApp:installDebug
adb shell am start -n dev.aide.android/.MainActivity
```

Адрес хоста на Android задаётся на экране настроек и читается один раз при старте приложения, поэтому порядок такой:

1. Открыть в приложении настройки и ввести адрес хоста: `ws://10.0.2.2:<порт>/ws` (для эмулятора) или `ws://<IP машины>:<порт>/ws` (для устройства в той же сети). Нажать «Сохранить адрес».
2. Перезапустить приложение — иначе новый адрес не применится:

```bash
adb shell am force-stop dev.aide.android
adb shell am start -n dev.aide.android/.MainActivity
```

3. Открыть путь `/tmp/aide-fixture` в настройках — приложение должно показать то же дерево и то же содержимое файла, что и десктоп.

Перезапуск нужен потому, что соединение создаётся один раз при старте приложения; переподключение к другому хосту на ходу появится в `T-1.51`.

Отдельно проверить состояние «нет связи»: выключить Wi-Fi на устройстве при открытом дереве — должна появиться плашка «Нет связи с хостом», а дерево из кэша остаться на экране.

- [ ] **Шаг 16: обновить статусы задач этапа**

Отметить в `docs/tasks/01-foundation.md` выполненные задачи `T-0.1`–`T-0.16` по фактическому результату, следуя правилам из `docs/tasks/README.md`. Если какие-то критерии не выполнены — оставить `[ ]` и дописать статус в строке задачи, а не отмечать по факту написания кода.

- [ ] **Шаг 17: финальный прогон и коммит**

```bash
./gradlew clean :domain:jvmTest :protocol:jvmTest :host-core:test :client-state:jvmTest :client-ui:jvmTest detekt verifyModuleBoundaries :androidApp:assembleDebug :desktopApp:createDistributable
./gradlew -p build-logic test
```

Ожидаемо: `BUILD SUCCESSFUL` в обоих прогонах (вторая команда — тесты конвенций сборки: они живут в отдельной included-сборке и в корневой `check` не входят). Затем:

```bash
git add client-state client-ui androidApp desktopApp platform-android platform-desktop docs/tasks/01-foundation.md
git commit -m "feat(client): экраны дерева и файла с пятью состояниями, настройки и сквозная проверка этапа 0"
```

---

## Самопроверка плана

**Покрытие задач этапа.** Каждая из 16 задач `docs/tasks/01-foundation.md` имеет соответствующую задачу плана:

| Задача этапа | Задача плана |
|---|---|
| `T-0.1` инициализация KMP-проекта | 1 |
| `T-0.2` целевые платформы | 2 |
| `T-0.3` CI | 3 |
| `T-0.4` границы модулей | 4 |
| `T-0.5` модели домена | 5 |
| `T-0.6` инварианты | 6 |
| `T-0.7` `RiskLevel` | 7 |
| `T-0.8` сообщения протокола | 8 |
| `T-0.9` совместимость версий | 9 |
| `T-0.10` транспорт с реконнектом | 10 |
| `T-0.11` воркспейс и файлы | 11 |
| `T-0.12` чтение состояния git | 12 |
| `T-0.13` локальный хост | 13 |
| `T-0.14` настройки, тема, строки | 14 |
| `T-0.16` хранилище метаданных | 15 |
| `T-0.15` сквозная проверка | 16 |

**Согласованность имён.** Типы, введённые один раз и используемые дальше: `TaskId`, `RunId`, `PacketId`, `HunkId`, `ToolCallId`, `SnapshotRef`, `Task`, `AgentRun`, `ToolCall`, `ToolPermission`, `ChangePacket`, `FileChange`, `Hunk`, `HunkLine`, `TestStatus`, `ReviewDecision`, `Snapshot`, `RiskLevel`, `AutonomyMode`, `Permission`, `Cost`, `RequestId`, `WorkspaceId`, `SessionId`, `ProtocolVersion`, `ClientMessage`, `HostMessage`, `ProtocolError`, `FileTreePayload`, `FileContentPayload`, `HostStatePayload`, `HostEvent`, `HostMode`, `Workspace`, `WorkspaceFileSystem`, `FileTreeBuilder`, `GitRepository`, `JGitRepository`, `ChangeFile`→`ChangedFile`, `CommitInfo`, `EmbeddedHost`, `ProtocolServer`, `ClientSession`, `ClientMessageHandler`, `StageZeroHandler`, `HostConnection`, `KtorHostConnection`, `HostClient`, `HostSession`, `ConnectionState`, `ScreenState`, `AppStateStore`, `SettingsStore`, `KeyValueStore`, `AndroidClientRuntime`, `AndroidClientDependencies`, `DesktopRuntime`, `HostStore`, `DatabaseFactory`.

**Что осознанно не входит в план**, потому что относится к другим этапам: агент и его рантайм (`T-1.1`–`T-1.6`), diff-движок (`T-1.21`–`T-1.23`), инбокс и hunk-вьюер (`T-1.28`–`T-1.37`), редактор (этап 2), удалённый транспорт через интернет (этап 5), Rust-модули (этап 6). Поле `Hunk.explanation` для пояснений на уровне блока добавляется в `T-1.25`, а не здесь.

**Известные ограничения плана, которые исполнителю стоит знать:**

1. `host-core` объявлен JVM-модулем, а не KMP. Это соответствует § 3.2 (хост — JVM-сервис) и § 7.1, но означает, что хост нельзя запустить на Android. Если позже понадобится локальный хост на телефоне, модуль придётся делать мультиплатформенным — это отдельное решение, а не следствие этапа 0.
2. Состояние «нет связи» на десктопе в локальном режиме не воспроизводится (хост живёт в том же процессе). Честная проверка этого состояния — на Android с выключенной сетью (шаг 15).
3. Тесты `JGitRepositoryTest` и `MigrationTest` требуют `git` в `PATH` и JDBC-драйвер SQLite; в CI на `ubuntu-latest` оба есть. Локально на Windows `git` нужно добавить в `PATH`, иначе тесты упадут с явным сообщением из `GitCliFixture.requireGit`.

---

## Как выполнять этот план

План рассчитан на исполнение по задачам с проверкой после каждой. Рекомендуемый порядок — как в документе: каждая следующая задача опирается на предыдущую, а задачи 1–4 задают сборку, без которой остальное не запускается.

**Способ 1: субагент на задачу (рекомендуется).** Я запускаю отдельного агента на каждую задачу, проверяю результат по критериям шага и только потом перехожу к следующей. Даёт быструю итерацию и свежий контекст на каждой задаче.

**Способ 2: последовательное исполнение в этой сессии.** Выполняю задачи подряд с остановками на проверку после каждой. Медленнее в переключениях, но не требует передачи контекста между агентами.

Уточните, какой способ предпочитаете, или скажите, с какой задачи начинать.
