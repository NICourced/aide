package dev.aide.tools.limits

import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionDecision
import kotlin.test.Test
import kotlin.test.assertEquals

class NetworkPolicyTest {

    @Test
    fun `пустой список отказывает любому хосту`() {
        val policy = NetworkPolicy()
        assertEquals(
            PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
            policy.check("api.openai.com"),
        )
        assertEquals(
            PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
            policy.check("localhost"),
        )
    }

    @Test
    fun `перечисленный хост разрешён, остальные запрещены`() {
        val policy = NetworkPolicy(setOf("registry.npmjs.org", "proxy.internal"))
        assertEquals(PermissionDecision.Allow, policy.check("registry.npmjs.org"))
        assertEquals(PermissionDecision.Allow, policy.check("proxy.internal"))
        assertEquals(
            PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
            policy.check("api.openai.com"),
        )
    }

    @Test
    fun `пустая строка хоста тоже запрещена`() {
        val policy = NetworkPolicy(setOf("registry.npmjs.org"))
        assertEquals(
            PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
            policy.check(""),
        )
    }

    @Test
    fun `регистр, порт, корневая точка в конце и поддомен — это другие записи`() {
        val policy = NetworkPolicy(setOf("api.openai.com"))
        assertEquals(PermissionDecision.Allow, policy.check("api.openai.com"))
        val others = listOf("API.OpenAI.com", "api.openai.com:443", "api.openai.com.", "sub.api.openai.com")
        others.forEach { host ->
            assertEquals(
                PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
                policy.check(host),
                "хост «$host» не совпадает с записью списка и должен быть запрещён",
            )
        }
    }

    @Test
    fun `пустой хост запрещён, даже если записан в списке разрешённых`() {
        val policy = NetworkPolicy(setOf("", "   ", "api.openai.com"))
        assertEquals(PermissionDecision.Allow, policy.check("api.openai.com"))
        listOf("", " ", "   ", "\t").forEach { host ->
            assertEquals(
                PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
                policy.check(host),
                "пустой хост «$host» должен быть запрещён независимо от списка",
            )
        }
    }

    @Test
    fun `localhost и 127_0_0_1 — разные записи списка`() {
        val policy = NetworkPolicy(setOf("localhost"))
        assertEquals(PermissionDecision.Allow, policy.check("localhost"))
        assertEquals(
            PermissionDecision.Deny(DenyReason.NETWORK_FORBIDDEN),
            policy.check("127.0.0.1"),
        )
    }
}
