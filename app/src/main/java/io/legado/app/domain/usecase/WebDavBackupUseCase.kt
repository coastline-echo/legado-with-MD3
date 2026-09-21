package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.WebDavBackupGateway
import io.legado.app.domain.model.WebDavBackup
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.exception.NoStackTraceException

class WebDavBackupUseCase(
    private val webDavBackupGateway: WebDavBackupGateway
) {

    val isJianGuoYun: Boolean
        get() = webDavBackupGateway.isJianGuoYun

    suspend fun refreshConfig(): kotlin.Result<Unit> {
        return webDavBackupGateway.syncConfig()
    }

    suspend fun test(): Boolean {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.test()
    }

    suspend fun backup(): String {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.backup()
    }

    suspend fun getBackupNames(): List<String> {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.getBackupNames()
    }

    suspend fun getLatestBackup(): WebDavBackup? {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.getLatestBackup()
    }

    suspend fun restore(name: String): String {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.restore(name)
    }

    suspend fun restore(name: String, mode: RestoreMode, categoryModes: Map<String, RestoreMode> = emptyMap(), backupPassword: String? = null): String {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.restore(name, mode, categoryModes, backupPassword)
    }

    suspend fun preview(name: String): RestorePreview {
        val syncResult = webDavBackupGateway.syncConfig()
        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
        return webDavBackupGateway.preview(name)
    }
}