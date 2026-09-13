package io.legado.app.help.storage

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

data class BackupRestoreProgress(
    val stage: String,
    val current: Int = 0,
    val total: Int = 0,
    val warnings: Int = 0,
    val failures: Int = 0,
)

object BackupRestoreProgressBus {
    private val _events = MutableSharedFlow<BackupRestoreProgress>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun emit(progress: BackupRestoreProgress) {
        _events.tryEmit(progress)
    }
}
