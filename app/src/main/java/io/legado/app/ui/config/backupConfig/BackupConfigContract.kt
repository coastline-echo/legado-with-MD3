package io.legado.app.ui.config.backupConfig

import androidx.annotation.StringRes
import androidx.compose.runtime.Stable
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.domain.model.BackupSensitiveCategory
import kotlinx.collections.immutable.ImmutableList
import kotlinx.collections.immutable.persistentListOf

@Stable
data class BackupConfigUiState(
    val settings: BackupSettings = BackupSettings(),
    val activeSheet: BackupConfigSheet? = null,
    val activeDialog: BackupConfigDialog? = null,
    val backupNames: ImmutableList<String> = persistentListOf(),
    val ignoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val dbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val backupDbIgnoreItems: ImmutableList<BackupIgnoreItem> = persistentListOf(),
    val sensitiveItems: ImmutableList<BackupSensitiveItem> = persistentListOf(),
)

@Stable
data class BackupIgnoreItem(
    val key: String,
    val title: String,
    val checked: Boolean,
)

@Stable
data class BackupSensitiveItem(
    val category: BackupSensitiveCategory,
    val title: String,
    val checked: Boolean,
    val expanded: Boolean = false,
)

sealed interface BackupConfigSheet {
    data object ChooseBackupPath : BackupConfigSheet
    data object ChooseBackupAndRun : BackupConfigSheet
    data object BackupOptions : BackupConfigSheet
    data object RestoreOptions : BackupConfigSheet
    data object RestoreFiles : BackupConfigSheet
    data object IgnoreRestoreItems : BackupConfigSheet
    data object IgnoreBackupItems : BackupConfigSheet
    data object SensitiveItems : BackupConfigSheet
}

sealed interface BackupConfigDialog {
    data class WebDavAuth(
        val account: String,
        val password: String,
        val passwordVisible: Boolean = false,
    ) : BackupConfigDialog

    data class ConfirmLocalRestoreFallback(val error: String?) : BackupConfigDialog
    data class RestorePreview(
        val uri: String,
        val preview: io.legado.app.domain.model.RestorePreview,
        val mode: RestoreMode,
        val selectedCategories: Set<String> = emptySet(),
    ) : BackupConfigDialog
    data class Loading(@StringRes val titleRes: Int) : BackupConfigDialog
}

sealed interface BackupConfigIntent {
    data class SetWebDavUrl(val value: String) : BackupConfigIntent
    data class SetWebDavDir(val value: String) : BackupConfigIntent
    data class SetWebDavDeviceName(val value: String) : BackupConfigIntent
    data class SetSyncBookProgress(val value: Boolean) : BackupConfigIntent
    data class SetSyncBookProgressPlus(val value: Boolean) : BackupConfigIntent
    data class SetAutoCheckNewBackup(val value: Boolean) : BackupConfigIntent
    data class SetOnlyLatestBackup(val value: Boolean) : BackupConfigIntent
    data class SetBackupSyncMode(val value: String) : BackupConfigIntent
    data class OpenSheet(val sheet: BackupConfigSheet) : BackupConfigIntent
    data object DismissSheet : BackupConfigIntent
    data object OpenWebDavAuth : BackupConfigIntent
    data class EditWebDavAccount(val value: String) : BackupConfigIntent
    data class EditWebDavPassword(val value: String) : BackupConfigIntent
    data object TogglePasswordVisibility : BackupConfigIntent
    data object SaveWebDavAuth : BackupConfigIntent
    data object TestWebDav : BackupConfigIntent
    data object OpenIgnoreDialog : BackupConfigIntent
    data class ToggleIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveIgnoreItems : BackupConfigIntent
    data object OpenBackupIgnoreDialog : BackupConfigIntent
    data class ToggleBackupIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupIgnoreItems : BackupConfigIntent
    data class ToggleDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveDbIgnoreItems : BackupConfigIntent
    data class ToggleBackupDbIgnoreItem(val key: String, val value: Boolean) : BackupConfigIntent
    data object SaveBackupDbIgnoreItems : BackupConfigIntent
    data object OpenSensitiveItems : BackupConfigIntent
    data class ToggleSensitiveItem(val category: BackupSensitiveCategory, val value: Boolean) : BackupConfigIntent
    data class ToggleSensitiveExpanded(val category: BackupSensitiveCategory) : BackupConfigIntent
    data object SaveSensitiveItems : BackupConfigIntent
    data object DismissDialog : BackupConfigIntent
    data object SelectBackupDirectory : BackupConfigIntent
    data object SelectBackupAndRunDirectory : BackupConfigIntent
    data class BackupDirectorySelected(val path: String, val runBackup: Boolean) : BackupConfigIntent
    data object BackupDirectorySelectionCancelled : BackupConfigIntent
    data class RequestBackup(val mode: String) : BackupConfigIntent
    data class PerformBackup(val path: String, val mode: String) : BackupConfigIntent
    data class RequestLocalRestore(val mode: RestoreMode = RestoreMode.KEEP_APPLICATION) : BackupConfigIntent
    data class RestoreLocal(val uri: String) : BackupConfigIntent
    data class ConfirmRestorePreview(val uri: String) : BackupConfigIntent
    data class ToggleRestorePreviewCategory(val category: String) : BackupConfigIntent
    data class RequestNetworkRestore(val mode: RestoreMode = RestoreMode.KEEP_APPLICATION) : BackupConfigIntent
    data class RestoreNetwork(val name: String, val mode: RestoreMode = RestoreMode.KEEP_APPLICATION) : BackupConfigIntent
    data object ConfirmLocalRestoreFallback : BackupConfigIntent
    data object RequestImportOldData : BackupConfigIntent
}

sealed interface BackupConfigEffect {
    data object LaunchBackupDirectoryPicker : BackupConfigEffect
    data object LaunchBackupAndRunDirectoryPicker : BackupConfigEffect
    data object LaunchRestoreFilePicker : BackupConfigEffect
    data object LaunchImportOldDataPicker : BackupConfigEffect
    data object BackupDirectorySelectionCancelled : BackupConfigEffect
    data class RequestStoragePermission(val path: String, val mode: String) : BackupConfigEffect
    data class ShowMessage(@StringRes val messageRes: Int, val argument: String? = null) : BackupConfigEffect
}
