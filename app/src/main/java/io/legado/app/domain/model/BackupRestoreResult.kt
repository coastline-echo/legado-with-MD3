package io.legado.app.domain.model

enum class BackupRestoreOutcome {
    SUCCESS,
    SUCCESS_WITH_WARNINGS,
    PARTIAL,
    FAILED,
}

data class BackupRestoreResult(
    val outcome: BackupRestoreOutcome,
    val warnings: Int = 0,
    val failures: Int = 0,
    val failedCategories: Set<String> = emptySet(),
    /** 每个类别的失败项数；集合字段保留用于兼容旧调用方。 */
    val failedCategoryCounts: Map<String, Int> = emptyMap(),
    val writtenConfigKeys: Int = 0,
    val ignoredConfigKeys: Int = 0,
    val unsupportedConfigKeys: Int = 0,
    val failedConfigKeys: Int = 0,
    val notSetConfigKeys: Int = 0,
    val ignoredConfigKeyNames: Set<String> = emptySet(),
    val unsupportedConfigKeyNames: Set<String> = emptySet(),
    val failedConfigKeyNames: Set<String> = emptySet(),
    val notSetConfigKeyNames: Set<String> = emptySet(),
    val configFileMissing: Boolean = false,
    val configFileParseFailed: Boolean = false,
    val webDavUploadFailed: Boolean = false,
)
