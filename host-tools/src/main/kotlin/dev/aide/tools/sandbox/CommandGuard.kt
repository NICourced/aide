package dev.aide.tools.sandbox

import dev.aide.tools.ToolContext
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.limits.NetworkPolicy
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionDecision

/**
 * Предполётная сверка аргументов команды (T-1.9).
 *
 * Аргументы разбираются до запуска процесса: путь, названный явно, разрешается через
 * [ToolContext.resolveInside] (выход за корень — [HardLimitViolation]), а имя хоста
 * сверяется с [NetworkPolicy]. Это **не изоляция**: сверка ловит то, что агент написал
 * явно, и не мешает самой программе сходить наружу — содержимое скрипта, конфигурация
 * внутри запущенного кода и сетевой редирект проверке недоступны. Поэтому настоящий
 * барьер здесь — подтверждение пользователя (умолчание `ASK`), а полная изоляция
 * файловой системы и сети — T-3.22 (FR-TOOLS-13).
 *
 * Распознаются только **однозначные** сетевые адреса: полный `scheme://host`,
 * scp-подобная форма `user@host:путь` и `host:port` для `localhost` и IP-литералов.
 * Голое имя хоста (`curl example.com`) и домен с числовым портом (`api.example.com:443`)
 * намеренно не детектируются: отличить их от обычного слова и от `файл:строка` нечем,
 * а ошибочный отказ с кодом `NETWORK_FORBIDDEN` на законной команде дороже пропуска.
 * Пропуск допустим именно потому, что это предполётная проверка, а не барьер.
 *
 * Оба нарушения летят одним типом [HardLimitViolation] с разными кодами причины
 * ([DenyReason.PATH_NOT_ALLOWED] и [DenyReason.NETWORK_FORBIDDEN]): это отказ жёсткого
 * предела, а не ошибка инструмента, и в `ToolOutcome.DENIED` его переводит точка вызова.
 */
internal class CommandGuard(private val network: NetworkPolicy) {

    /** Проверяет все аргументы; при нарушении бросает [HardLimitViolation] — команда не запускается. */
    fun check(command: List<String>, context: ToolContext) {
        command.forEach { argument ->
            checkPath(argument, context)
            checkHosts(argument)
        }
    }

    /** Путь, названный аргументом или значением опции, обязан быть внутри воркспейса. */
    private fun checkPath(argument: String, context: ToolContext) {
        pathValue(argument)?.let(context::resolveInside)
    }

    /** Значение аргумента, если оно похоже на путь: сам аргумент или правая часть `--опция=путь`. */
    private fun pathValue(argument: String): String? {
        val value = if (argument.startsWith("-")) argument.substringAfter('=', "") else argument
        return value.takeIf { it.isNotBlank() && looksLikePath(it) }
    }

    /** Хост-кандидаты сверяются с политикой; не-`Allow` — отказ с кодом сети. */
    private fun checkHosts(argument: String) {
        hostCandidates(argument).forEach { host ->
            if (network.check(host) !is PermissionDecision.Allow) {
                throw HardLimitViolation(
                    reason = DenyReason.NETWORK_FORBIDDEN,
                    detail = "адрес «$host» не разрешён: сеть запрещена, пока хост не перечислен явно",
                )
            }
        }
    }
}

/** Путь с абсолютным началом, буквой диска, тильдой или выходом наверх через `..`. */
private fun looksLikePath(value: String): Boolean =
    value.startsWith("/") ||
        value.startsWith("~") ||
        value.startsWith("\\") ||
        DRIVE_LETTER.matches(value) ||
        ESCAPING_SEGMENT.containsMatchIn(value)

/**
 * Кандидаты в имена хостов внутри одного аргумента.
 *
 * Формы выбраны так, чтобы ложный отказ на законной команде был невозможен:
 * - `scheme://host` — адрес без двусмысленности, все вхождения (в аргументе их бывает
 *   несколько: `wget https://a/x https://b/y`), поэтому `findAll`, а не `find`;
 * - `user@host:путь` — scp-подобная форма; двоеточие после хоста отличает её от `fix@home`;
 * - `user@host` без двоеточия — только если хост похож на имя хоста: точка и не версия
 *   (`react@18.2.0` — версия, `fix@home` — без точки, оба пропускаются);
 * - `host:port` — только `localhost`, IPv4 и IPv6-литерал с числовым портом, иначе
 *   `error.log:12` из вывода grep принимался бы за сетевой адрес.
 */
internal fun hostCandidates(argument: String): List<String> {
    val hosts = LinkedHashSet<String>()
    SCHEME.findAll(argument).forEach { hosts += hostOfAuthority(it.groupValues[1]) }
    SCP_HOST.findAll(argument).forEach { hosts += it.groupValues[1] }
    USER_HOST.findAll(argument).forEach { match ->
        val host = match.groupValues[1]
        if (isHostName(host)) hosts += host
    }
    HOST_PORT.findAll(argument).forEach { hosts += it.groupValues[1].trim('[', ']') }
    return hosts.filter { it.isNotBlank() }
}

/** Похоже ли слово после собаки на имя хоста: `localhost`, IP или домен, но не версия. */
private fun isHostName(host: String): Boolean =
    host == LOCALHOST || IPV4.matches(host) || (host.contains('.') && !VERSION.matches(host))

/** Хост из `user@host:port`: убирает пользователя и порт, оставляет имя (IPv6 — в скобках). */
private fun hostOfAuthority(authority: String): String {
    val withoutUser = authority.substringAfterLast('@')
    return if (withoutUser.startsWith("[")) {
        withoutUser.substringAfter('[').substringBefore(']')
    } else {
        withoutUser.substringBefore(':')
    }
}

private const val LOCALHOST: String = "localhost"

/** Версия вида `18.2.0`: точка есть, но это не хост, и путать их нельзя. */
private val VERSION = Regex("^\\d+(\\.\\d+)*$")
private val IPV4 = Regex("^(\\d{1,3}\\.){3}\\d{1,3}$")

/** Буква диска: и `C:\x`, и drive-relative `C:foo` (значение разрешается границей). */
private val DRIVE_LETTER = Regex("^[A-Za-z]:.*$")
private val ESCAPING_SEGMENT = Regex("(^|[/\\\\])\\.\\.([/\\\\]|$)")

/** Полный адрес; хвост за именем хоста (путь, порт, запрос) в кандидат не входит. */
private val SCHEME = Regex("[A-Za-z][A-Za-z0-9+.\\-]*://([^/\\s?#]*)")

/** scp-подобная форма `user@host:путь`: двоеточие обязательно. */
private val SCP_HOST = Regex("@([A-Za-z0-9][A-Za-z0-9.\\-]*):")

/** `user@host` без двоеточия: годность хоста решает [isHostName]. */
private val USER_HOST = Regex("@([A-Za-z0-9][A-Za-z0-9.\\-]*)")

/** `host:port`, где хост — `localhost`, IPv4 или IPv6-литерал, а порт — число. */
private val HOST_PORT = Regex(
    "(?:^|[^A-Za-z0-9.\\-])(localhost|(?:\\d{1,3}\\.){3}\\d{1,3}|\\[[0-9A-Fa-f:]+\\]):(\\d{1,5})(?![0-9])",
)
