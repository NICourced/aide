package dev.aide.domain

/** Инвариант домена, который удаётся проверить конструктором. */
enum class DomainInvariant(
    /** Человекочитаемая формулировка инварианта; попадает в сообщение [DomainViolation]. */
    val description: String,
) {
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
    /** Инвариант, который нарушен. */
    val invariant: DomainInvariant,
    detail: String,
) : IllegalArgumentException("${invariant.description}. $detail")

/**
 * Проверяет инварианты 1 и 5 § 4.1. Вызывается из `init`-блока [ChangePacket],
 * поэтому некорректный пакет нельзя ни создать, ни разобрать из сериализованного вида.
 *
 * Помимо § 4.1 проверяется усиление: файл, чьё содержимое меняется
 * ([FileChangeKind.ADDED], [FileChangeKind.MODIFIED]), обязан иметь хотя бы один
 * hunk. Пустой список hunk'ов допустим только там, где содержимое не меняется:
 * [FileChangeKind.DELETED] и [FileChangeKind.RENAMED] (чистый `git mv`).
 *
 * Проверки разнесены по функциям с одним `throw` каждая: детектор ограничивает
 * число `throw` в функции, а исключения здесь — единственный способ сообщить о нарушении.
 */
internal fun validatePacket(files: List<FileChange>, risk: RiskLevel) {
    requireUniquePaths(files)
    files.forEach(::requireHunksBelongToFile)
    requireRiskNotBelowHunks(files, risk)
}

/** Инвариант 1: один путь — одно [FileChange] в пакете. */
private fun requireUniquePaths(files: List<FileChange>) {
    val duplicates = files.groupBy { it.path }.filterValues { it.size > 1 }.keys
    if (duplicates.isNotEmpty()) {
        throw DomainViolation(
            DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
            "Пути, встречающиеся в пакете больше одного раза: ${duplicates.sorted()}",
        )
    }
}

/**
 * Инвариант 1: у hunk'а тот же файл, что у [FileChange], и файл с изменяемым
 * содержимым не пуст.
 *
 * Пустой список hunk'ов допустим только для [FileChangeKind.DELETED] (файл удалён
 * целиком) и [FileChangeKind.RENAMED] (чистый `git mv`: путь меняется, содержимое —
 * нет). Для [FileChangeKind.ADDED] и [FileChangeKind.MODIFIED] отсутствие hunk'ов —
 * ошибка: изменённый файл обязан объяснить, что именно изменилось.
 */
private fun requireHunksBelongToFile(file: FileChange) {
    if (file.changeKind.requiresHunks() && file.hunks.isEmpty()) {
        throw DomainViolation(
            DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
            "Файл '${file.path}' изменён (${file.changeKind}), но не содержит ни одного hunk'а",
        )
    }
    val foreign = file.hunks.firstOrNull { it.filePath != file.path } ?: return
    throw DomainViolation(
        DomainInvariant.HUNK_HAS_EXACTLY_ONE_FILE,
        "Hunk '${foreign.id.value}' указывает файл '${foreign.filePath}', " +
            "но лежит в FileChange '${file.path}'",
    )
}

/** Меняет ли этот вид изменения содержимое: только тогда пакет обязан нести hunk'и. */
private fun FileChangeKind.requiresHunks(): Boolean = when (this) {
    FileChangeKind.ADDED, FileChangeKind.MODIFIED -> true
    FileChangeKind.DELETED, FileChangeKind.RENAMED -> false
}

/** Инвариант 5: риск пакета не ниже риска самого опасного его hunk'а. */
private fun requireRiskNotBelowHunks(files: List<FileChange>, risk: RiskLevel) {
    val highestHunkRisk = files.flatMap { it.hunks }.maxOfOrNull { it.risk.ordinal } ?: return
    if (risk.ordinal < highestHunkRisk) {
        val highest = RiskLevel.entries[highestHunkRisk]
        throw DomainViolation(
            DomainInvariant.PACKET_RISK_NOT_BELOW_HUNKS,
            "Риск пакета $risk ниже риска самого опасного hunk'а ($highest)",
        )
    }
}
