package io.legado.app.help.storage

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
                clearApplication = input.backupItemIds.isEmpty(),
                importItemIds = input.backupItemIds,
                deleteItemIds = input.applicationItemIds - input.backupItemIds,
                skippedByIgnore = false,
            )
            RestoreMode.KEEP_APPLICATION -> RestoreCategoryPlan(
                category = input.category,
                mode = mode,
                clearApplication = false,
                importItemIds = input.backupItemIds - input.applicationItemIds,
                deleteItemIds = emptySet(),
                skippedByIgnore = false,
            )
            RestoreMode.BY_CATEGORY -> plan(input, RestoreMode.KEEP_APPLICATION)
        }
    }
}
