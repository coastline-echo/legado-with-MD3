package io.legado.app.data.repository

import androidx.core.net.toUri
import io.legado.app.domain.gateway.BackupRestoreGateway
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.Restore
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.withContext
import splitties.init.appCtx

class BackupRestoreRepository : BackupRestoreGateway {

    override suspend fun backup(path: String?, mode: String) {
        withContext(IO) {
            Backup.backupLocked(appCtx, path, mode)
        }
    }

    override suspend fun restoreLocal(uri: String) {
        withContext(IO) {
            Restore.restore(appCtx, uri.toUri())
        }
    }

    override suspend fun restoreLocal(uri: String, mode: RestoreMode) {
        withContext(IO) {
            Restore.restore(appCtx, uri.toUri(), mode)
        }
    }

    override suspend fun previewLocal(uri: String): RestorePreview = withContext(IO) {
        Restore.preview(appCtx, uri.toUri())
    }
}
