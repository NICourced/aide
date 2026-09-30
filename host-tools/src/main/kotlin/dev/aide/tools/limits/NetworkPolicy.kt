package dev.aide.tools.limits

import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionDecision

/**
 * Сеть запрещена, пока адрес не перечислен явно; по умолчанию список пуст (FR-TOOLS-13, § 10.1).
 * Это не разрешение на инструмент, а предел: список хостов не пополняется правом `allow`,
 * поэтому инструмент с любым разрешением не выйдет за перечисленные адреса.
 *
 * Сверяется **строка имени хоста**, и только она: `API.OpenAI.com` (регистр), `api.openai.com:443`
 * (порт), `example.com.` (корневая точка) — уже другие записи и получат отказ; `localhost`
 * и `127.0.0.1` тоже разные записи. Поэтому это предполётная проверка имени, а **не изоляция**:
 * разрешённое имя может резолвиться куда угодно, а редирект с него уводит на другой хост.
 * Базовую изоляцию сетевого вызова даёт `run_command` (T-1.9), полную изоляцию файловой
 * системы и сети — T-3.22 (FR-TOOLS-13).
 */
class NetworkPolicy(private val allowedHosts: Set<String> = emptySet()) {

    /**
     * Пропускает только перечисленные хосты; на пустом списке отказывает любому.
     * Пустой хост (или из одних пробелов) запрещён всегда, даже если попал в список:
     * запись пустой строки не должна открывать «никуда», это отказ, а не разрешение.
     */
    fun check(host: String): PermissionDecision =
        if (host.isNotBlank() && host in allowedHosts) PermissionDecision.Allow
        else PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN)
}
