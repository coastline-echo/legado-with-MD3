package io.legado.app.domain.model

/** 恢复类别的用户选择。 */
enum class RestoreMode {
    /** 将备份数据合并到应用：新增备份记录并更新同身份记录，保留应用独有记录。 */
    USE_BACKUP,
    /** 仅补充应用中不存在的记录，保留已有记录内容。 */
    KEEP_APPLICATION,
    BY_CATEGORY,
    /** 用户明确确认后，先清空选定类别再导入备份。 */
    CLEAR_THEN_RESTORE,
}
