package io.legado.app.help.storage

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.domain.model.BackupRestoreResult
import java.util.concurrent.ConcurrentHashMap

data class BackupRestoreProgress(
    val operationId: String = "",
    val stage: BackupRestoreStage,
    val current: Int = 0,
    val total: Int = 0,
    val warnings: Int = 0,
    val failures: Int = 0,
    val category: String? = null,
    val failedCategoryCounts: Map<String, Int> = emptyMap(),
)

object BackupRestoreProgressBus {
    // 进度不回放，避免新操作开始前收到旧 operationId 的阶段并污染 Loading。
    // 最终成功/失败状态由 BackupRestoreResultBus 按 operationId 保证可见。
    private val _events = MutableSharedFlow<BackupRestoreProgress>(extraBufferCapacity = 16)
    val events = _events.asSharedFlow()

    fun emit(progress: BackupRestoreProgress) {
        _events.tryEmit(progress)
    }
}

object BackupRestoreResultBus {
    // 结果同时按 operationId 缓存；replay 保障晚订阅者仍能收到最近一次结果。
    private val _events = MutableSharedFlow<BackupRestoreResult>(replay = 1, extraBufferCapacity = 16)
    val events = _events.asSharedFlow()
    // 使用 ConcurrentHashMap 存储每个操作的结果，避免全局 latest 被复用
    private val results = ConcurrentHashMap<String, BackupRestoreResult>()

    fun emit(result: BackupRestoreResult) {
        results[result.operationId] = result
        _events.tryEmit(result)
    }

    fun getResult(operationId: String): BackupRestoreResult? = results[operationId]

    fun removeResult(operationId: String) {
        results.remove(operationId)
    }

}
