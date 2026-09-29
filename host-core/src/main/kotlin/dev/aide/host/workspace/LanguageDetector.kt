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
