package io.legado.app.ui.widget.components.importComponents

import androidx.compose.runtime.Immutable

// 单个条目的状态包装
data class ImportItemWrapper<T>(
    val data: T,// 具体的数据对象（替换规则、书源等）
    val oldData: T? = null,
    val isSelected: Boolean = true,
    val status: ImportStatus = ImportStatus.New, // 用于UI显示颜色
    val conflictReason: ImportConflictReason? = null,
    val normalizedUrl: String? = null,
    val host: String? = null,
    /** 弱重复提示；不会单独改变书源身份或最终写入决策。 */
    val searchUrlHint: String? = null,
    val decision: ImportDecision? = null,
    val localMetadata: ImportLocalMetadata? = null,
)

@Immutable
data class ImportLocalMetadata(
    val bookReferenceCount: Int = 0,
    val hasCookie: Boolean = false,
    val hasVariablesOrCache: Boolean = false,
)

enum class ImportDecision {
    KeepLocal,
    UseImport,
    KeepBoth,
    Skip,
}

enum class ImportConflictReason {
    NormalizedUrl,
    SameHost,
    InternalDuplicate,
    InvalidUrl,
    InvalidPattern,
    IncompleteImport,
    IncompleteLocal,
    ExistingUrl,
}

// 导入状态枚举
enum class ImportStatus {
    New,      // 新增 绿
    Update,   // 更新 黄
    Existing, // 已有 灰
    NormalizedConflict,
    HostConflict,
    InternalDuplicate,
    InvalidUrl,
    IncompleteImport,
    IncompleteLocal,
    Error     // 错误 红
}

// 整个导入流程的 UI State
sealed interface BaseImportUiState<out T> {
    data object Idle : BaseImportUiState<Nothing>
    data class Loading(val message: String? = null) : BaseImportUiState<Nothing>
    data class Error(val msg: String) : BaseImportUiState<Nothing>
    data class Success<T>(
        val source: String,
        val items: List<ImportItemWrapper<T>>,
        val version: Int = 0,
        // 导入配置项
        val keepOriginalName: Boolean = false,
        val keepOriginalGroup: Boolean = false,
        val keepOriginalEnable: Boolean = false,
        val customGroup: String? = null,
        val isAddGroup: Boolean = false
    ) : BaseImportUiState<T>
}
