package io.legado.app.help.storage

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.domain.model.BackupRestoreResult

data class BackupRestoreProgress(
    val stage: BackupRestoreStage,
    val current: Int = 0,
    val total: Int = 0,
    val warnings: Int = 0,
    val failures: Int = 0,
    val category: String? = null,
    val failedCategoryCounts: Map<String, Int> = emptyMap(),
)

object BackupRestoreProgressBus {
    private val _events = MutableSharedFlow<BackupRestoreProgress>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun emit(progress: BackupRestoreProgress) {
        _events.tryEmit(progress)
    }
}

object BackupRestoreResultBus {
    private val _events = MutableSharedFlow<BackupRestoreResult>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    @Volatile
    var latest: BackupRestoreResult? = null
        private set

    fun emit(result: BackupRestoreResult) {
        latest = result
        _events.tryEmit(result)
    }
}
