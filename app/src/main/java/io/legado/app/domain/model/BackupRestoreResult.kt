package io.legado.app.domain.model

import java.util.UUID

enum class BackupRestoreOutcome {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    PARTIAL,
    FAILED,
    PRECONDITION_FAILED,
}

data class RestoreCategoryStats(
    val inserted: Int = 0,
    val updated: Int = 0,
    val skipped: Int = 0,
    val deleted: Int = 0,
    val failed: Int = 0,
)

data class BackupRestoreResult(
    val operationId: String = UUID.randomUUID().toString(),
    val outcome: BackupRestoreOutcome,
    val warnings: Int = 0,
    val failures: Int = 0,
    val failedCategories: Set<String> = emptySet(),
    /** 每个类别的失败项数；集合字段保留用于兼容旧调用方。 */
    val failedCategoryCounts: Map<String, Int> = emptyMap(),
    /** 每个失败类别对应的可展示原因，不包含凭据内容。 */
    val failedCategoryDetails: Map<String, List<String>> = emptyMap(),
    val categoryStats: Map<String, RestoreCategoryStats> = emptyMap(),
    val writtenConfigKeys: Int = 0,
    val ignoredConfigKeys: Int = 0,
    val unsupportedConfigKeys: Int = 0,
    val failedConfigKeys: Int = 0,
    val notSetConfigKeys: Int = 0,
    val ignoredConfigKeyNames: Set<String> = emptySet(),
    val unsupportedConfigKeyNames: Set<String> = emptySet(),
    val failedConfigKeyNames: Set<String> = emptySet(),
    val decryptionFailedConfigKeyNames: Set<String> = emptySet(),
    val notSetConfigKeyNames: Set<String> = emptySet(),
    /** 备份清单中的类别覆盖状态，供结果页和离线清单核对。 */
    val backupCategoryStatus: Map<String, String> = emptyMap(),
    val backupCategoryLabels: Map<String, String> = emptyMap(),
    val backupCategoryStatusDetails: Map<String, String> = emptyMap(),
    val backupEmptyCategories: Set<String> = emptySet(),
    val backupSensitiveStatus: Map<String, String> = emptyMap(),
    val backupConfigKeyStatus: Map<String, String> = emptyMap(),
    val backupConfigKeyLabels: Map<String, String> = emptyMap(),
    val backupFileNames: List<String> = emptyList(),
    val configFileMissing: Boolean = false,
    val configFileParseFailed: Boolean = false,
    val webDavUploadFailed: Boolean = false,
    val localBackupAttempted: Boolean = false,
    val localBackupCreated: Boolean = false,
    val localBackupFailed: Boolean = false,
    val webDavUploadAttempted: Boolean = false,
    val webDavDownloadFailed: Boolean = false,
    val unzipFailed: Boolean = false,
    val parseFailed: Boolean = false,
)
