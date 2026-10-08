package io.legado.app.ui.config.backupConfig

import androidx.compose.animation.AnimatedContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Lan
import androidx.compose.material.icons.filled.PhoneAndroid
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.heightIn
import io.legado.app.R
import io.legado.app.ui.widget.components.modalBottomSheet.OptionCard
import io.legado.app.ui.widget.components.modalBottomSheet.OptionSheet
import io.legado.app.domain.model.RestoreMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupOptionSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onBackupToLocal: () -> Unit,
    onBackupToNetwork: () -> Unit,
    onBackupToLocalAndNetwork: () -> Unit,
) {
    OptionSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.backup),
    ) {
        OptionCard(
            icon = Icons.Default.PhoneAndroid,
            text = stringResource(R.string.backup_to_local),
            description = stringResource(R.string.backup_to_local_desc),
            onClick = onBackupToLocal,
        )
        OptionCard(
            icon = Icons.Default.Cloud,
            text = stringResource(R.string.backup_to_network),
            description = stringResource(R.string.backup_to_network_desc),
            onClick = onBackupToNetwork,
        )
        OptionCard(
            icon = Icons.Default.Lan,
            text = stringResource(R.string.backup_to_local_and_network),
            description = stringResource(R.string.backup_to_both_desc),
            onClick = onBackupToLocalAndNetwork,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RestoreOptionSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onRestoreFromLocal: (RestoreMode) -> Unit,
    onRestoreFromNetwork: (RestoreMode) -> Unit,
) {
    var source by androidx.compose.runtime.remember { mutableStateOf<RestoreSource?>(null) }
    var showAdvanced by androidx.compose.runtime.remember { mutableStateOf(false) }
    OptionSheet(
        show = show,
        onDismissRequest = { source = null; showAdvanced = false; onDismissRequest() },
        modifier = Modifier.heightIn(min = 220.dp, max = 420.dp),
        title = stringResource(
            when {
                source == null -> R.string.restore_choose_source
                showAdvanced -> R.string.restore_advanced_actions
                else -> R.string.restore_choose_strategy
            }
        ),
    ) {
        AnimatedContent(
            targetState = source to showAdvanced,
            label = "restore-options",
            modifier = Modifier.fillMaxWidth(),
        ) { (selectedSource, advanced) ->
            FlowRow(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(12.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                maxItemsInEachRow = 2,
            ) {
                if (selectedSource == null) {
                    OptionCard(Icons.Default.PhoneAndroid, stringResource(R.string.restore_source_local),
                        description = stringResource(R.string.restore_source_local_desc)) {
                        showAdvanced = false
                        source = RestoreSource.LOCAL
                    }
                    OptionCard(Icons.Default.Cloud, stringResource(R.string.restore_source_webdav),
                        description = stringResource(R.string.restore_source_webdav_desc)) {
                        showAdvanced = false
                        source = RestoreSource.WEBDAV
                    }
                } else {
                    fun restore(mode: RestoreMode) {
                        val action = if (selectedSource == RestoreSource.LOCAL) onRestoreFromLocal else onRestoreFromNetwork
                        source = null
                        showAdvanced = false
                        action(mode)
                    }
                    if (advanced) {
                        OptionCard(Icons.Default.PhoneAndroid, stringResource(R.string.restore_back_to_strategies)) {
                            showAdvanced = false
                        }
                        OptionCard(Icons.Default.Warning, stringResource(R.string.restore_strategy_replace),
                            description = stringResource(R.string.restore_strategy_replace_desc)) {
                            restore(RestoreMode.CLEAR_THEN_RESTORE)
                        }
                    } else {
                        OptionCard(Icons.Default.PhoneAndroid, stringResource(R.string.restore_strategy_merge),
                            description = stringResource(R.string.restore_strategy_merge_desc)) {
                            restore(RestoreMode.KEEP_APPLICATION)
                        }
                        OptionCard(Icons.Default.PhoneAndroid, stringResource(R.string.restore_strategy_by_category),
                            description = stringResource(R.string.restore_strategy_by_category_desc)) {
                            restore(RestoreMode.BY_CATEGORY)
                        }
                        // 删除数据的方式放入独立的高级层级，避免与普通恢复误触。
                        OptionCard(Icons.Default.Warning, stringResource(R.string.restore_advanced_actions),
                            description = stringResource(R.string.restore_advanced_actions_desc)) {
                            showAdvanced = true
                        }
                    }
                }
            }
        }
    }
}

private enum class RestoreSource { LOCAL, WEBDAV }

/** 旧版数据既可从完整目录导入，也可直接选择单个 JSON 文件。 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LegacyImportOptionSheet(
    show: Boolean,
    onDismissRequest: () -> Unit,
    onSelectFile: () -> Unit,
    onSelectDirectory: () -> Unit,
) {
    OptionSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = stringResource(R.string.menu_import_old_version),
    ) {
        OptionCard(
            icon = Icons.Default.PhoneAndroid,
            text = stringResource(R.string.select_file),
            description = stringResource(R.string.select_file_desc),
            onClick = onSelectFile,
        )
        OptionCard(
            icon = Icons.Default.PhoneAndroid,
            text = stringResource(R.string.select_folder),
            description = stringResource(R.string.select_folder_desc),
            onClick = onSelectDirectory,
        )
    }
}
