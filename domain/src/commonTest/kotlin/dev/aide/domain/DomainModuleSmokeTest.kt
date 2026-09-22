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
