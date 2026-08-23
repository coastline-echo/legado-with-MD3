package io.legado.app.ui.book.source.manage

import android.net.Uri
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.Stable
import io.legado.app.data.entities.BookSource
import io.legado.app.domain.usecase.BookSourceMatchType
import io.legado.app.domain.usecase.BookSourceRecommendationReason
import io.legado.app.domain.usecase.BookSourceRuleProfile
import io.legado.app.ui.widget.components.importComponents.BaseImportUiState
import io.legado.app.ui.widget.components.importComponents.ImportStatus
import io.legado.app.ui.widget.components.importComponents.ImportDecision
import io.legado.app.ui.widget.components.list.InteractionState
import io.legado.app.ui.widget.components.list.ListUiState
import io.legado.app.ui.widget.components.list.SelectableItem
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.ImmutableSet
import kotlinx.collections.immutable.persistentListOf
import kotlinx.collections.immutable.persistentSetOf

@Immutable
data class BookSourceItemUi(
    override val id: String,
    val domain: String,
    val name: String,
    val group: String?,
    val enabled: Boolean,
    val enabledExplore: Boolean,
    val hasLoginUrl: Boolean,
    val hasExploreUrl: Boolean,
    val checkMessage: String? = null,
    val customOrder: Int,
) : SelectableItem<String> {
}

@Immutable
data class BookSourceCheckOptionsUi(
    val timeoutSeconds: Long = 180,
    val checkSearch: Boolean = true,
    val checkDiscovery: Boolean = true,
    val checkInfo: Boolean = true,
    val checkCategory: Boolean = true,
    val checkContent: Boolean = true,
)

@Immutable
data class BookSourceDedupSourceUi(
    val sourceUrl: String,
    val name: String,
    val referencedBookCount: Int,
    val score: Int,
    val reasons: ImmutableList<BookSourceRecommendationReason>,
    val hasCookie: Boolean,
    val hasVariablesOrCache: Boolean,
    val rules: BookSourceRuleProfile,
    val retained: Boolean = false,
)

@Immutable
data class BookSourceDedupGroupUi(
    val matchType: BookSourceMatchType,
    val sources: ImmutableList<BookSourceDedupSourceUi>,
    val recommendedSourceUrl: String,
    val ignored: Boolean = false,
)

@Immutable
data class BookSourceDeletePreviewUi(
    val sourceUrls: ImmutableSet<String> = persistentSetOf(),
    val referencedBookCount: Int = 0,
    val hasCookie: Boolean = false,
    val hasVariablesOrCache: Boolean = false,
    val recommendedTargetSourceUrl: String? = null,
    val targetSourceUrls: ImmutableList<String> = persistentListOf(),
    val loading: Boolean = false,
)

@Stable
data class BookSourceUiState(
    override val items: ImmutableList<BookSourceItemUi> = persistentListOf(),
    override val selectedIds: ImmutableSet<String> = persistentSetOf(),
    override val searchKey: String = "",
    val groupFilterName: String? = null,
    val activeFilter: String? = null,
    val groups: ImmutableList<String> = persistentListOf(),
    val sort: BookSourceSort = BookSourceSort.Default,
    val sortAscending: Boolean = true,
    val groupByDomain: Boolean = false,
    val importState: BaseImportUiState<BookSource> = BaseImportUiState.Idle,
    val checkProgress: String? = null,
    val checkOptions: BookSourceCheckOptionsUi = BookSourceCheckOptionsUi(),
    // 导入过程由独立的批量弹窗展示，不能让书源列表进入页面级 loading。
    val interaction: InteractionState = InteractionState(),
    val dedupGroups: ImmutableList<BookSourceDedupGroupUi> = persistentListOf(),
    val dedupScanning: Boolean = false,
    val deletePreview: BookSourceDeletePreviewUi? = null,
) : ListUiState<BookSourceItemUi> {
    override val isSearch get() = interaction.isSearchMode
    override val isLoading get() = interaction.isLoading
}

sealed interface BookSourceIntent {
    data class SetSearchMode(val enabled: Boolean) : BookSourceIntent
    data class SetSearchQuery(val query: String) : BookSourceIntent
    data class SetSelection(val ids: Set<String>) : BookSourceIntent
    data class ToggleSelection(val id: String) : BookSourceIntent
    data class SetFilter(val filter: String?) : BookSourceIntent
    data class SetSort(val sort: BookSourceSort) : BookSourceIntent
    data object ToggleSortDirection : BookSourceIntent
    data object ToggleGroupByDomain : BookSourceIntent
    data class SetEnabled(val id: String, val enabled: Boolean) : BookSourceIntent
    data class SetEnabledForSelection(val ids: Set<String>, val enabled: Boolean) : BookSourceIntent
    data class SetExploreEnabled(val ids: Set<String>, val enabled: Boolean) : BookSourceIntent
    data class Delete(val ids: Set<String>) : BookSourceIntent
    data class PrepareDelete(val ids: Set<String>) : BookSourceIntent
    data object CancelDelete : BookSourceIntent
    data object ConfirmDirectDelete : BookSourceIntent
    data object ConfirmChangeSourceDelete : BookSourceIntent
    data class SelectDeleteTarget(val sourceUrl: String) : BookSourceIntent
    data class MoveToEdge(val ids: Set<String>, val toTop: Boolean) : BookSourceIntent
    data class MoveItem(val from: Int, val to: Int) : BookSourceIntent
    data object SaveSortOrder : BookSourceIntent
    data class CommitSortOrder(
        val ids: List<String>,
        val ascending: Boolean,
    ) : BookSourceIntent

    data class AddToGroup(val ids: Set<String>, val group: String) : BookSourceIntent
    data class RemoveFromGroup(val ids: Set<String>, val group: String) : BookSourceIntent
    data class UpdateGroup(val old: String, val new: String) : BookSourceIntent
    data class DeleteGroup(val group: String) : BookSourceIntent
    data class CheckSelectedInterval(val ids: Set<String>) : BookSourceIntent
    data class StartCheck(
        val ids: Set<String>,
        val keyword: String,
        val options: BookSourceCheckOptionsUi,
    ) : BookSourceIntent

    data class UpdateCheckOptions(val options: BookSourceCheckOptionsUi) : BookSourceIntent
    data object CancelCheck : BookSourceIntent
    data class Import(val text: String) : BookSourceIntent
    data class Export(val uri: Uri, val ids: Set<String>) : BookSourceIntent
    data class Upload(val ids: Set<String>) : BookSourceIntent
    data class ToggleImportItem(val index: Int) : BookSourceIntent
    data class ToggleImportAll(val selected: Boolean) : BookSourceIntent
    data class SetImportDecision(val index: Int, val decision: ImportDecision) : BookSourceIntent
    data class SetImportDecisionForAll(val decision: ImportDecision) : BookSourceIntent
    data class UpdateImportItem(val index: Int, val source: BookSource) : BookSourceIntent
    data class SelectImportStatus(val status: ImportStatus) : BookSourceIntent
    data class SetImportKeepName(val enabled: Boolean) : BookSourceIntent
    data class SetImportKeepGroup(val enabled: Boolean) : BookSourceIntent
    data class SetImportKeepEnable(val enabled: Boolean) : BookSourceIntent
    data class SetImportCustomGroup(val group: String?, val add: Boolean) : BookSourceIntent
    data object CancelImport : BookSourceIntent
    data object SaveImportedSources : BookSourceIntent
    data object ScanDuplicateSources : BookSourceIntent
    data class ToggleDedupRetained(val sourceUrl: String) : BookSourceIntent
    data class IgnoreDedupGroup(val sourceUrl: String) : BookSourceIntent
}

sealed interface BookSourceEffect {
    data class StartCheck(
        val ids: Set<String>,
        val keyword: String,
    ) : BookSourceEffect

    data object CancelCheck : BookSourceEffect

    data class ImportFinished(val summary: String) : BookSourceEffect

    data class ShowSnackbar(
        val message: String,
        val actionLabel: String? = null,
        val url: String? = null,
    ) : BookSourceEffect
}
