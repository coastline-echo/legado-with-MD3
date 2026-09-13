package io.legado.app.domain.model

/** 恢复写入前展示的结构化摘要。 */
data class RestorePreview(
    val legacyFormat: Boolean,
    val fileCount: Int,
    val resourceCount: Int,
    val categories: Map<String, Int>,
    val warnings: List<String>,
)
