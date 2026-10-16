package io.legado.app.ui.config.backupConfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
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
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.FilledTonalButton
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.RestoreMode
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.button.PrimaryButton
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.checkBox.CheckboxItem
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

/** 分类恢复的独立预览页；弹窗只负责合并恢复的简短确认。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun RestorePreviewScreen(
    preview: BackupConfigDialog.RestorePreview,
    onIntent: (BackupConfigIntent) -> Unit,
) {
    BackHandler { onIntent(BackupConfigIntent.CloseRestorePreview) }
    val categories = (preview.preview.categories.keys +
        if (preview.mode == RestoreMode.CLEAR_THEN_RESTORE) emptySet()
        else preview.preview.missingCategories).distinct().sorted()
    val selectedCount = categories.count { it in preview.selectedCategories }
    val attentionCategories = (preview.preview.emptyCategories +
        preview.preview.missingCategories +
        preview.preview.backupIgnoredCategories +
        preview.preview.restoreIgnoredCategories).distinct().sorted()
    var attentionExpanded by rememberSaveable(preview.uri, preview.mode) { mutableStateOf(false) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.restore_preview_title),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = { onIntent(BackupConfigIntent.CloseRestorePreview) }) },
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                FilledTonalButton(
                    onClick = { onIntent(BackupConfigIntent.CloseRestorePreview) },
                    modifier = Modifier.weight(1f),
                ) {
                    Text(
                        text = stringResource(R.string.cancel),
                        style = LegadoTheme.typography.labelLarge,
                    )
                }
                PrimaryButton(
                    text = stringResource(R.string.restore),
                    enabled = selectedCount > 0,
                    onClick = {
                        if (preview.mode == RestoreMode.CLEAR_THEN_RESTORE) {
                            onIntent(BackupConfigIntent.RequestClearRestoreConfirmation(
                                preview.uri, preview.networkName, preview.selectedCategories, preview.localPassword,
                            ))
                        } else if (preview.networkName != null) {
                            onIntent(BackupConfigIntent.ConfirmNetworkRestorePreview(preview.networkName))
                        } else {
                            onIntent(BackupConfigIntent.ConfirmRestorePreview(preview.uri))
                        }
                    },
                    modifier = Modifier.weight(1f),
                )
            }
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = paddingValues.calculateBottomPadding() + 24.dp,
            ),
        ) {
            item {
                Row(
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = stringResource(
                            R.string.restore_result_source,
                            if (preview.networkName == null) stringResource(R.string.backup_source_local)
                            else stringResource(R.string.restore_preview_source_webdav, preview.networkName),
                        ),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    text = stringResource(R.string.restore_preview_mode, restoreModeTitle(preview.mode)),
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                Text(
                    text = stringResource(R.string.restore_preview_selected_summary, selectedCount, categories.size),
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
                if (preview.mode == RestoreMode.CLEAR_THEN_RESTORE) {
                    Text(
                        text = stringResource(R.string.restore_strategy_replace_desc),
                        color = LegadoTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                if (preview.preview.warnings.isNotEmpty() || attentionCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(
                            R.string.restore_preview_attention_summary,
                            attentionCategories.size,
                            preview.preview.warnings.size,
                        ),
                        color = LegadoTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    MediumTonalButton(
                        text = stringResource(
                            if (attentionExpanded) R.string.restore_preview_attention_collapse
                            else R.string.restore_preview_attention_view,
                        ),
                        onClick = { attentionExpanded = !attentionExpanded },
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                    if (attentionExpanded) {
                        if (preview.preview.emptyCategories.isNotEmpty()) Text(
                            text = stringResource(R.string.restore_preview_empty_categories, restoreCategoryTitles(preview.preview.emptyCategories)),
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (preview.preview.missingCategories.isNotEmpty()) Text(
                            text = stringResource(R.string.restore_preview_missing_categories, restoreCategoryTitles(preview.preview.missingCategories)),
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (preview.preview.backupIgnoredCategories.isNotEmpty()) Text(
                            text = stringResource(R.string.restore_preview_backup_ignored, restoreCategoryTitles(preview.preview.backupIgnoredCategories)),
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                        if (preview.preview.restoreIgnoredCategories.isNotEmpty()) Text(
                            text = stringResource(R.string.restore_preview_restore_ignored, restoreCategoryTitles(preview.preview.restoreIgnoredCategories)),
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
                if (preview.preview.sensitiveIncluded && !preview.preview.weakProtection) {
                    AppTextField(
                        value = preview.localPassword,
                        onValueChange = { onIntent(BackupConfigIntent.SetRestoreBackupPassword(it)) },
                        label = stringResource(R.string.restore_local_password_label),
                        backgroundColor = LegadoTheme.colorScheme.surface,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                Text(
                    text = stringResource(R.string.restore_preview_contents_title),
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            items(categories, key = { it }) { category ->
                val checked = category in preview.selectedCategories
                val available = category in preview.preview.categories
                Column(
                    modifier = Modifier
                        .padding(horizontal = 12.dp, vertical = 4.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(LegadoTheme.colorScheme.surfaceContainer)
                ) {
                    CheckboxItem(
                        title = restoreCategoryTitle(category),
                        checked = checked,
                        enabled = available,
                        onCheckedChange = { onIntent(BackupConfigIntent.ToggleRestorePreviewCategory(category)) },
                    )
                    Text(
                        text = stringResource(
                            R.string.restore_category_counts,
                            preview.preview.applicationCounts[category] ?: 0,
                            preview.preview.categories[category] ?: 0,
                        ),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 52.dp, bottom = 4.dp),
                    )
                    if (preview.mode == RestoreMode.BY_CATEGORY && available) {
                        val categoryMode = preview.categoryModes[category] ?: RestoreMode.KEEP_APPLICATION
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 52.dp, end = 12.dp, bottom = 8.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LegadoTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable {
                                    onIntent(BackupConfigIntent.SetRestorePreviewCategoryMode(
                                        category,
                                        if (categoryMode == RestoreMode.USE_BACKUP) RestoreMode.KEEP_APPLICATION
                                        else RestoreMode.USE_BACKUP,
                                    ))
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                        ) {
                            Text(
                                text = stringResource(
                                    if (categoryMode == RestoreMode.USE_BACKUP) {
                                        R.string.restore_category_use_backup
                                    } else {
                                        R.string.restore_category_keep_application
                                    },
                                ),
                                color = LegadoTheme.colorScheme.primary,
                            )
                            Text(
                                text = stringResource(
                                    if (categoryMode == RestoreMode.USE_BACKUP) {
                                        R.string.restore_use_backup_content_desc
                                    } else {
                                        R.string.restore_keep_application_desc
                                    },
                                ),
                                style = LegadoTheme.typography.bodySmall,
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun restoreModeTitle(mode: RestoreMode): String = stringResource(
    when (mode) {
        RestoreMode.KEEP_APPLICATION -> R.string.restore_strategy_merge
        RestoreMode.BY_CATEGORY -> R.string.restore_strategy_by_category
        RestoreMode.CLEAR_THEN_RESTORE -> R.string.restore_strategy_replace
        RestoreMode.USE_BACKUP -> R.string.restore_strategy_merge
    }
)
