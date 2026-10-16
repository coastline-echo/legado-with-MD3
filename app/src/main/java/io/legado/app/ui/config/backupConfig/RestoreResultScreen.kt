package io.legado.app.ui.config.backupConfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.CollapsibleHeader
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

/** 展示本次恢复实际处理结果；不读取备份清单，避免混淆备份内容和恢复结果。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestoreResultScreen(
    result: BackupRestoreResult,
    sourceLabel: String? = null,
    onBackClick: () -> Unit,
) {
    BackHandler(onBack = onBackClick)
    val failed = result.failedCategories
    val restored = result.categoryStats.filter { (category, stats) ->
        category !in failed && stats.inserted + stats.updated + stats.deleted > 0
    }
    val unchanged = result.categoryStats.filter { (category, stats) ->
        category !in failed && stats.inserted + stats.updated + stats.deleted == 0
    }
    val skippedByIgnore = (result.restoreSelectedCategories - result.categoryStats.keys - failed).sorted()
    val notSelected = (result.restoreAvailableCategories - result.restoreSelectedCategories).sorted()
    var showNotSelected by rememberSaveable(result.operationId) { mutableStateOf(false) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.restore_result_details_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 32.dp,
            ),
        ) {
            item {
                if (result.restoreAvailableCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.restore_preview_selected_summary,
                            result.restoreSelectedCategories.size,
                            result.restoreAvailableCategories.size,
                        ),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
                Text(
                    text = stringResource(
                        R.string.restore_result_details_summary,
                        restored.size,
                        unchanged.size,
                        failed.size,
                        skippedByIgnore.size,
                    ),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
                sourceLabel?.let {
                    Text(
                        text = it,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (restored.isNotEmpty()) {
                item { RestoreResultSectionTitle(R.string.restore_result_success_categories) }
                items(restored.toList(), key = { "restored:${it.first}" }) { (category, stats) ->
                    ManifestStatusRow(
                        name = restoreCategoryTitle(category),
                        status = stringResource(R.string.restore_result_status_success),
                        detail = restoreStatsText(stats.inserted, stats.updated, stats.deleted),
                    )
                }
            }
            if (unchanged.isNotEmpty()) {
                item { RestoreResultSectionTitle(R.string.restore_result_unchanged_categories_title) }
                items(unchanged.toList(), key = { "unchanged:${it.first}" }) { (category, _) ->
                    ManifestStatusRow(
                        name = restoreCategoryTitle(category),
                        status = stringResource(R.string.restore_result_status_unchanged),
                        detail = "",
                    )
                }
            }
            if (failed.isNotEmpty()) {
                item { RestoreResultSectionTitle(R.string.restore_result_failures_title) }
                items(failed.toList(), key = { "failed:$it" }) { category ->
                    val details = result.failedCategoryDetails[category].orEmpty().distinct()
                        .map { readableRestoreFailure(it) }
                    ManifestStatusRow(
                        name = restoreCategoryTitle(category),
                        status = stringResource(R.string.restore_result_status_failed),
                        detail = details.joinToString(stringResource(R.string.backup_config_summary_separator))
                            .ifBlank { stringResource(R.string.restore_result_failure_unknown) },
                    )
                }
            }
            if (skippedByIgnore.isNotEmpty()) {
                item { RestoreResultSectionTitle(R.string.restore_result_ignored_categories_title) }
                items(skippedByIgnore, key = { "ignored:$it" }) { category ->
                    ManifestStatusRow(
                        name = restoreCategoryTitle(category),
                        status = stringResource(R.string.restore_result_status_ignored),
                        detail = "",
                    )
                }
            }
            if (notSelected.isNotEmpty()) {
                item {
                    CollapsibleHeader(
                        isCollapsed = !showNotSelected,
                        title = stringResource(R.string.restore_result_not_selected_title, notSelected.size),
                        onToggle = { showNotSelected = !showNotSelected },
                    )
                }
                if (showNotSelected) {
                    items(notSelected, key = { "not-selected:$it" }) { category ->
                        ManifestStatusRow(
                            name = restoreCategoryTitle(category),
                            status = stringResource(R.string.restore_result_status_not_selected),
                            detail = "",
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun restoreStatsText(inserted: Int, updated: Int, deleted: Int): String =
    listOfNotNull(
        inserted.takeIf { it > 0 }?.let { stringResource(R.string.restore_result_added, it) },
        updated.takeIf { it > 0 }?.let { stringResource(R.string.restore_result_updated, it) },
        deleted.takeIf { it > 0 }?.let { stringResource(R.string.restore_result_deleted, it) },
    ).joinToString(stringResource(R.string.backup_config_summary_separator))

@Composable
private fun RestoreResultSectionTitle(@androidx.annotation.StringRes resId: Int) {
    Text(
        text = stringResource(resId),
        color = LegadoTheme.colorScheme.primary,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
}

