package io.legado.app.ui.config.backupConfig

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import io.legado.app.R
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.domain.model.BackupSensitiveCategory
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.CollapsibleHeader
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton

/** 结果页的临时子页面；返回后仍保留原结果弹窗，不复制备份业务状态。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun BackupManifestScreen(
    result: BackupRestoreResult,
    onBackClick: () -> Unit,
) {
    BackHandler(onBack = onBackClick)
    var showAllCategories by remember { mutableStateOf(false) }
    var showNotSetConfig by remember { mutableStateOf(false) }
    var showEmptySensitive by remember { mutableStateOf(false) }
    var onlyIssues by remember { mutableStateOf(false) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val issueStatuses = setOf("不支持", "写入失败", "未备份")
    val emptyCategories = result.backupEmptyCategories + result.backupCategoryStatus
        .filterValues { it == "无内容" }.keys
    val allCategories = result.backupCategoryStatus.entries.toList()
    val categories = allCategories.filter { (category, status) ->
        if (onlyIssues) status in issueStatuses else category !in emptyCategories
    }
    val emptyCategoryEntries = if (!onlyIssues && showAllCategories) {
        allCategories.filter { (category, _) -> category in emptyCategories }
    } else emptyList()
    val writtenConfig = result.backupConfigKeyStatus.entries.filter { it.value == "已写入" }
    val excludedConfig = result.backupConfigKeyStatus.entries.filter { (key, status) ->
        status == "已忽略" && key != "__local_ui_status_migrated_to_settings"
    }
    val issueConfig = result.backupConfigKeyStatus.entries.filter { it.value in issueStatuses }
    val notSetConfig = result.backupConfigKeyStatus.entries.filter { it.value == "未设置" }
    val visibleConfig = if (onlyIssues) issueConfig else writtenConfig
    val knownConfig = visibleConfig.filter { (key, _) ->
        result.backupConfigKeyLabels[key]?.let { it != "其他配置项" && it != key } == true
    }.sortedBy { result.backupConfigKeyLabels[it.key] }
    val otherConfig = visibleConfig.filterNot { it in knownConfig }.groupBy { it.value }
    val sensitiveStatuses = result.backupSensitiveStatus.entries.filter { (_, status) ->
        if (onlyIssues) {
            status.contains("失败") || status.contains("失敗") || status.contains("failed", ignoreCase = true)
        } else {
            status != "无可写入内容" && status != "無可寫入內容" &&
                !status.equals("no content", ignoreCase = true)
        }
    }
    val hiddenSensitiveCount = if (onlyIssues) 0 else {
        (result.backupSensitiveStatus.size - sensitiveStatuses.size).coerceAtLeast(0)
    }
    val hiddenSensitive = if (onlyIssues) emptyList() else {
        result.backupSensitiveStatus.entries.filterNot { it in sensitiveStatuses }
    }

    AppScaffold(
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.backup_manifest_title),
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
                MediumTonalButton(
                    text = stringResource(
                        if (onlyIssues) R.string.backup_manifest_show_normal
                        else R.string.backup_manifest_only_issues
                    ),
                    onClick = {
                        if (onlyIssues) {
                            onlyIssues = false
                        } else {
                            onlyIssues = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            if (onlyIssues && result.warnings > 0) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_warnings, result.warnings),
                        color = LegadoTheme.colorScheme.error,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                    Text(
                        text = stringResource(R.string.backup_manifest_warnings_detail),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 0.dp),
                    )
                }
            }
            if (onlyIssues && result.warnings == 0 && categories.isEmpty() && knownConfig.isEmpty() &&
                otherConfig.isEmpty() && sensitiveStatuses.isEmpty()
            ) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_no_issues),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (categories.isNotEmpty() || emptyCategoryEntries.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (onlyIssues) R.string.backup_manifest_categories
                            else R.string.backup_manifest_non_empty_categories_title,
                        ),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(categories, key = { "category:${it.key}" }) { (category, status) ->
                val storedName = result.backupCategoryLabels[category].orEmpty()
                val name = if (storedName.isNotBlank()) {
                    storedName
                } else {
                    stringResource(R.string.restore_category_other)
                }
                val displayStatus = status
                val detail = result.backupCategoryStatusDetails[category].orEmpty()
                val count = result.backupCategoryCounts[category] ?: 0
                val details = buildList {
                    if (count > 0) add(stringResource(R.string.backup_manifest_category_count, count))
                    if (detail.isNotBlank()) add(detail)
                }
                ManifestStatusRow(
                    name = name,
                    status = displayStatus,
                    detail = details.joinToString(stringResource(R.string.backup_config_summary_separator)),
                )
            }
            if (!onlyIssues && emptyCategories.isNotEmpty()) {
                item {
                    MediumTonalButton(
                        text = stringResource(
                            if (showAllCategories) R.string.backup_manifest_hide_empty
                            else R.string.backup_manifest_show_empty
                        ),
                        onClick = { showAllCategories = !showAllCategories },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (emptyCategoryEntries.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            R.string.backup_manifest_empty_categories_title,
                            emptyCategoryEntries.size,
                        ),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            items(emptyCategoryEntries, key = { "empty-category:${it.key}" }) { (category, _) ->
                val name = result.backupCategoryLabels[category].orEmpty().ifBlank {
                    stringResource(R.string.restore_category_other)
                }
                ManifestStatusRow(
                    name = name,
                    status = stringResource(R.string.backup_manifest_empty_category),
                    detail = "",
                    muted = true,
                )
            }
            if (visibleConfig.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(
                            if (onlyIssues) R.string.backup_manifest_issue_config_title
                            else R.string.backup_manifest_saved_config_title,
                            visibleConfig.size,
                        ),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(knownConfig, key = { "config:${it.key}" }) { (key, status) ->
                    ManifestStatusRow(
                        name = result.backupConfigKeyLabels.getValue(key),
                        status = status,
                        detail = "",
                    )
                }
                otherConfig.forEach { (status, entries) ->
                    item(key = "other:$status") {
                    ManifestStatusRow(
                        name = stringResource(R.string.backup_config_detail_other),
                        status = status,
                        detail = stringResource(R.string.backup_manifest_other_config_count, "", entries.size),
                    )
                    }
                }
            }
            if (!onlyIssues && excludedConfig.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_excluded_config_title, excludedConfig.size),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(excludedConfig, key = { "excluded:${it.key}" }) { (key, _) ->
                    val label = result.backupConfigKeyLabels[key].orEmpty()
                    Text(
                        text = if (label.isBlank()) stringResource(R.string.backup_config_detail_other) else label,
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (!onlyIssues && notSetConfig.isNotEmpty()) {
                item {
                    CollapsibleHeader(
                        isCollapsed = !showNotSetConfig,
                        title = stringResource(R.string.backup_manifest_not_set, notSetConfig.size),
                        onToggle = { showNotSetConfig = !showNotSetConfig },
                    )
                }
                if (showNotSetConfig) {
                    items(notSetConfig.filter { (key, _) ->
                        result.backupConfigKeyLabels[key]?.let { it != "其他配置项" && it != key } == true
                    }, key = { "not-set:${it.key}" }) { (key, _) ->
                        Text(
                            text = result.backupConfigKeyLabels.getValue(key),
                            color = LegadoTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                    val unnamedCount = notSetConfig.count { (key, _) ->
                        result.backupConfigKeyLabels[key]?.let { it == "其他配置项" || it == key } != false
                    }
                    if (unnamedCount > 0) {
                        item {
                            Text(
                                text = stringResource(R.string.backup_manifest_other_unset_count, unnamedCount),
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                            )
                        }
                    }
                }
            }
            if (sensitiveStatuses.isNotEmpty() || hiddenSensitiveCount > 0) {
                item {
                    Text(
                        text = stringResource(R.string.backup_sensitive_title),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(sensitiveStatuses, key = { "sensitive:${it.key}" }) { (id, status) ->
                    val category = BackupSensitiveCategory.entries.firstOrNull { it.name == id }
                    val name = if (category == null) {
                        stringResource(R.string.backup_sensitive_other)
                    } else {
                        sensitiveCategoryTitle(category)
                    }
                    ManifestStatusRow(name = name, status = status, detail = "")
                }
            }
            if (!onlyIssues && hiddenSensitiveCount > 0) {
                item {
                    CollapsibleHeader(
                        isCollapsed = !showEmptySensitive,
                        title = stringResource(
                            if (showEmptySensitive) R.string.backup_sensitive_empty_collapse
                            else R.string.backup_sensitive_empty_summary,
                            hiddenSensitiveCount,
                        ),
                        onToggle = { showEmptySensitive = !showEmptySensitive },
                    )
                }
            }
            if (!onlyIssues && showEmptySensitive) {
                items(hiddenSensitive, key = { "sensitive-empty:${it.key}" }) { (id, status) ->
                    val category = BackupSensitiveCategory.entries.firstOrNull { it.name == id }
                    ManifestStatusRow(
                        name = category?.let { sensitiveCategoryTitle(it) }
                            ?: stringResource(R.string.backup_sensitive_other),
                        status = status,
                        detail = "",
                        muted = true,
                    )
                }
            }
        }
    }
}

@Composable
internal fun ManifestStatusRow(
    name: String,
    status: String,
    detail: String,
    muted: Boolean = false,
) {
    val contentColor = if (muted) {
        LegadoTheme.colorScheme.onSurfaceVariant
    } else {
        LegadoTheme.colorScheme.onSurface
    }
    val statusColor = when (status) {
        "已备份", "已写入", "已加密写入" -> LegadoTheme.colorScheme.primary
        "未备份", "写入失败", "不支持" -> LegadoTheme.colorScheme.error
        else -> LegadoTheme.colorScheme.onSurfaceVariant
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 5.dp),
    ) {
        Row(modifier = Modifier.fillMaxWidth()) {
            Text(text = name, color = contentColor, modifier = Modifier.weight(0.62f))
            Text(text = status, color = statusColor, modifier = Modifier.weight(0.38f))
        }
        if (detail.isNotBlank()) {
            Text(
                text = detail,
                color = LegadoTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}
