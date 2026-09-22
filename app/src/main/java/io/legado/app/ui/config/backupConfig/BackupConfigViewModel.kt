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
import io.legado.app.help.storage.BackupRestoreProgressBus
import io.legado.app.help.storage.BackupRestoreResultBus
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.exception.NoStackTraceException
import io.legado.app.utils.isContentScheme
import kotlinx.collections.immutable.toImmutableList
import kotlinx.collections.immutable.persistentListOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeout
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
            ignoreItems = persistentListOf(),
            backupIgnoreItems = persistentListOf(),
            dbIgnoreItems = persistentListOf(),
            backupDbIgnoreItems = persistentListOf(),
            sensitiveItems = persistentListOf(),
        )
    )
    val uiState = _uiState.asStateFlow()

    private val _effects = MutableSharedFlow<BackupConfigEffect>(extraBufferCapacity = 16)
    val effects = _effects.asSharedFlow()

    /** 当前页面正在等待的备份/恢复操作；拒绝其他操作的进度污染 Loading。 */
    private var activeOperationId: String? = null

    /** 选择目录后立即执行时，保留用户最初选择的备份目标。 */
    private var pendingPathBackupMode: String = "local"

    init {
        viewModelScope.launch {
            settingsGateway.settings.collect { settings ->
                _uiState.update { it.copy(settings = settings) }
            }
        }
        // 恢复与备份共用进度总线；不能等到发起过备份后才开始监听。
        viewModelScope.launch {
            BackupRestoreProgressBus.events.collect { progress ->
                val operationId = progress.operationId.takeIf { it.isNotBlank() }
                if (operationId != null) {
                    val current = activeOperationId
                    if (current == null) {
                        activeOperationId = operationId
                    } else if (current != operationId) {
                        return@collect
                    }
                }
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
        // 异步加载忽略配置和敏感选择，避免阻塞 UI 线程
        viewModelScope.launch(Dispatchers.IO) {
            _uiState.update { it.copy(
                ignoreItems = loadIgnoreItems(),
                backupIgnoreItems = loadBackupIgnoreItems(),
                dbIgnoreItems = loadDbIgnoreItems(),
                backupDbIgnoreItems = loadBackupDbIgnoreItems(),
                sensitiveItems = loadSensitiveItems(),
            ) }
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
            BackupConfigIntent.SaveSensitiveItems -> saveSensitiveItems()
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
            is BackupConfigIntent.RequestClearRestoreConfirmation -> _uiState.update {
                it.copy(activeDialog = BackupConfigDialog.ConfirmClearRestore(intent.uri, intent.networkName, intent.selectedCategories))
            }
            is BackupConfigIntent.ConfirmClearRestore -> {
                if (intent.networkName != null) performRestoreNetwork(intent.networkName, intent.selectedCategories)
                else performRestoreLocal(intent.uri, intent.selectedCategories)
            }
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
                state.copy(activeDialog = dialog.copy(localPassword = intent.value))
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
            if (runBackup && path.isNotEmpty()) requestBackup(pendingPathBackupMode, path)
        }
    }

    private fun requestBackup(mode: String, selectedPath: String? = null) {
        _uiState.update { it.copy(activeSheet = null) }
        val path = selectedPath ?: _uiState.value.settings.backupPath.orEmpty()
        if (path.isEmpty() && mode != "webdav") {
            pendingPathBackupMode = mode
            _effects.tryEmit(BackupConfigEffect.LaunchBackupAndRunDirectoryPicker)
            return
        }
        if (path.isNotEmpty() && !path.isContentScheme()) {
            _effects.tryEmit(BackupConfigEffect.RequestStoragePermission(path, mode))
        } else {
            performBackup(path, mode)
        }
    }

    private fun performBackup(path: String, mode: String) {
        activeOperationId = null
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.backup)) }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val operationId = when (mode) {
                    "webdav" -> {
                        val syncResult = webDavBackupUseCase.refreshConfig()
                        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
                        backupRestoreUseCase.backup(null, "webdav")
                    }
                    "both" -> {
                        val syncResult = webDavBackupUseCase.refreshConfig()
                        if (syncResult.isFailure) throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
                        backupRestoreUseCase.backup(path, "both")
                    }
                    else -> {
                        backupRestoreUseCase.backup(path, "local")
                    }
                }
                // 等待本次操作的结果（通过事件总线），而非依赖全局 latest
                val result = BackupRestoreResultBus.events
                    .first { it.operationId == operationId }
                activeOperationId = null
                BackupRestoreResultBus.removeResult(operationId)
                withContext(Dispatchers.Main) {
                    _uiState.update { it.copy(activeDialog = BackupConfigDialog.ConfigIntegrityReport(result)) }
                }
            } catch (error: Exception) {
                activeOperationId = null
                withContext(Dispatchers.Main) {
                    _uiState.update {
                        it.copy(
                            activeDialog = BackupConfigDialog.ConfigIntegrityReport(
                                result = BackupRestoreResult(
                                    outcome = BackupRestoreOutcome.FAILED,
                                    failures = 1,
                                    parseFailed = true,
                                ),
                                message = error.localizedMessage,
                            ),
                        )
                    }
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
                                localPassword = "",
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
        io.legado.app.help.storage.BackupConfig.saveSensitiveSelection(
            io.legado.app.domain.model.BackupSensitiveSelection(selected = selected)
        )
        io.legado.app.help.config.LocalConfig.backupSensitiveNoticeShown = true
        _uiState.update { it.copy(activeSheet = null) }
    }

    private fun performRestoreLocal(uri: String, confirmedCategories: Set<String> = emptySet()) {
        activeOperationId = null
        val dialog = _uiState.value.activeDialog as? BackupConfigDialog.RestorePreview
        val categories = dialog?.selectedCategories?.takeIf { it.isNotEmpty() } ?: confirmedCategories
        val localPassword = dialog?.localPassword.orEmpty()
        val restoreMode = dialog?.mode ?: selectedRestoreMode
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching {
                backupRestoreUseCase.restoreLocal(
                    Uri.parse(uri).toString(),
                    restoreMode,
                    categories,
                    dialog?.categoryModes.orEmpty(),
                    localPassword,
                )
            }
                .fold(
                    onSuccess = { operationId ->
                        // 等待本次恢复操作的结果
                        runCatching { awaitRestoreResult(operationId) }.fold(
                            onSuccess = { result ->
                                activeOperationId = null
                                BackupRestoreResultBus.removeResult(operationId)
                                finishRestoreSuccess(result)
                            },
                            onFailure = { activeOperationId = null; finishRestoreFailure(it.localizedMessage) },
                        )
                    },
                    onFailure = { activeOperationId = null; finishRestoreFailure(it.localizedMessage) },
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
                                localPassword = "",
                            )
                        )
                    }
                } },
                onFailure = { finishRestoreFailure(it.localizedMessage, webDav = true) },
            )
        }
    }

    private fun performRestoreNetwork(name: String, confirmedCategories: Set<String> = emptySet()) {
        activeOperationId = null
        val previewDialog = _uiState.value.activeDialog as? BackupConfigDialog.RestorePreview
        val password = previewDialog?.localPassword.orEmpty()
        val categoryModes = previewDialog?.categoryModes.orEmpty()
        _uiState.update { it.copy(activeDialog = BackupConfigDialog.Loading(R.string.on_restore)) }
        viewModelScope.launch(Dispatchers.IO) {
            runCatching { webDavBackupUseCase.restore(name, selectedRestoreMode, categoryModes, password) }
                .fold(
                    onSuccess = { operationId ->
                        runCatching { awaitRestoreResult(operationId) }.fold(
                            onSuccess = { result ->
                                activeOperationId = null
                                BackupRestoreResultBus.removeResult(operationId)
                                finishRestoreSuccess(result, webDav = true)
                            },
                            onFailure = { activeOperationId = null; finishRestoreFailure(it.localizedMessage, webDav = true) },
                        )
                    },
                    onFailure = { activeOperationId = null; finishRestoreFailure(it.localizedMessage, webDav = true) },
                )
        }
    }

    /** 恢复异常时也必须退出 Loading，不能无限等待结果总线。 */
    private suspend fun awaitRestoreResult(operationId: String): BackupRestoreResult =
        withTimeout(5 * 60_000L) {
            BackupRestoreResultBus.events.first { it.operationId == operationId }
        }

private suspend fun finishRestoreSuccess(result: BackupRestoreResult, webDav: Boolean = false) = withContext(Dispatchers.Main) {
        // 恢复结果使用完整性弹窗展示，避免重要失败信息被短暂提示遮蔽。
        _uiState.update {
            it.copy(
                activeDialog = BackupConfigDialog.ConfigIntegrityReport(
                    result = result,
                    type = BackupConfigReportType.RESTORE,
                )
            )
        }
    }

    private suspend fun finishRestoreFailure(message: String?, webDav: Boolean = false) =
        withContext(Dispatchers.Main) {
            _uiState.update {
                it.copy(
                    activeDialog = BackupConfigDialog.ConfigIntegrityReport(
                        result = BackupRestoreResult(
                            outcome = BackupRestoreOutcome.FAILED,
                            failures = 1,
                            webDavDownloadFailed = webDav,
                            parseFailed = !webDav,
                        ),
                        message = message,
                        type = BackupConfigReportType.RESTORE,
                    ),
                )
            }
        }

private companion object {
        @Suppress("UNUSED_PARAMETER")
        suspend fun loadIgnoreItems() = withContext(Dispatchers.IO) {
            io.legado.app.help.storage.BackupConfig.ignoreKeys
                .mapIndexed { index, key ->
                    BackupIgnoreItem(
                        key = key,
                        title = io.legado.app.help.storage.BackupConfig.ignoreTitle[index],
                        checked = io.legado.app.help.storage.BackupConfig.ignoreConfig[key] ?: false,
                    )
                }
                .toImmutableList()
        }

        @Suppress("UNUSED_PARAMETER")
        suspend fun loadBackupIgnoreItems() = withContext(Dispatchers.IO) {
            io.legado.app.help.storage.BackupConfig.backupIgnoreKeys
                .mapIndexed { index, key ->
                    BackupIgnoreItem(
                        key = key,
                        title = io.legado.app.help.storage.BackupConfig.backupIgnoreTitle[index],
                        checked = io.legado.app.help.storage.BackupConfig.backupIgnoreConfig[key]
                            ?: false,
                    )
                }
                .toImmutableList()
        }

        @Suppress("UNUSED_PARAMETER")
        suspend fun loadDbIgnoreItems() = withContext(Dispatchers.IO) {
            io.legado.app.help.storage.BackupConfig.dbIgnoreKeys
                .mapIndexed { index, key ->
                    BackupIgnoreItem(
                        key = key,
                        title = io.legado.app.help.storage.BackupConfig.dbIgnoreTitle[index],
                        checked = io.legado.app.help.storage.BackupConfig.dbIgnoreConfig[key] ?: false,
                    )
                }
                .toImmutableList()
        }

        @Suppress("UNUSED_PARAMETER")
        suspend fun loadBackupDbIgnoreItems() = withContext(Dispatchers.IO) {
            io.legado.app.help.storage.BackupConfig.backupDbIgnoreKeys
                .mapIndexed { index, key ->
                    BackupIgnoreItem(
                        key = key,
                        title = io.legado.app.help.storage.BackupConfig.backupDbIgnoreTitle[index],
                        checked = io.legado.app.help.storage.BackupConfig.backupDbIgnoreConfig[key]
                            ?: false,
                    )
                }
                .toImmutableList()
        }

        @Suppress("UNUSED_PARAMETER")
        suspend fun loadSensitiveItems() = withContext(Dispatchers.IO) {
            BackupSensitiveCategory.entries.map { category ->
                BackupSensitiveItem(
                    category = category,
                    title = category.name,
                    checked = io.legado.app.help.storage.BackupConfig.sensitiveSelection.includes(category),
                )
            }.toImmutableList()
        }

    }
}
