package io.legado.app.help.storage

import io.legado.app.domain.model.RestoreCategoryStats
import io.legado.app.domain.model.RestoreMode

data class RestoreCategoryInput(
    val category: String,
    val backupPresent: Boolean,
    val backupItemIds: Set<String> = emptySet(),
    val applicationItemIds: Set<String> = emptySet(),
    val ignored: Boolean = false,
)

data class RestoreCategoryPlan(
    val category: String,
    val mode: RestoreMode?,
    val backupItemIds: Set<String>,
    val applicationItemIds: Set<String>,
    val clearApplication: Boolean,
    val importItemIds: Set<String>,
    val deleteItemIds: Set<String>,
    val skippedByIgnore: Boolean,
)

/** 统一表达缺失类别、明确空类别及覆盖/保留语义，供数据库和配置恢复复用。 */
object RestoreCategoryPlanner {

    fun planByCategory(
        inputs: Collection<RestoreCategoryInput>,
        modes: Map<String, RestoreMode>,
        defaultMode: RestoreMode = RestoreMode.KEEP_APPLICATION,
    ): List<RestoreCategoryPlan> = inputs.map { input ->
        plan(input, modes[input.category] ?: defaultMode)
    }

    fun plan(input: RestoreCategoryInput, mode: RestoreMode): RestoreCategoryPlan {
        if (input.ignored || !input.backupPresent) {
            return RestoreCategoryPlan(
                category = input.category,
                mode = null,
                backupItemIds = input.backupItemIds,
                applicationItemIds = input.applicationItemIds,
                clearApplication = false,
                importItemIds = emptySet(),
                deleteItemIds = emptySet(),
                skippedByIgnore = input.ignored,
            )
        }
        return when (mode) {
            RestoreMode.USE_BACKUP -> RestoreCategoryPlan(
                category = input.category,
                mode = mode,
                backupItemIds = input.backupItemIds,
                applicationItemIds = input.applicationItemIds,
                // 空备份只表示“本次没有可导入数据”，不在普通恢复中隐式清空应用数据。
                // 清空仅由用户明确选择清空策略并完成二次确认后执行。
                clearApplication = false,
                importItemIds = input.backupItemIds,
                // 普通恢复不得删除应用独有记录；DAO 负责按稳定身份新增或更新。
                deleteItemIds = emptySet(),
                skippedByIgnore = false,
            )
            RestoreMode.KEEP_APPLICATION -> RestoreCategoryPlan(
                category = input.category,
                mode = mode,
                backupItemIds = input.backupItemIds,
                applicationItemIds = input.applicationItemIds,
                clearApplication = false,
                importItemIds = input.backupItemIds - input.applicationItemIds,
                deleteItemIds = emptySet(),
                skippedByIgnore = false,
            )
            RestoreMode.BY_CATEGORY -> plan(input, RestoreMode.KEEP_APPLICATION)
            RestoreMode.CLEAR_THEN_RESTORE -> RestoreCategoryPlan(
                category = input.category,
                mode = mode,
                backupItemIds = input.backupItemIds,
                applicationItemIds = input.applicationItemIds,
                // 空类别仍表示“没有可恢复数据”，不能借清空模式隐式清空应用。
                clearApplication = input.backupItemIds.isNotEmpty(),
                importItemIds = input.backupItemIds,
                deleteItemIds = emptySet(),
                skippedByIgnore = false,
            )
        }
    }
}

fun RestoreCategoryPlan.toStats(failed: Int = 0): RestoreCategoryStats {
    if (mode == null) {
        return RestoreCategoryStats(
            skipped = backupItemIds.size,
            failed = failed,
        )
    }
    // 普通恢复同样会更新应用中已存在的稳定身份；此前只统计新增，
    // 导致书架已有记录时结果全部显示为 0 或“跳过”。
    val updated = when (mode) {
        RestoreMode.CLEAR_THEN_RESTORE -> 0
        RestoreMode.USE_BACKUP,
        RestoreMode.KEEP_APPLICATION,
        RestoreMode.BY_CATEGORY -> backupItemIds.intersect(applicationItemIds).size
    }
    val inserted = when (mode) {
        RestoreMode.CLEAR_THEN_RESTORE -> backupItemIds.size
        else -> (backupItemIds - applicationItemIds).size
    }
    val skipped = 0
    return RestoreCategoryStats(
        inserted = inserted,
        updated = updated,
        skipped = skipped,
        deleted = if (clearApplication) applicationItemIds.size else 0,
        failed = failed,
    )
}
