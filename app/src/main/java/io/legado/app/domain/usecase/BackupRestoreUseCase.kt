package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.BackupRestoreGateway
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview

class BackupRestoreUseCase(
    private val gateway: BackupRestoreGateway,
) {
    suspend fun backup(path: String?, mode: String) {
        gateway.backup(path, mode)
    }

    suspend fun restoreLocal(uri: String) {
        gateway.restoreLocal(uri)
    }

    suspend fun restoreLocal(uri: String, mode: RestoreMode, selectedCategories: Set<String> = emptySet(), categoryModes: Map<String, RestoreMode> = emptyMap(), backupPassword: String? = null) {
        gateway.restoreLocal(uri, mode, selectedCategories, categoryModes, backupPassword)
    }

    suspend fun previewLocal(uri: String): RestorePreview = gateway.previewLocal(uri)
}
