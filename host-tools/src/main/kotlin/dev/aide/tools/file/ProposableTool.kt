package dev.aide.tools.file

import dev.aide.domain.FileChange
import dev.aide.domain.RiskLevel
import dev.aide.tools.ToolContext
import kotlinx.serialization.json.JsonObject

/**
 * Способность инструмента описать файловое изменение без записи на диск (T-1.15).
 *
 * Реализуется только теми инструментами, которые могут вернуть содержательный [FileChange]:
 * у `write_file` новое содержимое уже есть в аргументах, а старое можно прочитать через
 * [ToolContext]. Произвольная команда этого сделать не может, поэтому в режимах
 * «только предлагать» и «авто-применять безопасное» для не-SAFE она получает честный
 * отказ, а не исполняется ради diff'а.
 */
interface ProposableTool {

    /**
     * Возвращает файловое изменение, которое произошло бы при выполнении вызова.
     *
     * [risk] присваивается hunk'ам из той же доверенной оценки, что и предложению.
     * При невозможности описать изменение возвращается [ProposeResult.Failure] с исходом
     * для модели; жёсткий предел (выход за корень) переводится в исключение
     * [dev.aide.tools.limits.HardLimitViolation], как и в обычном выполнении.
     */
    suspend fun describe(arguments: JsonObject, context: ToolContext, risk: RiskLevel): ProposeResult
}

/** Результат попытки описать изменение без применения. */
sealed interface ProposeResult {

    /** Описание изменения готово к сохранению в очередь предложений. */
    data class Files(val files: List<FileChange>) : ProposeResult

    /** Описать изменение не удалось; результат уходит модели как исход вызова. */
    data class Failure(val result: dev.aide.tools.ToolResult) : ProposeResult
}
