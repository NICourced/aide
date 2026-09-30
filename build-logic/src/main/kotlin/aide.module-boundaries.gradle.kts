import aide.build.VerifyModuleBoundariesTask

// Модули, чьи границы проверяются (T-0.4). `platform-desktop` в список не входит
// осознанно: локальный режим (§ 3.3) разрешает ему держать встроенный хост,
// и ребро `platform-desktop` → `host-core` — не нарушение.
val guardedModules = setOf(
    "domain", "protocol", "client-state", "client-ui", "host-core", "host-agent", "host-tools",
)

// Проверяются только main-наборы исходников. Тестовые наборы исключены явно, а не
// «случайно» из-за отсутствия запрещённых импортов в них: интеграционный тест хоста
// (EmbeddedHostTest, задача 13) подключает клиент настоящим соединением, и ребро
// `host-core` → `client-state` в тестах легально. Запрет на клиента в `host-core`
// относится только к main-коду.
val mainSourceSetNames = listOf("commonMain", "jvmMain", "androidMain", "main")

subprojects {
    if (name in guardedModules) {
        val moduleName = name
        val verify = tasks.register<VerifyModuleBoundariesTask>("verifyModuleBoundaries") {
            group = "verification"
            description = "Проверяет границы модуля (T-0.4)"
            module.set(moduleName)
            projectDirectory.set(project.layout.projectDirectory)
            sources.from(fileTree("src") {
                mainSourceSetNames.forEach { include("$it/**/*.kt") }
            })
        }
        // `check` появляется при применении base-плагина — это происходит уже после
        // выполнения этого блока, поэтому привязка идёт через `plugins.withId`, а не
        // через `tasks.named("check")` (иначе задача ещё не существует).
        plugins.withId("base") {
            tasks.named("check") { dependsOn(verify) }
        }
    }
}
