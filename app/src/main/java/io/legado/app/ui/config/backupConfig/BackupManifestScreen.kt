package io.legado.app.ui.config.backupConfig

import androidx.activity.compose.BackHandler
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
    var showFiles by remember { mutableStateOf(false) }
    var onlyIssues by remember { mutableStateOf(false) }
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    val issueStatuses = setOf("已忽略", "不支持", "写入失败")
    val categories = result.backupCategoryStatus.entries.filter { (_, status) ->
        if (onlyIssues) status in issueStatuses else showAllCategories || status != "未写入"
    }
    val knownConfig = result.backupConfigKeyStatus.entries.filter { (key, status) ->
        status != "未设置" && (!onlyIssues || status in issueStatuses) &&
            result.backupConfigKeyLabels[key]?.let { it != "其他配置项" && it != key } == true
    }.sortedBy { result.backupConfigKeyLabels[it.key] }
    val notSetConfig = result.backupConfigKeyStatus.entries.filter { !onlyIssues && it.value == "未设置" }
    val otherConfig = result.backupConfigKeyStatus.entries
        .filter { it.value != "未设置" && (!onlyIssues || it.value in issueStatuses) }
        .filterNot { it in knownConfig }
        .groupBy { it.value }
    val sensitiveStatuses = result.backupSensitiveStatus.entries.filter { (_, status) ->
        !onlyIssues || status.contains("失败") || status.contains("失敗") ||
            status.contains("failed", ignoreCase = true)
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
                bottom = paddingValues.calculateBottomPadding(),
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
                            showAllCategories = true
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
                }
            }
            if (onlyIssues && result.warnings == 0 && categories.isEmpty() && knownConfig.isEmpty() &&
                otherConfig.isEmpty() && sensitiveStatuses.isEmpty()
            ) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_no_issues),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (categories.isNotEmpty() || !onlyIssues) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_categories),
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
                val detail = if (category in result.backupEmptyCategories && status == "已写入") {
                    stringResource(R.string.backup_manifest_empty_category)
                } else {
                    result.backupCategoryStatusDetails[category].orEmpty()
                }
                val detailText = if (detail.isNotBlank()) {
                    stringResource(R.string.backup_manifest_category_reason, detail)
                } else ""
                Text(
                    text = stringResource(
                        R.string.backup_manifest_category_item,
                        name,
                        status,
                        detailText,
                    ),
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                )
            }
            if (!onlyIssues && (result.backupCategoryStatus.size > categories.size || showAllCategories)) {
                item {
                    MediumTonalButton(
                        text = stringResource(
                            if (showAllCategories) R.string.backup_manifest_hide_empty
                            else R.string.backup_manifest_show_all
                        ),
                        onClick = { showAllCategories = !showAllCategories },
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
            }
            if (knownConfig.isNotEmpty() || otherConfig.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_config_items),
                        color = LegadoTheme.colorScheme.primary,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(knownConfig, key = { "config:${it.key}" }) { (key, status) ->
                    Text(
                        text = stringResource(
                            R.string.backup_config_key_coverage,
                            result.backupConfigKeyLabels.getValue(key),
                            status,
                        ),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
                otherConfig.forEach { (status, entries) ->
                    item(key = "other:$status") {
                        Text(
                            text = stringResource(R.string.backup_manifest_other_config_count, status, entries.size),
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
            if (notSetConfig.isNotEmpty()) {
                item {
                    Text(
                        text = stringResource(R.string.backup_manifest_not_set, notSetConfig.size),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    )
                }
                items(notSetConfig.filter { (key, _) ->
                    result.backupConfigKeyLabels[key]?.let { it != "其他配置项" && it != key } == true
                }, key = { "not-set:${it.key}" }) { (key, _) ->
                    Text(
                        text = result.backupConfigKeyLabels.getValue(key),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (sensitiveStatuses.isNotEmpty()) {
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
                    Text(
                        text = stringResource(R.string.backup_sensitive_coverage, name, status),
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                    )
                }
            }
            if (!onlyIssues && result.backupFileNames.isNotEmpty()) {
                item {
                    CollapsibleHeader(
                        isCollapsed = !showFiles,
                        title = stringResource(R.string.backup_manifest_files, result.backupFileNames.size),
                        onToggle = { showFiles = !showFiles },
                    )
                }
                if (showFiles) {
                    items(result.backupFileNames.distinct(), key = { "file:$it" }) { fileName ->
                        Text(
                            text = fileName,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
                        )
                    }
                }
            }
        }
    }
}
