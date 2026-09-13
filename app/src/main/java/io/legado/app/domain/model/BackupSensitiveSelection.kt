package io.legado.app.domain.model

/** 敏感信息按类别选择，清单只保存稳定标识，不保存原文。 */
enum class BackupSensitiveCategory {
    WEBDAV_CREDENTIALS,
    SERVER_CREDENTIALS,
    BOOK_SOURCE_AUTH,
    REQUEST_HEADERS,
    RSS_AUTH,
    TTS_API_KEYS,
    DIRECT_LINK_CREDENTIALS,
    OTHER,
}

data class BackupSensitiveSelection(
    val selected: Set<BackupSensitiveCategory> = BackupSensitiveCategory.entries.toSet(),
    val expanded: Set<BackupSensitiveCategory> = emptySet(),
) {
    fun includes(category: BackupSensitiveCategory): Boolean = category in selected

    fun toggle(category: BackupSensitiveCategory): BackupSensitiveSelection = copy(
        selected = if (includes(category)) selected - category else selected + category,
    )

    fun toggleExpanded(category: BackupSensitiveCategory): BackupSensitiveSelection = copy(
        expanded = if (category in expanded) expanded - category else expanded + category,
    )
}
