package io.legado.app.domain.usecase

import io.legado.app.domain.gateway.WebDavBackupGateway
import io.legado.app.domain.model.WebDavBackup
import io.legado.app.domain.model.RestoreMode

class WebDavBackupUseCase(
    private val webDavBackupGateway: WebDavBackupGateway
) {

    val isJianGuoYun: Boolean
        get() = webDavBackupGateway.isJianGuoYun

    suspend fun refreshConfig() {
        webDavBackupGateway.syncConfig()
    }

    suspend fun test(): Boolean {
        webDavBackupGateway.syncConfig()
        return webDavBackupGateway.test()
    }

    suspend fun backup() {
        webDavBackupGateway.syncConfig()
        webDavBackupGateway.backup()
    }

    suspend fun getBackupNames(): List<String> {
        webDavBackupGateway.syncConfig()
        return webDavBackupGateway.getBackupNames()
    }

    suspend fun getLatestBackup(): WebDavBackup? {
        webDavBackupGateway.syncConfig()
        return webDavBackupGateway.getLatestBackup()
    }

    suspend fun restore(name: String) {
        webDavBackupGateway.syncConfig()
        webDavBackupGateway.restore(name)
    }

    suspend fun restore(name: String, mode: RestoreMode) {
        webDavBackupGateway.syncConfig()
        webDavBackupGateway.restore(name, mode)
    }
}
