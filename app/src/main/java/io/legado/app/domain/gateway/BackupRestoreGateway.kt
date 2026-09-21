package io.legado.app.domain.gateway

import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview

interface BackupRestoreGateway {
    suspend fun backup(path: String?, mode: String): String
    suspend fun restoreLocal(uri: String): String
    suspend fun restoreLocal(
        uri: String,
        mode: RestoreMode,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
        backupPassword: String? = null,
    ): String
    suspend fun previewLocal(uri: String): RestorePreview
}
