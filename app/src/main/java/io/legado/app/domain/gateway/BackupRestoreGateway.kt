package io.legado.app.domain.gateway

import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview

interface BackupRestoreGateway {
    suspend fun backup(path: String?, mode: String)
    suspend fun restoreLocal(uri: String)
    suspend fun restoreLocal(uri: String, mode: RestoreMode) = restoreLocal(uri)
    suspend fun previewLocal(uri: String): RestorePreview
}
