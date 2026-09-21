package io.legado.app.data.repository

import io.legado.app.domain.gateway.WebDavBackupGateway
import io.legado.app.domain.model.WebDavBackup
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.help.AppWebDav
import io.legado.app.help.storage.Backup
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class WebDavBackupRepository : WebDavBackupGateway {

    override val isJianGuoYun: Boolean
        get() = AppWebDav.isJianGuoYun

    override suspend fun syncConfig(): kotlin.Result<Unit> {
        return withContext(IO) {
            AppWebDav.upConfig()
        }
    }

    override suspend fun test(): Boolean {
        return withContext(IO) {
            AppWebDav.testWebDav()
        }
    }

    override suspend fun backup(): String {
        return withContext(IO) {
            Backup.backupLocked(appCtx, path = null, mode = "webdav")
        }
    }

    override suspend fun getBackupNames(): List<String> {
        return withContext(IO) {
            AppWebDav.getBackupNames()
        }
    }

    override suspend fun getLatestBackup(): WebDavBackup? {
        return withContext(IO) {
            AppWebDav.lastBackUp().getOrThrow()?.let {
                WebDavBackup(
                    name = it.displayName,
                    lastModify = it.lastModify
                )
            }
        }
    }

    override suspend fun restore(name: String): String {
        return withContext(IO) {
            AppWebDav.restoreWebDav(name)
        }
    }

    override suspend fun restore(name: String, mode: RestoreMode, categoryModes: Map<String, RestoreMode>, backupPassword: String?): String {
        return withContext(IO) {
            AppWebDav.restoreWebDav(name, mode, categoryModes, backupPassword)
        }
    }

    override suspend fun preview(name: String): RestorePreview = withContext(IO) {
        AppWebDav.previewWebDav(name)
    }
}
