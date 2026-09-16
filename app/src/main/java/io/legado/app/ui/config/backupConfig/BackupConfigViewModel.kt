package io.legado.app.ui.config.backupConfig

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import io.legado.app.R
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.model.settings.BackupSettings
import io.legado.app.domain.usecase.BackupRestoreUseCase
import io.legado.app.domain.usecase.WebDavBackupUseCase
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.BackupSensitiveCategory
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupRestoreProgressBus
import io.legado.app.help.storage.BackupRestoreResultBus
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.utils.isContentScheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class BackupConfigViewModel(
    private val settingsGateway: BackupSettingsGateway,
    private val webDavBackupUseCase: WebDavBackupUseCase,
    private val backupRestoreUseCase: BackupRestoreUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        BackupConfigUiState(
            settings = settingsGateway.currentSettings,
            activeSheet = io.legado.app.help.config.LocalConfig.backupSensitiveNoticeShown
                .takeIf { it == false }
                ?.let { BackupConfigSheet.SensitiveItems },
            ignoreItems = loadIgnoreItems(),
            backupIgnoreItems = loadBackupIgnoreItems(),
            dbIgnoreItems = loadDbIgnoreItems(),
            backupDbIgnoreItems = loadBackupDbIgnoreItems(),
            sensitiveItems = loadSensitiveItems(),
            backupPassword = io.legado.app.help.storage.BackupConfig.backupPassword.orEmpty(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BackupConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
    }

    fun onIntent(intent: BackupConfigIntent) {
        when (intent) {
            is BackupConfigIntent.SetWebDavUrl -> update { it.copy(webDavUrl = intent.value) }
            is BackupConfigIntent.SetWebDavDir -> update { it.copy(webDavDir = intent.value) }
            is BackupConfigIntent.SetWebDavDeviceName ->
                update { it.copy(webDavDeviceName = intent.value) }
            is BackupConfigIntent.SetSyncBookProgress -> setSyncBookProgress(intent.value)
            is BackupConfigIntent.SetSyncBookProgressPlus ->
                update { it.copy(syncBookProgressPlus = intent.value) }
            is BackupConfigIntent.SetAutoCheckNewBackup ->
                update { it.copy(autoCheckNewBackup = intent.value) }
            is BackupConfigIntent.SetOnlyLatestBackup ->
                update { it.copy(onlyLatestBackup = intent.value) }
            is BackupConfigIntent.SetBackupSyncMode ->
                update { it.copy(backupSyncMode = intent.value) }
            is BackupConfigIntent.OpenSheet -> _uiState.update { it.copy(activeSheet = intent.sheet) }
            BackupConfigIntent.DismissSheet -> _uiState.update { it.copy(activeSheet = null) }
            BackupConfigIntent.OpenWebDavAuth -> openWebDavAuth()
            is BackupConfigIntent.EditWebDavAccount -> updateAuth { it.copy(account = intent.value) }
            is BackupConfigIntent.EditWebDavPassword -> updateAuth { it.copy(password = intent.value) }
            BackupConfigIntent.TogglePasswordVisibility ->
                updateAuth { it.copy(passwordVisible = !it.passwordVisible) }
            BackupConfigIntent.SaveWebDavAuth -> saveWebDavAuth()
            BackupConfigIntent.TestWebDav -> testWebDav()
            BackupConfigIntent.OpenIgnoreDialog ->
                _uiState.update { it.copy(activeSheet = BackupConfigSheet.IgnoreRestoreItems) }
            is BackupConfigIntent.ToggleIgnoreItem -> toggleIgnoreItem(intent.key, intent.value)
            BackupConfigIntent.SaveIgnoreItems -> saveIgnoreItems()
            BackupConfigIntent.OpenBackupIgnoreDialog ->
                _uiState.update { it.copy(activeSheet = BackupConfigSheet.IgnoreBackupItems) }

            is BackupConfigIntent.ToggleBackupIgnoreItem -> toggleBackupIgnoreItem(
                intent.key,
                intent.value
            )

            BackupConfigIntent.SaveBackupIgnoreItems -> saveBackupIgnoreItems()
            is BackupConfigIntent.ToggleDbIgnoreItem -> toggleDbIgnoreItem(intent.key, intent.value)
            BackupConfigIntent.SaveDbIgnoreItems -> saveDbIgnoreItems()
            is BackupConfigIntent.ToggleBackupDbIgnoreItem -> toggleBackupDbIgnoreItem(
                intent.key,
                intent.value
            )

            BackupConfigIntent.SaveBackupDbIgnoreItems -> saveBackupDbIgnoreItems()
            BackupConfigIntent.OpenSensitiveItems ->
                _uiState.update { it.copy(activeSheet = BackupConfigSheet.SensitiveItems) }
            is BackupConfigIntent.ToggleSensitiveItem -> _uiState.update { state ->
                state.copy(sensitiveItems = state.sensitiveItems.map { item ->
                    if (item.category == intent.category) item.copy(checked = intent.value) else item
                }.toImmutableList())
            }
            is BackupConfigIntent.ToggleSensitiveExpanded -> _uiState.update { state ->
                state.copy(sensitiveItems = state.sensitiveItems.map { item ->
                    if (item.category == intent.category) item.copy(expanded = !item.expanded) else item
                }.toImmutableList())
            }
            BackupConfigIntent.SaveSensitiveItems -> saveSensitiveItems()
            is BackupConfigIntent.SetBackupPassword -> _uiState.update { it.copy(backupPassword = intent.value) }
            BackupConfigIntent.ToggleConfigReportDetails -> _uiState.update { state ->
                val dialog = state.activeDialog as? BackupConfigDialog.ConfigIntegrityReport
                    ?: return@update state
                state.copy(activeDialog = dialog.copy(expanded = !dialog.expanded))
            }
            BackupConfigIntent.DismissDialog -> _uiState.update { it.copy(activeDialog = null) }
            BackupConfigIntent.SelectBackupDirectory -> launchDirectoryPicker(runBackup = false)
            BackupConfigIntent.SelectBackupAndRunDirectory -> launchDirectoryPicker(runBackup = true)
            is BackupConfigIntent.BackupDirectorySelected -> saveBackupPath(intent.path, intent.runBackup)
            BackupConfigIntent.BackupDirectorySelectionCancelled ->
                _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.backup_directory_not_selected))
            is BackupConfigIntent.RequestBackup -> requestBackup(intent.mode)
            is BackupConfigIntent.PerformBackup -> performBackup(intent.path, intent.mode)
            is BackupConfigIntent.RequestLocalRestore -> requestLocalRestore(intent.mode)
            is BackupConfigIntent.RestoreLocal -> restoreLocal(intent.uri)
            is BackupConfigIntent.ConfirmRestorePreview -> performRestoreLocal(intent.uri)
            is BackupConfigIntent.ConfirmNetworkRestorePreview -> performRestoreNetwork(intent.name)
            is BackupConfigIntent.ToggleRestorePreviewCategory -> _uiState.update { state ->
                val dialog = state.activeDialog as? BackupConfigDialog.RestorePreview ?: return@update state
                val selected = if (intent.category in dialog.selectedCategories) {
                    dialog.selectedCategories - intent.category
                } else {
                    dialog.selectedCategories + intent.category
                }
                state.copy(activeDialog = dialog.copy(selectedCategories = selected))
            }
            is BackupConfigIntent.SetRestorePreviewCategoryMode -> _uiState.update { state ->
                val dialog = state.activeDialog as? BackupConfigDialog.RestorePreview ?: return@update state
                state.copy(activeDialog = dialog.copy(categoryModes = dialog.categoryModes + (intent.category to intent.mode)))
            }
            is BackupConfigIntent.SetRestoreBackupPassword -> _uiState.update { state ->
                val dialog = state.activeDialog as? BackupConfigDialog.RestorePreview ?: return@update state
                state.copy(activeDialog = dialog.copy(backupPassword = intent.value))
            }
            is BackupConfigIntent.RequestNetworkRestore -> loadNetworkBackups(intent.mode)
            is BackupConfigIntent.RestoreNetwork -> restoreNetwork(intent.name, selectedRestoreMode)
            BackupConfigIntent.ConfirmLocalRestoreFallback -> requestLocalRestore(RestoreMode.KEEP_APPLICATION)
            BackupConfigIntent.RequestImportOldData -> _uiState.update {
                it.copy(activeSheet = BackupConfigSheet.ImportOldData)
            }
            BackupConfigIntent.SelectOldDataFile -> {
                _uiState.update { it.copy(activeSheet = null) }
                _effects.tryEmit(BackupConfigEffect.LaunchImportOldDataFilePicker)
            }
            BackupConfigIntent.SelectOldDataDirectory -> {
                _uiState.update { it.copy(activeSheet = null) }
                _effects.tryEmit(BackupConfigEffect.LaunchImportOldDataDirectoryPicker)
            }
        }
    }

    private fun update(transform: (BackupSettings) -> BackupSettings) {
        viewModelScope.launch { settingsGateway.update(transform) }
    }

    private fun setSyncBookProgress(value: Boolean) {
        viewModelScope.launch {
            settingsGateway.update {
                it.copy(
                    syncBookProgress = value,
                    syncBookProgressPlus = it.syncBookProgressPlus && value,
                )
            }
        }
    }

    private fun openWebDavAuth() {
        val settings = _uiState.value.settings
        _uiState.update {
            it.copy(
                activeDialog = BackupConfigDialog.WebDavAuth(
                    account = settings.webDavAccount,
                    password = settings.webDavPassword,
                )
            )
        }
    }

    private fun updateAuth(transform: (BackupConfigDialog.WebDavAuth) -> BackupConfigDialog.WebDavAuth) {
        _uiState.update { state ->
            val dialog = state.activeDialog as? BackupConfigDialog.WebDavAuth ?: return@update state
            state.copy(activeDialog = transform(dialog))
        }
    }

    private fun saveWebDavAuth() {
        val dialog = _uiState.value.activeDialog as? BackupConfigDialog.WebDavAuth ?: return
        viewModelScope.launch {
            settingsGateway.update {
                it.copy(webDavAccount = dialog.account, webDavPassword = dialog.password)
            }
            testWebDav()
        }
    }

    private fun testWebDav() {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.test_sync_loading_text)) }
        viewModelScope.launch {
            val success = withContext(Dispatchers.IO) {
                runCatching { webDavBackupUseCase.test() }.getOrDefault(false)
            }
            _uiState.update { it.copy(activeDialog = null) }
            _effects.tryEmit(
                BackupConfigEffect.ShowMessage(
                    if (success) R.string.test_sync_status_success else R.string.test_sync_status_fail
                )
            )
        }
    }

    private fun toggleIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                ignoreItems = state.ignoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveIgnoreItems() {
        _uiState.value.ignoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.ignoreConfig[item.key] = item.checked
        }
        _uiState.value.dbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveIgnoreConfig()
        io.legado.app.help.storage.BackupConfig.saveDbIgnoreConfig()
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun toggleBackupIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                backupIgnoreItems = state.backupIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveBackupIgnoreItems() {
        _uiState.value.backupIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupIgnoreConfig[item.key] = item.checked
        }
        _uiState.value.backupDbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveBackupIgnoreConfig()
        io.legado.app.help.storage.BackupConfig.saveBackupDbIgnoreConfig()
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun toggleDbIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                dbIgnoreItems = state.dbIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveDbIgnoreItems() {
        _uiState.value.dbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveDbIgnoreConfig()
    }

    private fun toggleBackupDbIgnoreItem(key: String, value: Boolean) {
        _uiState.update { state ->
            state.copy(
                backupDbIgnoreItems = state.backupDbIgnoreItems.map { item ->
                    if (item.key == key) item.copy(checked = value) else item
                }.toImmutableList()
            )
        }
    }

    private fun saveBackupDbIgnoreItems() {
        _uiState.value.backupDbIgnoreItems.forEach { item ->
            io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[item.key] = item.checked
        }
        io.legado.app.help.storage.BackupConfig.saveBackupDbIgnoreConfig()
    }

    private fun launchDirectoryPicker(runBackup: Boolean) {
        _uiState.update { it.copy(activeSheet = null) }
        _effects.tryEmit(
            if (runBackup) BackupConfigEffect.LaunchBackupAndRunDirectoryPicker
            else BackupConfigEffect.LaunchBackupDirectoryPicker
        )
    }

    private fun saveBackupPath(path: String, runBackup: Boolean) {
        viewModelScope.launch {
            settingsGateway.update { it.copy(backupPath = path) }
            if (runBackup && path.isNotEmpty()) requestBackup("both", path)
        }
    }

    private fun requestBackup(mode: String, selectedPath: String? = null) {
        _uiState.update { it.copy(activeSheet = null) }
        val path = selectedPath ?: _uiState.value.settings.backupPath.orEmpty()
        if (path.isEmpty() && mode != "webdav") {
            _effects.tryEmit(BackupConfigEffect.LaunchBackupAndRunDirectoryPicker)
            return
        }
        viewModelScope.launch {
            BackupRestoreProgressBus.events.collect { progress ->
                _uiState.update { state ->
                    val loading = state.activeDialog as? BackupConfigDialog.Loading
                        ?: return@update state
                    state.copy(activeDialog = loading.copy(progress = progress))
                }
            }
        }
        viewModelScope.launch {
            BackupRestoreResultBus.events.collect { result ->
                _uiState.update { it.copy(lastResult = result) }
            }
        }
        if (path.isNotEmpty() && !path.isContentScheme()) {
            _effects.tryEmit(BackupConfigEffect.RequestStoragePermission(path, mode))
        } else {
            performBackup(path, mode)
        }
    }

    private fun performBackup(path: String, mode: String) {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.backup)) }
        viewModelScope.launch(Dispatchers.IO) {
                runCatching { backupRestoreUseCase.backup(path, mode) }
                .onSuccess {
                    withContext(Dispatchers.Main) {
                        val result = BackupRestoreResultBus.latest
                        if (result == null) {
                            _uiState.update { it.copy(activeDialog = null) }
                            _effects.tryEmit(BackupConfigEffect.ShowMessage(R.string.backup_success))
                        } else {
                            _uiState.update { it.copy(activeDialog = BackupConfigDialog.ConfigIntegrityReport(result)) }
                        }
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _uiState.update { it.copy(activeDialog = null) }
                        _effects.tryEmit(
                            BackupConfigEffect.ShowMessage(R.string.backup_fail, error.localizedMessage)
                        )
                    }
                }
        }
    }

    private var selectedRestoreMode = RestoreMode.KEEP_APPLICATION

    private fun requestLocalRestore(mode: RestoreMode) {
        selectedRestoreMode = mode
        _uiState.update { it.copy(activeSheet = null, activeDialog = null) }
        _effects.tryEmit(BackupConfigEffect.LaunchRestoreFilePicker)
    }

    private fun restoreLocal(uri: String) {
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { backupRestoreUseCase.previewLocal(Uri.parse(uri).toString()) }
                .onSuccess { preview -> withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            activeDialog = BackupConfigDialog.RestorePreview(
                                uri = uri,
                                preview = preview,
                                mode = selectedRestoreMode,
                                selectedCategories = preview.categories.keys,
                                categoryModes = preview.categories.keys.associateWith { RestoreMode.KEEP_APPLICATION },
                                backupPassword = io.legado.app.help.storage.BackupConfig.backupPassword.orEmpty(),
                            )
                        )
                    }
                } }
                .onFailure { finishRestoreFailure(it.localizedMessage) }
        }
    }

    private fun saveSensitiveItems() {
        val selected = _uiState.value.sensitiveItems.filter { it.checked }
            .map { it.category }.toSet()
        io.legado.app.help.storage.BackupConfig.backupPassword =
            _uiState.value.backupPassword
        io.legado.app.help.storage.BackupConfig.saveSensitiveSelection(
            io.legado.app.domain.model.BackupSensitiveSelection(selected = selected)
        )
        io.legado.app.help.config.LocalConfig.backupSensitiveNoticeShown = true
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun performRestoreLocal(uri: String) {
        val dialog = _uiState.value.activeDialog as? BackupConfigDialog.RestorePreview
        val categories = dialog?.selectedCategories.orEmpty()
        val backupPassword = dialog?.backupPassword.orEmpty()
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                backupRestoreUseCase.restoreLocal(
                    Uri.parse(uri).toString(),
                    selectedRestoreMode,
                    categories,
                    dialog?.categoryModes.orEmpty(),
                    backupPassword,
                )
            }
                .fold(
                    onSuccess = { finishRestoreSuccess() },
                    onFailure = { finishRestoreFailure(it.localizedMessage) },
                )
        }
    }

    private fun loadNetworkBackups(mode: RestoreMode) {
        selectedRestoreMode = mode
        _uiState.update {
            it.copy(activeSheet = null, activeDialog = BackupConfigDialog.Loading(R.string.loading))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.getBackupNames() }
                .onSuccess { names ->
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                activeDialog = null,
                                activeSheet = BackupConfigSheet.RestoreFiles,
                                backupNames = names.toImmutableList(),
                            )
                        }
                    }
                }
                .onFailure { error ->
                    withContext(Dispatchers.Main) {
                        _uiState.update {
                            it.copy(
                                activeDialog = BackupConfigDialog.ConfirmLocalRestoreFallback(
                                    error.localizedMessage
                                )
                            )
                        }
                    }
                }
        }
    }

    private fun restoreNetwork(name: String, mode: RestoreMode) {
        selectedRestoreMode = mode
        _uiState.update {
            it.copy(activeSheet = null, activeDialog = BackupConfigDialog.Loading(R.string.loading))
        }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.preview(name) }.fold(
                onSuccess = { preview -> withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            activeDialog = BackupConfigDialog.RestorePreview(
                                uri = "",
                                preview = preview,
                                mode = selectedRestoreMode,
                                networkName = name,
                                selectedCategories = preview.categories.keys,
                                categoryModes = preview.categories.keys.associateWith { RestoreMode.KEEP_APPLICATION },
                                backupPassword = io.legado.app.help.storage.BackupConfig.backupPassword.orEmpty(),
                            )
                        )
                    }
                } },
                onFailure = { finishRestoreFailure(it.localizedMessage, webDav = true) },
            )
        }
    }

    private fun performRestoreNetwork(name: String) {
        val previewDialog = _uiState.value.activeDialog as? BackupConfigDialog.RestorePreview
        val password = previewDialog?.backupPassword.orEmpty()
        val categoryModes = previewDialog?.categoryModes.orEmpty()
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.restore(name, selectedRestoreMode, categoryModes, password) }
                .fold(onSuccess = { finishRestoreSuccess() }, onFailure = { finishRestoreFailure(it.localizedMessage, webDav = true) })
        }
    }

    private suspend fun finishRestoreSuccess() = withContext(Dispatchers.Main) {
        val result = BackupRestoreResultBus.latest ?: _uiState.value.lastResult
        val details = result?.failedCategoryCounts
            ?.entries
            ?.joinToString { (category, count) -> "$category($count)" }
            ?.takeUnless { it.isNullOrBlank() }
            ?: result?.failedCategories?.joinToString().takeUnless { it.isNullOrBlank() }
        _uiState.update { it.copy(activeDialog = null) }
        _effects.tryEmit(
            BackupConfigEffect.ShowMessage(
                when {
                    result?.configFileMissing == true -> R.string.restore_config_missing
                    result?.configFileParseFailed == true -> R.string.restore_config_parse_failed
                    result?.outcome == BackupRestoreOutcome.PARTIAL -> R.string.restore_partial
                    result?.outcome == BackupRestoreOutcome.FAILED -> R.string.restore_failed_with_categories
                    result?.outcome == BackupRestoreOutcome.SUCCESS_WITH_WARNINGS -> R.string.restore_success_with_warnings
                    else -> R.string.restore_success
                },
                details.orEmpty(),
            )
        )
    }

    private suspend fun finishRestoreFailure(message: String?, webDav: Boolean = false) =
        withContext(Dispatchers.Main) {
            _uiState.update { it.copy(activeDialog = null) }
            _effects.tryEmit(
                BackupConfigEffect.ShowMessage(
                    if (webDav) R.string.webdav_restore_fail else R.string.restore_fail_with_error,
                    message,
                )
            )
        }

    private companion object {
        fun loadIgnoreItems() = io.legado.app.help.storage.BackupConfig.ignoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.ignoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.ignoreConfig[key] ?: false,
                )
            }
            .toImmutableList()

        fun loadBackupIgnoreItems() = io.legado.app.help.storage.BackupConfig.backupIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.backupIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.backupIgnoreConfig[key]
                        ?: false,
                )
            }
            .toImmutableList()

        fun loadDbIgnoreItems() = io.legado.app.help.storage.BackupConfig.dbIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.dbIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[key] ?: false,
                )
            }
            .toImmutableList()

        fun loadBackupDbIgnoreItems() = io.legado.app.help.storage.BackupConfig.backupDbIgnoreKeys
            .mapIndexed { index, key ->
                BackupIgnoreItem(
                    key = key,
                    title = io.legado.app.help.storage.BackupConfig.backupDbIgnoreTitle[index],
                    checked = io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[key]
                        ?: false,
                )
            }
            .toImmutableList()

        fun loadSensitiveItems() = BackupSensitiveCategory.entries.map { category ->
            BackupSensitiveItem(
                category = category,
                title = category.name,
                checked = io.legado.app.help.storage.BackupConfig.sensitiveSelection.includes(category),
                details = sensitiveDetails(category).toImmutableList(),
            )
        }.toImmutableList()

        /** 展开敏感类别时只展示稳定标识，不展示密码、Cookie 等原文。 */
        private fun sensitiveDetails(category: BackupSensitiveCategory): List<String> = when (category) {
            BackupSensitiveCategory.SERVER_CREDENTIALS -> Backup.sensitiveItemIdentifiers(category)
            BackupSensitiveCategory.BOOK_SOURCE_AUTH,
            BackupSensitiveCategory.REQUEST_HEADERS ->
                Backup.sensitiveItemIdentifiers(category)
            BackupSensitiveCategory.RSS_AUTH ->
                Backup.sensitiveItemIdentifiers(category)
            BackupSensitiveCategory.TTS_API_KEYS ->
                Backup.sensitiveItemIdentifiers(category)
            BackupSensitiveCategory.WEBDAV_CREDENTIALS -> listOf("webDavPassword")
            BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS -> listOf("directLinkUploadRule")
            BackupSensitiveCategory.OTHER -> emptyList()
        }
    }
}
