package io.legado.app.domain.model

/** 恢复写入前展示的结构化摘要。 */
data class RestorePreview(
    val legacyFormat: Boolean,
    val fileCount: Int,
    val resourceCount: Int,
    val categories: Map<String, Int>,
    val warnings: List<String>,
    val sensitiveIncluded: Boolean = false,
    val weakProtection: Boolean = false,
    val sensitiveItems: Set<String> = emptySet(),
    val applicationCounts: Map<String, Int> = emptyMap(),
    val emptyCategories: Set<String> = emptySet(),
    val missingCategories: Set<String> = emptySet(),
    val ignoredCategories: Set<String> = emptySet(),
    val backupIgnoredCategories: Set<String> = emptySet(),
    val restoreIgnoredCategories: Set<String> = emptySet(),
    val conflictCounts: Map<String, Int> = emptyMap(),
)
