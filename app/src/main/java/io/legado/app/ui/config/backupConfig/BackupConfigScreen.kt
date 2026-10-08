package io.legado.app.ui.config.backupConfig

import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Save
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.clickable
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringArrayResource
import androidx.compose.ui.res.stringResource
import io.legado.app.utils.toastOnUi
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import io.legado.app.R
import io.legado.app.help.storage.ImportOldData
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.lib.permission.Permissions
import io.legado.app.lib.permission.PermissionsCompat
import io.legado.app.ui.theme.LegadoTheme
import io.legado.app.ui.theme.adaptiveContentPadding
import io.legado.app.ui.widget.components.AppScaffold
import io.legado.app.ui.widget.components.AppTextField
import io.legado.app.ui.widget.components.CollapsibleHeader
import io.legado.app.ui.widget.components.SplicedColumnGroup
import io.legado.app.ui.widget.components.alert.AppAlertDialog
import io.legado.app.ui.widget.components.button.series.MediumTonalButton
import io.legado.app.ui.widget.components.card.SelectionItemCard
import io.legado.app.ui.widget.components.checkBox.CheckboxItem
import io.legado.app.ui.widget.components.filePicker.FilePickerSheet
import io.legado.app.ui.widget.components.modalBottomSheet.AppModalBottomSheet
import io.legado.app.ui.widget.components.settingItem.ClickableSettingItem
import io.legado.app.ui.widget.components.settingItem.DropdownListSettingItem
import io.legado.app.ui.widget.components.settingItem.InputSettingItem
import io.legado.app.ui.widget.components.settingItem.SwitchSettingItem
import io.legado.app.ui.widget.components.tabRow.CardTabRow
import io.legado.app.ui.widget.components.topbar.GlassMediumFlexibleTopAppBar
import io.legado.app.ui.widget.components.topbar.GlassTopAppBarDefaults
import io.legado.app.ui.widget.components.topbar.TopBarNavigationButton
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.takePersistablePermissionSafely
import kotlinx.collections.immutable.ImmutableList
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.koin.androidx.compose.koinViewModel

@Composable
@SuppressLint("LocalContextGetResourceValueCall")
fun BackupConfigRouteScreen(
    onBackClick: () -> Unit,
    viewModel: BackupConfigViewModel = koinViewModel(),
) {
    val context = LocalContext.current
    val state = viewModel.uiState.collectAsStateWithLifecycle().value
    val snackbarHostState = remember { SnackbarHostState() }
    val importScope = rememberCoroutineScope()
    val selectBackupPathLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            viewModel.onIntent(BackupConfigIntent.BackupDirectorySelectionCancelled)
            return@rememberLauncherForActivityResult
        }
        uri.takePersistablePermissionSafely(context)
        val path = if (uri.isContentScheme()) uri.toString() else uri.path.orEmpty()
        viewModel.onIntent(BackupConfigIntent.BackupDirectorySelected(path, runBackup = false))
    }
    val backupAndSelectLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) {
            viewModel.onIntent(BackupConfigIntent.BackupDirectorySelectionCancelled)
            return@rememberLauncherForActivityResult
        }
        uri.takePersistablePermissionSafely(context)
        val path = if (uri.isContentScheme()) uri.toString() else uri.path.orEmpty()
        viewModel.onIntent(BackupConfigIntent.BackupDirectorySelected(path, runBackup = true))
    }
    val restoreFileLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri -> uri?.let { viewModel.onIntent(BackupConfigIntent.RestoreLocal(it.toString())) } }
    val importOldLauncher = rememberLauncherForActivityResult(
        // 旧版导入既支持目录也支持单文件；文件入口使用 OpenDocument，避免
        // 将文件 URI 错当成 tree URI 导致 DocumentFile 读取失败。
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) context.toastOnUi(context.getString(R.string.import_old_not_selected))
        else importScope.launch(Dispatchers.IO) {
            val result = runCatching { ImportOldData.importUri(context, uri) }
                .getOrElse { ImportOldData.ImportResult(failedFiles = 1) }
            withContext(Dispatchers.Main) {
                context.toastOnUi(
                    context.getString(
                        R.string.import_old_result,
                        result.processedFiles,
                        result.importedItems,
                        result.failedFiles,
                        result.skippedFiles,
                    )
                )
            }
        }
    }
    val importOldDirectoryLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        if (uri == null) context.toastOnUi(context.getString(R.string.import_old_not_selected))
        else importScope.launch(Dispatchers.IO) {
            val result = runCatching { ImportOldData.importUri(context, uri) }
                .getOrElse { ImportOldData.ImportResult(failedFiles = 1) }
            withContext(Dispatchers.Main) {
                context.toastOnUi(
                    context.getString(
                        R.string.import_old_result,
                        result.processedFiles,
                        result.importedItems,
                        result.failedFiles,
                        result.skippedFiles,
                    )
                )
            }
        }
    }

    LaunchedEffect(Unit) {
        viewModel.effects.collectLatest { effect ->
            when (effect) {
                BackupConfigEffect.LaunchBackupDirectoryPicker -> selectBackupPathLauncher.launch(null)
                BackupConfigEffect.LaunchBackupAndRunDirectoryPicker ->
                    backupAndSelectLauncher.launch(null)
                BackupConfigEffect.LaunchRestoreFilePicker ->
                    restoreFileLauncher.launch(arrayOf("application/zip"))
BackupConfigEffect.LaunchImportOldDataFilePicker ->
                    importOldLauncher.launch(arrayOf("application/zip", "application/json", "text/plain", "*/*", "application/octet-stream"))
                BackupConfigEffect.LaunchImportOldDataDirectoryPicker ->
                    importOldDirectoryLauncher.launch(null)
                BackupConfigEffect.BackupDirectorySelectionCancelled ->
                    snackbarHostState.showSnackbar(context.getString(R.string.backup_directory_not_selected))
                is BackupConfigEffect.RequestStoragePermission -> {
                    PermissionsCompat.Builder()
                        .addPermissions(*Permissions.Group.STORAGE)
                        .rationale(R.string.tip_perm_request_storage)
                        .onGranted {
                            viewModel.onIntent(
                                BackupConfigIntent.PerformBackup(effect.path, effect.mode)
                            )
                        }
                        .request()
                }
                is BackupConfigEffect.ShowMessage -> {
                    val message = effect.argument?.let {
                        context.getString(effect.messageRes, it)
                    } ?: context.getString(effect.messageRes)
                    snackbarHostState.showSnackbar(message)
                }
            }
        }
    }

    val manifestReport = (state.activeDialog as? BackupConfigDialog.ConfigIntegrityReport)?.result
    if (state.showBackupManifest && manifestReport != null) {
        BackupManifestScreen(
            result = manifestReport,
            onBackClick = { viewModel.onIntent(BackupConfigIntent.CloseBackupManifest) },
        )
    } else {
        BackupConfigScreen(
            state = state,
            onIntent = viewModel::onIntent,
            onBackClick = onBackClick,
            snackbarHostState = snackbarHostState,
        )
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BackupConfigScreen(
    state: BackupConfigUiState,
    onIntent: (BackupConfigIntent) -> Unit,
    onBackClick: () -> Unit,
    snackbarHostState: SnackbarHostState,
) {
    val settings = state.settings
    val scrollBehavior = GlassTopAppBarDefaults.defaultScrollBehavior()
    AppScaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            GlassMediumFlexibleTopAppBar(
                title = stringResource(R.string.backup_restore),
                scrollBehavior = scrollBehavior,
                navigationIcon = { TopBarNavigationButton(onClick = onBackClick) },
            )
        },
    ) { paddingValues ->
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = adaptiveContentPadding(
                top = paddingValues.calculateTopPadding(),
                bottom = 120.dp,
            ),
        ) {
            item {
                SplicedColumnGroup(title = stringResource(R.string.web_dav_set)) {
                    InputSettingItem(
                        title = stringResource(R.string.web_dav_url),
                        description = stringResource(R.string.web_dav_url_s),
                        value = settings.webDavUrl,
                        defaultValue = "",
                        onConfirm = { onIntent(BackupConfigIntent.SetWebDavUrl(it)) },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.web_dav_account),
                        description = stringResource(R.string.web_dav_account_d),
                        onClick = { onIntent(BackupConfigIntent.OpenWebDavAuth) },
                    )
                    InputSettingItem(
                        title = stringResource(R.string.sub_dir),
                        value = settings.webDavDir,
                        defaultValue = "legado",
                        onConfirm = { onIntent(BackupConfigIntent.SetWebDavDir(it)) },
                    )
                    InputSettingItem(
                        title = stringResource(R.string.webdav_device_name),
                        value = settings.webDavDeviceName,
                        defaultValue = "",
                        onConfirm = { onIntent(BackupConfigIntent.SetWebDavDeviceName(it)) },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.test_sync_t),
                        description = stringResource(R.string.test_sync_d),
                        onClick = { onIntent(BackupConfigIntent.TestWebDav) },
                    )
                    SwitchSettingItem(
                        title = stringResource(R.string.sync_book_progress_t),
                        description = stringResource(R.string.sync_book_progress_s),
                        checked = settings.syncBookProgress,
                        onCheckedChange = { onIntent(BackupConfigIntent.SetSyncBookProgress(it)) },
                    )
                    if (settings.syncBookProgress) {
                        SwitchSettingItem(
                            title = stringResource(R.string.sync_book_progress_plus_t),
                            description = stringResource(R.string.sync_book_progress_plus_s),
                            checked = settings.syncBookProgressPlus,
                            onCheckedChange = {
                                onIntent(BackupConfigIntent.SetSyncBookProgressPlus(it))
                            },
                        )
                    }
                    SwitchSettingItem(
                        title = stringResource(R.string.auto_check_new_backup_t),
                        description = stringResource(R.string.auto_check_new_backup_s),
                        checked = settings.autoCheckNewBackup,
                        onCheckedChange = {
                            onIntent(BackupConfigIntent.SetAutoCheckNewBackup(it))
                        },
                    )
                    DropdownListSettingItem(
                        title = stringResource(R.string.backup_sync_mode),
                        description = stringResource(R.string.backup_sync_mode_summary),
                        selectedValue = settings.backupSyncMode,
                        displayEntries = stringArrayResource(R.array.backup_sync_mode),
                        entryValues = stringArrayResource(R.array.backup_sync_mode_value),
                        onValueChange = { onIntent(BackupConfigIntent.SetBackupSyncMode(it)) },
                    )
                }
                SplicedColumnGroup(title = stringResource(R.string.backup_restore)) {
                    ClickableSettingItem(
                        title = stringResource(R.string.backup_path),
                        description = settings.backupPath ?: stringResource(R.string.select_backup_path),
                        onClick = {
                            onIntent(BackupConfigIntent.OpenSheet(BackupConfigSheet.ChooseBackupPath))
                        },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.backup),
                        description = stringResource(R.string.backup_summary),
                        onClick = {
                            onIntent(BackupConfigIntent.OpenSheet(BackupConfigSheet.BackupOptions))
                        },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.restore),
                        description = stringResource(R.string.restore_summary),
                        onClick = {
                            onIntent(BackupConfigIntent.OpenSheet(BackupConfigSheet.RestoreOptions))
                        },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.restore_ignore),
                        description = stringResource(R.string.restore_ignore_summary),
                        onClick = { onIntent(BackupConfigIntent.OpenIgnoreDialog) },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.backup_ignore),
                        description = stringResource(R.string.backup_ignore_summary),
                        onClick = { onIntent(BackupConfigIntent.OpenBackupIgnoreDialog) },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.backup_sensitive_title),
                        description = stringResource(R.string.backup_sensitive_summary),
                        onClick = { onIntent(BackupConfigIntent.OpenSensitiveItems) },
                    )
                    ClickableSettingItem(
                        title = stringResource(R.string.menu_import_old_version),
                        description = stringResource(R.string.import_old_summary),
                        onClick = { onIntent(BackupConfigIntent.RequestImportOldData) },
                    )
                    SwitchSettingItem(
                        title = stringResource(R.string.only_latest_backup_t),
                        description = stringResource(R.string.only_latest_backup_s),
                        checked = settings.onlyLatestBackup,
                        onCheckedChange = { onIntent(BackupConfigIntent.SetOnlyLatestBackup(it)) },
                    )
                }
            }
        }
    }

    BackupConfigSheets(state, onIntent)
    BackupConfigDialogs(state, onIntent)
}

@Composable
private fun BackupConfigSheets(
    state: BackupConfigUiState,
    onIntent: (BackupConfigIntent) -> Unit,
) {
    FilePickerSheet(
        show = state.activeSheet == BackupConfigSheet.ChooseBackupPath,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        onSelectSysDir = { onIntent(BackupConfigIntent.SelectBackupDirectory) },
    )
    FilePickerSheet(
        show = state.activeSheet == BackupConfigSheet.ChooseBackupAndRun,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        onSelectSysDir = { onIntent(BackupConfigIntent.SelectBackupAndRunDirectory) },
    )
    BackupOptionSheet(
        show = state.activeSheet == BackupConfigSheet.BackupOptions,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        onBackupToLocal = { onIntent(BackupConfigIntent.RequestBackup("local")) },
        onBackupToNetwork = { onIntent(BackupConfigIntent.RequestBackup("webdav")) },
        onBackupToLocalAndNetwork = { onIntent(BackupConfigIntent.RequestBackup("both")) },
    )
    RestoreOptionSheet(
        show = state.activeSheet == BackupConfigSheet.RestoreOptions,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        onRestoreFromLocal = { mode -> onIntent(BackupConfigIntent.RequestLocalRestore(mode)) },
        onRestoreFromNetwork = { mode -> onIntent(BackupConfigIntent.RequestNetworkRestore(mode)) },
    )
    AppModalBottomSheet(
        show = state.activeSheet == BackupConfigSheet.RestoreFiles && state.backupNames.isNotEmpty(),
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        title = stringResource(R.string.select_restore_file),
    ) {
        LazyColumn(
            modifier = Modifier
                .fillMaxWidth()
                .padding(bottom = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(state.backupNames, key = { it }) { name ->
                SelectionItemCard(
                    title = name,
                    containerColor = LegadoTheme.colorScheme.surface,
                    onToggleSelection = { onIntent(BackupConfigIntent.RestoreNetwork(name)) },
                )
            }
        }
    }
    IgnoreItemsSheet(
        show = state.activeSheet == BackupConfigSheet.IgnoreRestoreItems,
        title = stringResource(R.string.restore_ignore),
        ignoreItems = state.ignoreItems,
        dbIgnoreItems = state.dbIgnoreItems,
        onToggleIgnoreItem = { key, value ->
            onIntent(
                BackupConfigIntent.ToggleIgnoreItem(
                    key,
                    value
                )
            )
        },
        onToggleDbIgnoreItem = { key, value ->
            onIntent(
                BackupConfigIntent.ToggleDbIgnoreItem(
                    key,
                    value
                )
            )
        },
        onConfirm = { onIntent(BackupConfigIntent.SaveIgnoreItems) },
        onDismissRequest = { onIntent(BackupConfigIntent.SaveIgnoreItems) },
    )
    IgnoreItemsSheet(
        show = state.activeSheet == BackupConfigSheet.IgnoreBackupItems,
        title = stringResource(R.string.backup_ignore),
        ignoreItems = state.backupIgnoreItems,
        dbIgnoreItems = state.backupDbIgnoreItems,
        onToggleIgnoreItem = { key, value ->
            onIntent(
                BackupConfigIntent.ToggleBackupIgnoreItem(
                    key,
                    value
                )
            )
        },
        onToggleDbIgnoreItem = { key, value ->
            onIntent(
                BackupConfigIntent.ToggleBackupDbIgnoreItem(
                    key,
                    value
                )
            )
        },
        onConfirm = { onIntent(BackupConfigIntent.SaveBackupIgnoreItems) },
        onDismissRequest = { onIntent(BackupConfigIntent.SaveBackupIgnoreItems) },
    )
    LegacyImportOptionSheet(
        show = state.activeSheet == BackupConfigSheet.ImportOldData,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissSheet) },
        onSelectFile = { onIntent(BackupConfigIntent.SelectOldDataFile) },
        onSelectDirectory = { onIntent(BackupConfigIntent.SelectOldDataDirectory) },
    )
    SensitiveItemsSheet(
        show = state.activeSheet == BackupConfigSheet.SensitiveItems,
        items = state.sensitiveItems,
        onToggle = { category, value -> onIntent(BackupConfigIntent.ToggleSensitiveItem(category, value)) },
        onConfirm = { onIntent(BackupConfigIntent.SaveSensitiveItems) },
        onDismiss = { onIntent(BackupConfigIntent.SaveSensitiveItems) },
    )
}

@Composable
private fun SensitiveItemsSheet(
    show: Boolean,
    items: ImmutableList<BackupSensitiveItem>,
    onToggle: (io.legado.app.domain.model.BackupSensitiveCategory, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismiss,
        modifier = Modifier.height(adaptiveBackupDialogHeight(fraction = 0.65f)),
        title = stringResource(R.string.backup_sensitive_title),
        endAction = {
            MediumTonalButton(onClick = onConfirm, icon = Icons.Default.Save, contentDescription = stringResource(R.string.save))
        },
    ) {
        LazyColumn(
            // 单一 LazyColumn 由 bottom sheet 约束高度，避免小屏/MIUI 上 verticalScroll
            // 子 Column 被测量为无限高度而无法消费手势。
            modifier = Modifier.fillMaxWidth(),
            contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 24.dp),
        ) {
            item {
                Text(
                    text = stringResource(R.string.set_local_password_summary),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                )
            }
            items(items, key = { it.category.name }) { item ->
                CheckboxItem(
                    title = sensitiveCategoryTitle(item.category),
                    checked = item.checked,
                    onCheckedChange = { onToggle(item.category, it) },
                )
                androidx.compose.material3.Text(
                    text = stringResource(
                        if (item.checked) R.string.backup_sensitive_selected
                        else R.string.backup_sensitive_not_selected,
                    ),
                    modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.material3.Text(
                    text = sensitiveCategoryDescription(item.category),
                    modifier = Modifier.padding(start = 16.dp, end = 16.dp, bottom = 8.dp),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
internal fun sensitiveCategoryTitle(category: io.legado.app.domain.model.BackupSensitiveCategory): String =
    stringResource(
        when (category) {
            io.legado.app.domain.model.BackupSensitiveCategory.WEBDAV_CREDENTIALS -> R.string.backup_sensitive_webdav
            io.legado.app.domain.model.BackupSensitiveCategory.SERVER_CREDENTIALS -> R.string.backup_sensitive_server
            io.legado.app.domain.model.BackupSensitiveCategory.BOOK_SOURCE_AUTH -> R.string.backup_sensitive_source
            io.legado.app.domain.model.BackupSensitiveCategory.REQUEST_HEADERS -> R.string.backup_sensitive_headers
            io.legado.app.domain.model.BackupSensitiveCategory.RSS_AUTH -> R.string.backup_sensitive_rss
            io.legado.app.domain.model.BackupSensitiveCategory.TTS_API_KEYS -> R.string.backup_sensitive_tts
            io.legado.app.domain.model.BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS -> R.string.backup_sensitive_direct_link
            io.legado.app.domain.model.BackupSensitiveCategory.OTHER -> R.string.backup_sensitive_other
        }
    )

@Composable
private fun sensitiveCategoryDescription(category: io.legado.app.domain.model.BackupSensitiveCategory): String =
    stringResource(
        when (category) {
            io.legado.app.domain.model.BackupSensitiveCategory.WEBDAV_CREDENTIALS -> R.string.backup_sensitive_webdav_desc
            io.legado.app.domain.model.BackupSensitiveCategory.SERVER_CREDENTIALS -> R.string.backup_sensitive_server_desc
            io.legado.app.domain.model.BackupSensitiveCategory.BOOK_SOURCE_AUTH -> R.string.backup_sensitive_source_desc
            io.legado.app.domain.model.BackupSensitiveCategory.REQUEST_HEADERS -> R.string.backup_sensitive_headers_desc
            io.legado.app.domain.model.BackupSensitiveCategory.RSS_AUTH -> R.string.backup_sensitive_rss_desc
            io.legado.app.domain.model.BackupSensitiveCategory.TTS_API_KEYS -> R.string.backup_sensitive_tts_desc
            io.legado.app.domain.model.BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS -> R.string.backup_sensitive_direct_link_desc
            io.legado.app.domain.model.BackupSensitiveCategory.OTHER -> R.string.backup_sensitive_other_desc
        }
    )

/** 按屏幕高度比例计算弹窗尺寸，正文区域负责滚动。 */
@Composable
private fun adaptiveBackupDialogHeight(
    fraction: Float,
): androidx.compose.ui.unit.Dp {
    val screenHeight = LocalConfiguration.current.screenHeightDp.dp
    return screenHeight * fraction
}

@Composable
private fun IgnoreItemsSheet(
    show: Boolean,
    title: String,
    ignoreItems: ImmutableList<BackupIgnoreItem>,
    dbIgnoreItems: ImmutableList<BackupIgnoreItem>,
    onToggleIgnoreItem: (String, Boolean) -> Unit,
    onToggleDbIgnoreItem: (String, Boolean) -> Unit,
    onConfirm: () -> Unit,
    onDismissRequest: () -> Unit,
) {
    var selectedTab by remember { mutableIntStateOf(0) }

    AppModalBottomSheet(
        show = show,
        onDismissRequest = onDismissRequest,
        title = title,
        endAction = {
            MediumTonalButton(
                onClick = onConfirm,
                icon = Icons.Default.Save,
                contentDescription = stringResource(R.string.save),
            )
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            CardTabRow(
                tabTitles = listOf(
                    stringResource(R.string.config_ignore),
                    stringResource(R.string.database_ignore),
                ),
                selectedTabIndex = selectedTab,
                onTabSelected = { selectedTab = it },
            )
            when (selectedTab) {
                0 -> {
                    ignoreItems.forEach { item: BackupIgnoreItem ->
                        CheckboxItem(
                            title = item.title,
                            checked = item.checked,
                            onCheckedChange = { onToggleIgnoreItem(item.key, it) },
                        )
                    }
                }

                1 -> {
                    dbIgnoreItems.forEach { item: BackupIgnoreItem ->
                        CheckboxItem(
                            title = item.title,
                            checked = item.checked,
                            onCheckedChange = { onToggleDbIgnoreItem(item.key, it) },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BackupConfigDialogs(
    state: BackupConfigUiState,
    onIntent: (BackupConfigIntent) -> Unit,
) {
    val resultDialogHeight = adaptiveBackupDialogHeight(fraction = 0.40f)
    val previewDialogHeight = adaptiveBackupDialogHeight(fraction = 0.50f)
    val dialog = state.activeDialog
    val auth = dialog as? BackupConfigDialog.WebDavAuth
    AppAlertDialog(
        show = auth != null,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissDialog) },
        title = stringResource(R.string.web_dav_account),
        content = {
            auth?.let {
                Column {
                    AppTextField(
                        value = it.account,
                        onValueChange = { value ->
                            onIntent(BackupConfigIntent.EditWebDavAccount(value))
                        },
                        backgroundColor = LegadoTheme.colorScheme.surface,
                        label = stringResource(R.string.web_dav_account),
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    AppTextField(
                        value = it.password,
                        onValueChange = { value ->
                            onIntent(BackupConfigIntent.EditWebDavPassword(value))
                        },
                        backgroundColor = LegadoTheme.colorScheme.surface,
                        label = stringResource(R.string.web_dav_pw),
                        visualTransformation = if (it.passwordVisible) {
                            VisualTransformation.None
                        } else {
                            PasswordVisualTransformation()
                        },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        trailingIcon = {
                            IconButton(onClick = {
                                onIntent(BackupConfigIntent.TogglePasswordVisibility)
                            }) {
                                Icon(
                                    imageVector = if (it.passwordVisible) {
                                        Icons.Filled.Visibility
                                    } else {
                                        Icons.Filled.VisibilityOff
                                    },
                                    contentDescription = stringResource(
                                        if (it.passwordVisible) R.string.hide_password
                                        else R.string.show_password
                                    ),
                                )
                            }
                        },
                    )
                }
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(BackupConfigIntent.SaveWebDavAuth) },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(BackupConfigIntent.DismissDialog) },
    )

    val fallback = dialog as? BackupConfigDialog.ConfirmLocalRestoreFallback
    ConfirmDialog(
        show = fallback != null,
        title = stringResource(R.string.restore),
        text = stringResource(R.string.webdav_restore_fallback_message, fallback?.error.orEmpty()),
        onConfirm = { onIntent(BackupConfigIntent.ConfirmLocalRestoreFallback) },
        onDismiss = { onIntent(BackupConfigIntent.DismissDialog) },
    )

    val loading = dialog as? BackupConfigDialog.Loading
    AppAlertDialog(
        show = loading != null,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissDialog) },
        title = loading?.progress?.stage?.localizedTitle()
            ?: loading?.let { stringResource(it.titleRes) }.orEmpty(),
        text = loading?.progress?.let {
            stringResource(
                R.string.backup_restore_progress_summary,
                it.current,
                it.total,
                it.warnings,
                it.failures,
                it.category.orEmpty(),
            )
        } ?: loading?.let { stringResource(R.string.backup_restore_progress_preparing) },
        content = {
            LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
        },
    )

    val integrity = dialog as? BackupConfigDialog.ConfigIntegrityReport
    val backupTargetSucceeded = integrity?.result?.let { result ->
        result.localBackupCreated || (result.webDavUploadAttempted && !result.webDavUploadFailed)
    } == true
    val hasBackupManifest = integrity?.result?.let { result ->
        (integrity?.type == BackupConfigReportType.RESTORE || backupTargetSucceeded) &&
            (result.backupCategoryStatus.isNotEmpty() || result.backupConfigKeyStatus.isNotEmpty())
    } == true
    AppAlertDialog(
        // 高度由 AppAlertDialog 按主题分别应用：MD3 限制弹窗，Miuix 限制正文滚动区。
        show = integrity != null,
        contentMaxHeight = resultDialogHeight,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissDialog) },
        title = stringResource(
            if (integrity?.type == BackupConfigReportType.RESTORE) R.string.restore_result_title
            else R.string.backup_result_title
        ),
        text = integrity?.let {
            if (it.type == BackupConfigReportType.RESTORE) {
                val failed = it.result.failedCategories
                stringResource(
                    R.string.restore_result_summary,
                    restoreOutcomeTitle(it.result.outcome),
                    it.result.categoryStats.count { (category, stats) ->
                        category !in failed &&
                            stats.inserted + stats.updated + stats.deleted > 0
                    },
                    failed.size,
                )
            } else {
                val resultTitle = stringResource(
                    when {
                        it.result.localBackupCreated && it.result.webDavUploadFailed -> R.string.backup_result_partial
                        it.result.outcome == BackupRestoreOutcome.SUCCESS -> R.string.backup_success
                        it.result.outcome == BackupRestoreOutcome.SUCCESS_WITH_WARNINGS -> R.string.backup_result_warning
                        else -> R.string.backup_result_failed
                    }
                )
                if (!backupTargetSucceeded || (it.result.backupCategoryStatus.isEmpty() &&
                    it.result.writtenConfigKeys == 0 && it.result.notSetConfigKeys == 0)
                ) {
                    resultTitle
                } else {
                    stringResource(
                        R.string.backup_result_summary,
                        resultTitle,
                        it.result.writtenConfigKeys,
                        it.result.notSetConfigKeys,
                    )
                }
            }
        }.orEmpty(),
        content = {
            integrity?.let { report ->
                val visibleCategories = if (report.type == BackupConfigReportType.RESTORE) {
                    report.result.categoryStats.entries
                        .filter { (category, stats) ->
                            category !in report.result.failedCategories &&
                                stats.inserted + stats.updated + stats.deleted > 0
                        }
                        .sortedBy { it.key }
                } else emptyList()
                val unchangedCategoryCount = if (report.type == BackupConfigReportType.RESTORE) {
                    report.result.categoryStats.count { (category, stats) ->
                        category !in report.result.failedCategories &&
                            stats.inserted + stats.updated + stats.deleted == 0
                    }
                } else 0
                val unchangedCategories = if (report.type == BackupConfigReportType.RESTORE) {
                    report.result.categoryStats.entries
                        .filter { (category, stats) ->
                            category !in report.result.failedCategories &&
                                stats.inserted + stats.updated + stats.deleted == 0
                        }
                        .sortedBy { it.key }
                } else emptyList()
                report.message?.takeIf { it.isNotBlank() }?.let { message ->
                    Text(
                        text = readableRestoreFailure(message),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (report.type == BackupConfigReportType.RESTORE && report.source != null) {
                    Text(
                        text = stringResource(
                            R.string.restore_result_source,
                            stringResource(
                                if (report.source == BackupConfigResultSource.WEBDAV) {
                                    R.string.restore_result_source_webdav
                                } else {
                                    R.string.restore_result_source_local
                                }
                            ),
                        ),
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = LegadoTheme.colorScheme.onSurfaceVariant,
                    )
                }
                if (report.type == BackupConfigReportType.BACKUP &&
                    report.result.webDavUploadFailed &&
                    (report.result.outcome == BackupRestoreOutcome.PRECONDITION_FAILED ||
                        !report.result.webDavUploadAttempted)
                ) {
                    Text(
                        text = stringResource(R.string.backup_webdav_precondition_failed),
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = LegadoTheme.colorScheme.error,
                    )
                }
                if (report.result.localBackupAttempted || report.result.webDavUploadAttempted ||
                    report.result.webDavUploadFailed
                ) {
                    Text(
                        text = stringResource(R.string.backup_result_targets_title),
                        modifier = Modifier.padding(bottom = 4.dp),
                        color = LegadoTheme.colorScheme.primary,
                    )
                }
                if (report.result.localBackupAttempted) {
                    Text(
                        text = stringResource(
                            if (report.result.localBackupCreated) R.string.backup_local_result_success
                            else R.string.backup_local_result_failed,
                        ),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (report.result.webDavUploadAttempted || report.result.webDavUploadFailed) {
                    Text(
                        text = stringResource(
                            if (report.result.webDavUploadFailed) R.string.backup_webdav_result_failed
                            else R.string.backup_webdav_result_success,
                        ),
                        modifier = Modifier.padding(bottom = 8.dp),
                    )
                }
                if (report.type == BackupConfigReportType.BACKUP &&
                    (report.result.ignoredConfigKeys > 0 || report.result.failedConfigKeys > 0 ||
                        report.result.unsupportedConfigKeys > 0)
                ) {
                    Text(
                        text = backupAnomalySummary(report.result),
                        modifier = Modifier.padding(bottom = 8.dp),
                        color = LegadoTheme.colorScheme.error,
                    )
                }
                if (report.type == BackupConfigReportType.RESTORE &&
                    (report.result.failedCategories.isNotEmpty() ||
                        report.result.decryptionFailedConfigKeyNames.isNotEmpty())
                ) {
                    Text(
                        text = stringResource(R.string.restore_result_failures_title),
                        modifier = Modifier.padding(top = 4.dp, bottom = 2.dp),
                        color = LegadoTheme.colorScheme.error,
                    )
                    val separator = stringResource(R.string.backup_config_summary_separator)
                    val sensitiveCategories: List<String> = report.result.failedCategories
                        .filter { category -> isSensitiveRestoreCategory(category) }
                        .map { category -> restoreCategoryTitle(category) }
                    if (sensitiveCategories.isNotEmpty() || report.result.decryptionFailedConfigKeyNames.isNotEmpty()) {
                        Text(
                            text = stringResource(
                                R.string.restore_failure_sensitive_group,
                                sensitiveCategories.size.coerceAtLeast(1),
                                sensitiveCategories.joinToString(separator),
                            ),
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = LegadoTheme.colorScheme.error,
                        )
                    }
                    val nonSensitiveFailures: List<String> = report.result.failedCategories
                        .filterNot { category -> isSensitiveRestoreCategory(category) }
                    if (nonSensitiveFailures.isNotEmpty()) {
                        Text(
                            text = if (report.result.configFileParseFailed && "settings" in nonSensitiveFailures) {
                                stringResource(
                                    R.string.restore_failure_parse_group,
                                    restoreCategoryTitle("settings"),
                                )
                            } else {
                                val categoryTitles = nonSensitiveFailures.map { category -> restoreCategoryTitle(category) }
                                stringResource(
                                    R.string.restore_failure_category_group,
                                    categoryTitles.joinToString(separator),
                                )
                            },
                            modifier = Modifier.padding(bottom = 4.dp),
                            color = LegadoTheme.colorScheme.error,
                        )
                    }
                }
                if (report.type == BackupConfigReportType.RESTORE && detailCountForRestore(report.result) > 0) {
                    CollapsibleHeader(
                        isCollapsed = !report.showDetails,
                        title = if (report.showDetails) {
                            stringResource(R.string.restore_result_collapse_details)
                        } else {
                            stringResource(R.string.restore_result_view_details)
                        },
                        onToggle = { onIntent(BackupConfigIntent.ToggleConfigReportDetails) },
                    )
                    if (report.showDetails) {
                        val groupedDetails = linkedMapOf<String, LinkedHashSet<String>>()
                        report.result.failedCategoryDetails.forEach { (category, details) ->
                            details.distinct().forEach { detail ->
                                val readable = readableRestoreFailure(
                                    detail,
                                    configParseFailed = category == "settings" &&
                                        report.result.configFileParseFailed,
                                    configWriteFailed = category == "settings" &&
                                        !report.result.configFileParseFailed,
                                )
                                groupedDetails.getOrPut(readable) { linkedSetOf() } += restoreCategoryTitle(category)
                            }
                        }
                        groupedDetails.forEach { (reason, categories) ->
                            Text(
                                text = stringResource(
                                    R.string.restore_failure_grouped_detail,
                                    categories.joinToString(stringResource(R.string.backup_config_summary_separator)),
                                    reason,
                                ),
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = LegadoTheme.colorScheme.error,
                            )
                        }
                    }
                }
                if (report.type == BackupConfigReportType.RESTORE && visibleCategories.isNotEmpty()) {
                    Text(
                        text = stringResource(R.string.restore_result_success_categories),
                        modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
                        color = LegadoTheme.colorScheme.primary,
                    )
                }
                visibleCategories.forEach { (category, _) ->
                        Text(
                            text = restoreCategoryTitle(category),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                }
                if (unchangedCategoryCount > 0) {
                    CollapsibleHeader(
                        isCollapsed = !report.showAllCategories,
                        title = stringResource(R.string.restore_result_unchanged_categories, unchangedCategoryCount),
                        onToggle = { onIntent(BackupConfigIntent.ToggleAllRestoreCategories) },
                    )
                    if (report.showAllCategories) {
                        unchangedCategories.forEach { (category, _) ->
                            Text(
                                text = restoreCategoryTitle(category),
                                modifier = Modifier.padding(vertical = 2.dp),
                                color = LegadoTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
                if (report.type == BackupConfigReportType.RESTORE &&
                    report.result.decryptionFailedConfigKeyNames.isNotEmpty()
                ) {
                    Text(
                        text = stringResource(
                            R.string.restore_result_decryption_failed,
                            report.result.decryptionFailedConfigKeyNames.size,
                        ),
                        modifier = Modifier.padding(vertical = 4.dp),
                        color = LegadoTheme.colorScheme.error,
                    )
                }
                val detailCount = if (report.type == BackupConfigReportType.RESTORE) {
                    detailCountForRestore(report.result)
                } else {
                    report.result.failedConfigKeyNames.size +
                        report.result.notSetConfigKeyNames.size +
                        report.result.unsupportedConfigKeyNames.size +
                        report.result.ignoredConfigKeyNames.size
                }
                if (report.type != BackupConfigReportType.RESTORE && detailCount > 0) {
                    CollapsibleHeader(
                        isCollapsed = !report.showDetails,
                        title = if (report.showDetails) {
                            stringResource(R.string.backup_config_integrity_collapse_details)
                        } else {
                            stringResource(R.string.backup_config_integrity_details)
                        },
                        onToggle = { onIntent(BackupConfigIntent.ToggleConfigReportDetails) },
                    )
                }
                if (report.showDetails && report.type != BackupConfigReportType.RESTORE && detailCount > 0) {
                    configDetailTitles(report.result).forEach { title ->
                        Text(
                            text = stringResource(R.string.backup_config_detail_category, title),
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                }
            }
        },
        confirmText = stringResource(R.string.ok),
        onConfirm = { onIntent(BackupConfigIntent.DismissDialog) },
        dismissText = stringResource(
            if (integrity?.type == BackupConfigReportType.RESTORE) R.string.restore_result_view_backup
            else R.string.backup_coverage_details
        ),
        onDismiss = if (hasBackupManifest) {
            { onIntent(BackupConfigIntent.OpenBackupManifest) }
        } else null,
    )

    val preview = dialog as? BackupConfigDialog.RestorePreview
    AppAlertDialog(
        contentMaxHeight = previewDialogHeight,
        show = preview != null,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissDialog) },
        title = stringResource(R.string.restore_preview),
        text = preview?.let {
            stringResource(
                R.string.restore_preview_summary,
                it.preview.fileCount,
                it.preview.resourceCount,
                it.preview.warnings.size,
            )
        }.orEmpty(),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth(),
            ) {
            if (preview != null) {
                Text(
                    text = stringResource(R.string.restore_preview_source_title),
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                Text(
                    text = if (preview.networkName == null) {
                        stringResource(R.string.restore_preview_source_local)
                    } else {
                        stringResource(R.string.restore_preview_source_webdav, preview.networkName)
                    },
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                val empty = preview.preview.emptyCategories
                val missing = preview.preview.missingCategories
                val backupIgnored = preview.preview.backupIgnoredCategories
                val restoreIgnored = preview.preview.restoreIgnoredCategories
                if (empty.isNotEmpty()) Text(
                    text = stringResource(R.string.restore_preview_empty_categories, restoreCategoryTitles(empty)),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (missing.isNotEmpty()) Text(
                    text = stringResource(R.string.restore_preview_missing_categories, restoreCategoryTitles(missing)),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (backupIgnored.isNotEmpty()) Text(
                    text = stringResource(R.string.restore_preview_backup_ignored, restoreCategoryTitles(backupIgnored)),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                if (restoreIgnored.isNotEmpty()) Text(
                    text = stringResource(R.string.restore_preview_restore_ignored, restoreCategoryTitles(restoreIgnored)),
                    modifier = Modifier.padding(bottom = 8.dp),
                )
            }
            if (preview?.preview?.sensitiveIncluded == true && !preview.preview.weakProtection) {
                AppTextField(
                    value = preview.localPassword,
                    onValueChange = { onIntent(BackupConfigIntent.SetRestoreBackupPassword(it)) },
                    label = stringResource(R.string.restore_local_password_label),
                    backgroundColor = LegadoTheme.colorScheme.surface,
                )
                Text(
                    text = stringResource(R.string.restore_preview_sensitive_hint),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
                if (preview.localPassword.isBlank()) {
                    Text(
                        text = stringResource(R.string.restore_sensitive_password_warning),
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                        color = LegadoTheme.colorScheme.error,
                    )
                }
            } else if (preview?.preview?.sensitiveIncluded == true) {
                Text(
                    text = stringResource(R.string.restore_preview_sensitive_weak_hint),
                    modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                    color = LegadoTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (preview?.mode == io.legado.app.domain.model.RestoreMode.BY_CATEGORY ||
                preview?.mode == io.legado.app.domain.model.RestoreMode.CLEAR_THEN_RESTORE) {
                Text(
                    text = stringResource(R.string.restore_preview_contents_title),
                    color = LegadoTheme.colorScheme.primary,
                    modifier = Modifier.padding(bottom = 4.dp),
                )
                // 分类恢复和清空恢复都必须让用户明确选择受影响的类别。
                // 清空恢复只列出备份中实际存在的类别，避免提示用户清空不存在的数据。
                (if (preview.mode == io.legado.app.domain.model.RestoreMode.CLEAR_THEN_RESTORE) {
                    preview.preview.categories.keys
                } else {
                    preview.preview.categories.keys + preview.preview.missingCategories
                })
                    .distinct().sorted().forEach { category ->
                    CheckboxItem(
                        title = restoreCategoryTitle(category),
                        checked = category in preview.selectedCategories,
                        onCheckedChange = { onIntent(BackupConfigIntent.ToggleRestorePreviewCategory(category)) },
                    )
                    androidx.compose.material3.Text(
                        text = stringResource(
                            R.string.restore_category_counts,
                            preview.preview.applicationCounts[category] ?: 0,
                            preview.preview.categories[category] ?: 0,
                        ),
                        modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                    )
                    val conflicts = preview.preview.conflictCounts[category] ?: 0
                    if (conflicts > 0) {
                        androidx.compose.material3.Text(
                            text = stringResource(R.string.restore_category_conflicts, conflicts),
                            modifier = Modifier.padding(start = 16.dp, bottom = 2.dp),
                        )
                    }
                    if (preview.mode == io.legado.app.domain.model.RestoreMode.BY_CATEGORY) {
                        val categoryMode = preview.categoryModes[category] ?: io.legado.app.domain.model.RestoreMode.KEEP_APPLICATION
                        androidx.compose.material3.Text(
                            text = stringResource(
                                if (categoryMode == io.legado.app.domain.model.RestoreMode.USE_BACKUP) {
                                    R.string.restore_category_use_backup
                                } else {
                                    R.string.restore_category_keep_application
                                }
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(start = 16.dp, bottom = 6.dp)
                                .clip(RoundedCornerShape(8.dp))
                                .background(LegadoTheme.colorScheme.primary.copy(alpha = 0.12f))
                                .clickable {
                                    onIntent(
                                        BackupConfigIntent.SetRestorePreviewCategoryMode(
                                            category,
                                            if (categoryMode == io.legado.app.domain.model.RestoreMode.USE_BACKUP) {
                                                io.legado.app.domain.model.RestoreMode.KEEP_APPLICATION
                                            } else {
                                                io.legado.app.domain.model.RestoreMode.USE_BACKUP
                                            },
                                        )
                                    )
                                }
                                .padding(horizontal = 12.dp, vertical = 8.dp),
                            color = LegadoTheme.colorScheme.primary,
                        )
                    }
                }
            }
            }
        },
        confirmText = stringResource(R.string.restore),
        onConfirm = { preview?.let {
            if (it.mode == RestoreMode.CLEAR_THEN_RESTORE) {
                onIntent(BackupConfigIntent.RequestClearRestoreConfirmation(it.uri, it.networkName, it.selectedCategories))
            } else if (it.networkName != null) onIntent(BackupConfigIntent.ConfirmNetworkRestorePreview(it.networkName))
            else onIntent(BackupConfigIntent.ConfirmRestorePreview(it.uri))
        } },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(BackupConfigIntent.DismissDialog) },
    )

    val clearRestore = dialog as? BackupConfigDialog.ConfirmClearRestore
    var clearCategoriesExpanded by remember(clearRestore?.selectedCategories) { mutableStateOf(false) }
    AppAlertDialog(
        contentMaxHeight = adaptiveBackupDialogHeight(fraction = 0.32f),
        fixedContentHeight = true,
        show = clearRestore != null,
        onDismissRequest = { onIntent(BackupConfigIntent.DismissDialog) },
        title = stringResource(R.string.restore_clear_confirm_title),
        text = stringResource(R.string.restore_clear_confirm_message),
        content = {
            val categories = clearRestore?.selectedCategories.orEmpty().toList()
            Text(
                text = stringResource(R.string.restore_clear_confirm_count, categories.size),
                color = LegadoTheme.colorScheme.error,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            Text(
                text = restoreCategoryTitles(
                    if (clearCategoriesExpanded) categories else categories.take(4)
                ) + if (!clearCategoriesExpanded && categories.size > 4) " …" else "",
                modifier = Modifier.padding(bottom = 6.dp),
            )
            if (categories.size > 4) {
                MediumTonalButton(
                    text = stringResource(
                        if (clearCategoriesExpanded) R.string.restore_clear_confirm_collapse
                        else R.string.restore_clear_confirm_view_all
                    ),
                    onClick = { clearCategoriesExpanded = !clearCategoriesExpanded },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmText = stringResource(R.string.restore_clear_confirm_action),
        onConfirm = {
            clearRestore?.let {
                onIntent(BackupConfigIntent.ConfirmClearRestore(it.uri, it.networkName, it.selectedCategories))
            }
        },
        dismissText = stringResource(R.string.cancel),
        onDismiss = { onIntent(BackupConfigIntent.DismissDialog) },
    )
}

@Composable
private fun restoreCategoryTitle(category: String): String = stringResource(
    when (category) {
        "bookshelf" -> R.string.restore_category_bookshelf
        "bookmark" -> R.string.restore_category_bookmark
        "bookGroup" -> R.string.restore_category_book_group
        "bookSource" -> R.string.restore_category_book_source
        "rssSource" -> R.string.restore_category_rss_source
        "rssStar" -> R.string.restore_category_rss_star
        "replaceRule" -> R.string.restore_category_replace_rule
        "readRecord" -> R.string.restore_category_read_record
        "searchHistory" -> R.string.restore_category_search_history
        "server" -> R.string.restore_category_server
        "sourceSub" -> R.string.restore_category_source_sub
        "txtTocRule" -> R.string.restore_category_txt_toc
        "httpTTS" -> R.string.restore_category_http_tts
        "keyboardAssists" -> R.string.restore_category_keyboard_assists
        "dictRule" -> R.string.restore_category_dict_rule
        "homepageModules" -> R.string.restore_category_homepage_modules
        "homepageCustomSets" -> R.string.restore_category_homepage_sets
        "highlightRule" -> R.string.restore_category_highlight_rule
        "highlightTagRule" -> R.string.restore_category_highlight_tag_rule
        "tagGroupRule" -> R.string.restore_category_tag_group_rule
        "directLinkUploadRule" -> R.string.restore_category_direct_link
        "readSettings" -> R.string.restore_category_read_settings
        "settings" -> R.string.restore_category_settings
        "readConfig" -> R.string.restore_category_reader
        "themeConfig" -> R.string.restore_category_theme
        "coverConfig" -> R.string.restore_category_cover
        "resources" -> R.string.restore_category_resources
        else -> R.string.restore_category_other
    },
)

@Composable
private fun restoreCategoryTitles(categories: Collection<String>): String {
    val titles = ArrayList<String>(categories.size)
    for (category in categories) titles += restoreCategoryTitle(category)
    return titles.joinToString(separator = "、")
}

/** 将内部配置键归并为用户可理解的设置类别，避免在结果弹窗泄露实现细节。 */
@Composable
private fun configDetailTitles(result: io.legado.app.domain.model.BackupRestoreResult): List<String> {
    val keys = result.failedConfigKeyNames + result.notSetConfigKeyNames +
        result.unsupportedConfigKeyNames + result.ignoredConfigKeyNames +
        result.decryptionFailedConfigKeyNames
    val groups = linkedSetOf<String>()
    keys.forEach { key ->
        groups += when {
            key.contains("webdav", ignoreCase = true) || key.contains("server", ignoreCase = true) ||
                key.contains("network", ignoreCase = true) || key.contains("http", ignoreCase = true) ->
                stringResource(R.string.backup_config_detail_network)
            key.contains("theme", ignoreCase = true) || key.contains("color", ignoreCase = true) ||
                key.contains("background", ignoreCase = true) ->
                stringResource(R.string.backup_config_detail_theme)
            key.contains("read", ignoreCase = true) || key.contains("textSelect", ignoreCase = true) ->
                stringResource(R.string.backup_config_detail_reader)
            key.contains("book", ignoreCase = true) || key.contains("shelf", ignoreCase = true) ->
                stringResource(R.string.backup_config_detail_bookshelf)
            else -> stringResource(R.string.backup_config_detail_other)
        }
    }
    return groups.toList()
}

private fun detailCountForRestore(result: BackupRestoreResult): Int =
    result.failedCategoryDetails.values.sumOf { it.distinct().size }

/** 只展示实际存在的备份配置异常，避免让用户误以为 0 也是异常。 */
@Composable
private fun backupAnomalySummary(result: BackupRestoreResult): String {
    val items = buildList {
        if (result.ignoredConfigKeys > 0) {
            add(stringResource(R.string.backup_config_ignored, result.ignoredConfigKeys))
        }
        if (result.failedConfigKeys > 0) {
            add(stringResource(R.string.backup_config_failed, result.failedConfigKeys))
        }
        if (result.unsupportedConfigKeys > 0) {
            add(stringResource(R.string.backup_config_unsupported, result.unsupportedConfigKeys))
        }
    }
    return items.joinToString(stringResource(R.string.backup_config_summary_separator))
}

/** 恢复失败类别中可能包含凭据的类别，用于明确提示密码错误影响。 */
private fun isSensitiveRestoreCategory(category: String): Boolean = category in setOf(
    "bookSource",
    "rssSource",
    "httpTTS",
    "server",
    "directLinkUploadRule",
)

@Composable
private fun readableRestoreFailure(
    detail: String,
    configParseFailed: Boolean = false,
    configWriteFailed: Boolean = false,
): String =
    if (configParseFailed ||
        detail.contains("disallow-doctype-decl", ignoreCase = true) ||
        detail.contains("SAXParseException", ignoreCase = true) ||
        detail.contains("DOCTYPE", ignoreCase = true)
    ) {
        stringResource(R.string.restore_config_parse_user_error)
    } else if (detail.contains("BAD_DECRYPT", ignoreCase = true) ||
        detail.contains("Cipher", ignoreCase = true) ||
        detail.contains("解密失败")
    ) {
        stringResource(R.string.restore_sensitive_password_failed)
    } else if (configWriteFailed) {
        stringResource(R.string.restore_config_apply_user_error)
    } else {
        detail
    }

@Composable
private fun restoreOutcomeTitle(outcome: io.legado.app.domain.model.BackupRestoreOutcome): String =
    stringResource(
        when (outcome) {
            io.legado.app.domain.model.BackupRestoreOutcome.SUCCESS -> R.string.restore_success
            io.legado.app.domain.model.BackupRestoreOutcome.SUCCESS_WITH_WARNINGS -> R.string.restore_success_with_warnings
            io.legado.app.domain.model.BackupRestoreOutcome.PARTIAL -> R.string.restore_partial
            io.legado.app.domain.model.BackupRestoreOutcome.FAILED -> R.string.restore_fail
            io.legado.app.domain.model.BackupRestoreOutcome.PRECONDITION_FAILED -> R.string.restore_fail_with_error
        }
    )

@Composable
private fun BackupRestoreStage.localizedTitle(): String = stringResource(
    when (this) {
        BackupRestoreStage.PREPARE -> R.string.backup_stage_prepare
        BackupRestoreStage.EXPORT_DATABASE -> R.string.backup_stage_database
        BackupRestoreStage.PROCESS_RESOURCES -> R.string.backup_stage_resources
        BackupRestoreStage.GENERATE_MANIFEST -> R.string.backup_stage_manifest
        BackupRestoreStage.GENERATE_ZIP -> R.string.backup_stage_zip
        BackupRestoreStage.WRITE_LOCAL -> R.string.backup_stage_local
        BackupRestoreStage.UPLOAD_WEBDAV -> R.string.backup_stage_webdav
        BackupRestoreStage.READ_BACKUP -> R.string.restore_stage_read
        BackupRestoreStage.RESTORE_BOOKSHELF -> R.string.restore_stage_bookshelf
        BackupRestoreStage.RESTORE_DATABASE -> R.string.restore_stage_database
        BackupRestoreStage.RESTORE_CONFIG -> R.string.restore_stage_config
        BackupRestoreStage.RESTORE_RESOURCES -> R.string.restore_stage_resources
        BackupRestoreStage.REFRESH_RUNTIME -> R.string.restore_stage_refresh
        BackupRestoreStage.COMPLETE -> R.string.restore_stage_complete
    }
)


@Composable
fun ConfirmDialog(
    show: Boolean,
    title: String,
    text: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AppAlertDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = title,
        text = text,
        confirmText = stringResource(R.string.ok),
        onConfirm = onConfirm,
        dismissText = stringResource(R.string.cancel),
        onDismiss = onDismiss,
    )
}
