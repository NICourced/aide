package dev.aide.host.workspace

import dev.aide.domain.Permission
import dev.aide.domain.ToolPermission
import dev.aide.tools.limits.HardLimitViolation
import dev.aide.tools.limits.WorkspaceBoundary
import dev.aide.tools.permission.DenyReason
import dev.aide.tools.permission.PermissionDecision
import dev.aide.tools.permission.PermissionResolver
import dev.aide.tools.permission.ToolKind
import java.nio.file.Files
import kotlin.io.path.name
import kotlin.io.path.writeText
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Интеграционная проверка адаптера: граница воркспейса для инструментов не повторяет
 * канонизацию путей, а переиспользует поведение [WorkspaceFileSystem], и настраиваемое
 * разрешение инструмента этот предел не отменяет (§ 10.1).
 */
class WorkspaceBoundaryAdapterTest {

    private val fixture = TempRepoFixture()
    private val workspace = Workspace.open(fixture.root)
    private val boundary: WorkspaceBoundary = WorkspaceBoundaryAdapter(WorkspaceFileSystem(workspace))

    @AfterTest
    fun tearDown() = fixture.close()

    @Test
    fun `существующий файл внутри корня разрешается`() {
        assertEquals(
            fixture.root.toRealPath().resolve("src/auth/Login.kt"),
            boundary.resolveInside("src/auth/Login.kt"),
        )
    }

    @Test
    fun `несуществующий файл внутри корня разрешается для создания`() {
        assertEquals(
            fixture.root.toRealPath().resolve("src/new/Created.kt"),
            boundary.resolveInside("src/new/Created.kt"),
        )
    }

    @Test
    fun `абсолютный путь внутри корня разрешается`() {
        val target = fixture.root.resolve("src/auth/Login.kt")
        assertEquals(target.toRealPath(), boundary.resolveInside(target.toString()))
    }

    @Test
    fun `подъём по каталогам за корень отклонён`() {
        assertPathNotAllowed("../outside.txt")
    }

    @Test
    fun `абсолютный путь вне корня отклонён`() {
        val outside = Files.createTempFile(fixture.root.parent, "aide-absolute-", ".txt")
        outside.writeText("секрет\n")
        assertTrue(Files.isReadable(outside), "Цель атаки должна быть реально читаемой — иначе проверка вырождена")
        try {
            assertPathNotAllowed(outside.toString())
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `пустой путь отклонён, а не считается корнем`() {
        assertPathNotAllowed("")
    }

    @Test
    fun `симлинк наружу корня отклонён`() {
        val outside = Files.createTempFile("aide-outside-", ".txt")
        outside.writeText("секрет\n")
        assertTrue(Files.isReadable(outside), "Цель симлинка должна быть реально читаемой — иначе вектор вырожден")
        try {
            fixture.createEscapingSymlink("link-out.txt", outside)
            assertPathNotAllowed("link-out.txt")
        } finally {
            Files.deleteIfExists(outside)
        }
    }

    @Test
    fun `строка с NUL отклоняется, а не роняет хост`() {
        assertPathNotAllowed("src/bad\u0000.kt")
    }

    @Test
    fun `allow в настройках не отменяет запрет за пределами воркспейса`() {
        val sibling = Files.createTempFile(fixture.root.parent, "aide-secret-", ".txt")
        sibling.writeText("секрет\n")
        assertTrue(Files.isReadable(sibling), "Цель атаки должна быть реально читаемой — иначе проверка вырождена")
        try {
            val resolver = PermissionResolver {
                ToolPermission(tool = "write_file", read = Permission.ALLOW, write = Permission.ALLOW)
            }
            assertEquals(
                PermissionDecision.Allow,
                resolver.resolve("write_file", ToolKind.WRITE),
                "Предусловие теста: настройки разрешают запись — иначе отказ объяснялся бы резолвером",
            )
            assertPathNotAllowed("../${sibling.name}")
        } finally {
            Files.deleteIfExists(sibling)
        }
    }

    private fun assertPathNotAllowed(path: String) {
        val error = assertFailsWith<HardLimitViolation> { boundary.resolveInside(path) }
        assertEquals(DenyReason.PATH_NOT_ALLOWED, error.reason, "путь «$path»")
        assertTrue(error.detail.isNotBlank(), "точная причина отказа должна оставаться в detail — её читает журнал")
        assertNotNull(error.cause, "исходное исключение должно сохраняться в cause")
    }
}
