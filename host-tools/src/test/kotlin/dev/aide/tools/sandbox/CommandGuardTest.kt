package dev.aide.tools.sandbox

import dev.aide.tools.ToolsWorkspace
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.limits.NetworkPolicy
import dev.aide.tools.permission.DenyReason
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * T-1.9: предполётная сверка аргументов — какие адреса и пути ловятся, а какие нет.
 *
 * Смысл проверки в том, чтобы **не мешать** законной команде: ложный отказ с кодом
 * `NETWORK_FORBIDDEN` на `npm install react@18.2.0` стоил бы дороже пропуска, потому
 * что настоящий барьер — подтверждение пользователя, а не эта сверка. Поэтому здесь две
 * группы проверок: «похожее на сеть, но не сеть — не отказ» и «однозначный адрес — отказ».
 */
class CommandGuardTest {

    private val workspace = ToolsWorkspace()

    @AfterTest
    fun tearDown() {
        workspace.close()
    }

    @Test
    fun `слова с собакой и двоеточием не принимаются за хосты`() {
        ARGUMENTS_THAT_ARE_NOT_HOSTS.forEach { argument ->
            assertEquals(
                emptyList(),
                hostCandidates(argument),
                "«$argument» — не сетевой адрес, кандидатов быть не должно",
            )
            // Отказ здесь был бы ложным: команда в сеть не собиралась.
            CommandGuard(NetworkPolicy()).check(listOf(argument), workspace.context)
        }
    }

    @Test
    fun `полный адрес детектируется`() {
        assertEquals(listOf(EXAMPLE), hostCandidates("https://$EXAMPLE/x"))

        val violation = violationOf("curl", "https://$EXAMPLE/x")

        assertEquals(DenyReason.NETWORK_FORBIDDEN, violation.reason)
    }

    @Test
    fun `оба адреса в одном аргументе детектируются`() {
        val argument = "wget https://a.com/x https://b.com/y"

        assertEquals(listOf("a.com", "b.com"), hostCandidates(argument), "нужны все вхождения адреса")

        // Разрешён только первый: второй обязан остаться отказом — на нём и видна разница
        // между `find` (первое вхождение) и `findAll` (все).
        val violation = assertFailsWith<HardLimitViolation> {
            CommandGuard(NetworkPolicy(setOf("a.com"))).check(listOf(argument), workspace.context)
        }
        assertEquals(DenyReason.NETWORK_FORBIDDEN, violation.reason)
    }

    @Test
    fun `scp-подобные адреса детектируются`() {
        assertEquals(listOf("github.com"), hostCandidates("git@github.com:repo.git"))
        assertEquals(listOf("host"), hostCandidates("u@host:/x"))

        assertEquals(DenyReason.NETWORK_FORBIDDEN, violationOf("git", "clone", "git@github.com:repo.git").reason)
    }

    @Test
    fun `localhost и IP-литерал с портом детектируются`() {
        assertTrue(hostCandidates("server=localhost:8080").contains("localhost"))
        assertTrue(hostCandidates("db 127.0.0.1:5432").contains("127.0.0.1"))
    }

    @Test
    fun `разрешённый хост пропускается`() {
        // Не бросает — значит, вызов проходит предполётную сверку сети.
        CommandGuard(NetworkPolicy(setOf(EXAMPLE))).check(listOf("https://$EXAMPLE"), workspace.context)
    }

    @Test
    fun `абсолютный путь в значении опции отклоняется`() {
        // Решение: `-Dkey=/a/b` — законная форма вызова, но значение называет абсолютный
        // путь вне воркспейса, а сверка значений опций (`--опция=путь`) прямо требуется
        // решением 2 плана. Поэтому это отказ по пути, а не ложный отказ по сети.
        val violation = violationOf("java", "-Dkey=/a/b")

        assertEquals(DenyReason.PATH_NOT_ALLOWED, violation.reason)
    }

    /** Первое нарушение жёсткого предела от [CommandGuard] на пустом списке разрешённых. */
    private fun violationOf(vararg command: String): HardLimitViolation = assertFailsWith<HardLimitViolation> {
        CommandGuard(NetworkPolicy()).check(command.toList(), workspace.context)
    }

    private companion object {

        const val EXAMPLE: String = "example.com"

        /**
         * Аргументы, похожие на адрес, но адресом не являющиеся.
         *
         * `react@18.2.0` — версия за собакой; `fix@home` — слово без точки; `error.log:12`
         * — файл со строкой из вывода grep; `api.example.com:443` — домен с портом:
         * намеренный пропуск, чтобы не ловить `error.log:12` (см. KDoc `CommandGuard`).
         */
        val ARGUMENTS_THAT_ARE_NOT_HOSTS: List<String> = listOf(
            "react@18.2.0",
            "fix@home",
            "error.log:12",
            "api.example.com:443",
        )
    }
}
