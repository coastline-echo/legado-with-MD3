package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.WebDavBackupGateway
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.domain.model.WebDavBackup
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebDavBackupUseCaseTest {

    @Test
    fun `backup synchronizes config before delegating`() = runTest {
        val gateway = FakeGateway()
        val useCase = WebDavBackupUseCase(gateway)

        assertEquals("operation-1", useCase.backup())
        assertEquals(listOf("sync", "backup"), gateway.calls)
    }

    @Test
    fun `sync failure prevents network operation`() = runTest {
        val gateway = FakeGateway(syncResult = Result.failure(IllegalStateException("offline")))
        val useCase = WebDavBackupUseCase(gateway)

        val result = runCatching { useCase.getBackupNames() }
        assertTrue(result.isFailure)
        assertEquals(listOf("sync"), gateway.calls)
    }

    private class FakeGateway(
        private val syncResult: Result<Unit> = Result.success(Unit),
    ) : WebDavBackupGateway {
        val calls = mutableListOf<String>()
        override val isJianGuoYun: Boolean = false

        override suspend fun syncConfig(): Result<Unit> {
            calls += "sync"
            return syncResult
        }

        override suspend fun test(): Boolean { calls += "test"; return true }
        override suspend fun backup(): String { calls += "backup"; return "operation-1" }
        override suspend fun getBackupNames(): List<String> { calls += "list"; return emptyList() }
        override suspend fun getLatestBackup(): WebDavBackup? { calls += "latest"; return null }
        override suspend fun restore(name: String): String { calls += "restore"; return "operation-1" }
        override suspend fun restore(name: String, mode: RestoreMode, selectedCategories: Set<String>, categoryModes: Map<String, RestoreMode>, backupPassword: String?): String {
            calls += "restore"
            return "operation-1"
        }
        override suspend fun preview(name: String): RestorePreview {
            calls += "preview"
            return RestorePreview(false, 0, 0, emptyMap(), emptyList())
        }
    }
}
