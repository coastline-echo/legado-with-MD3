package io.legado.app.domain.gateway

import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview

import io.legado.app.domain.model.WebDavBackup

interface WebDavBackupGateway {
    val isJianGuoYun: Boolean

    suspend fun syncConfig(): Result<Unit>
    suspend fun test(): Boolean
    suspend fun backup(): String
    suspend fun getBackupNames(): List<String>
    suspend fun getLatestBackup(): WebDavBackup?
    suspend fun restore(name: String): String
    suspend fun restore(name: String, mode: RestoreMode, categoryModes: Map<String, RestoreMode> = emptyMap(), backupPassword: String? = null): String
    suspend fun preview(name: String): RestorePreview
}
