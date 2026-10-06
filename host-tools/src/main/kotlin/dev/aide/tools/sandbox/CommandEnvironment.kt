package dev.aide.tools.sandbox

import java.nio.file.Path

/**
 * Окружение команды, запускаемой хостом в воркспейсе (T-1.9, T-1.10).
 *
 * Только перечисленные переменные, а не унаследованный набор: ключ провайдера живёт
 * в переменных окружения хоста (T-1.58), и команда с наследуемым окружением прочитала
 * бы его через `env` — «урезанное окружение» стало бы фикцией. Поэтому передаются
 * только `PATH`, локаль и часовой пояс ([PASSED_NAMES]), а `HOME` и `TMPDIR` указывают
 * внутрь воркспейса: так команда не пишет во временный каталог хоста и не читает
 * домашние файлы пользователя.
 *
 * Общая для `run_command`, тестов и линтера: окружение — часть песочницы, и второй
 * его вариант разошёлся бы с первым молча (T-1.10).
 *
 * @param host источник переменных; по умолчанию — окружение процесса хоста.
 */
internal fun commandEnvironment(root: Path, host: Map<String, String> = System.getenv()): Map<String, String> {
    val environment = LinkedHashMap<String, String>()
    PASSED_NAMES.forEach { name -> host[name]?.let { environment[name] = it } }
    environment[HOME_NAME] = root.toString()
    environment[TMPDIR_NAME] = root.toString()
    return environment
}

/** Переменные, которые команде нужны, чтобы найти программу и говорить на одном языке. */
private val PASSED_NAMES: List<String> = listOf("PATH", "LANG", "LC_ALL", "TZ")

private const val HOME_NAME: String = "HOME"

/** Имя каталога временных файлов: `java.io.tmpdir` читается как `TMPDIR` в Unix и `TEMP` в Windows. */
private const val TMPDIR_NAME: String = "TMPDIR"
