package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.gateway.ThemeSettingsGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.BackupSensitiveCategory
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.AppWebDav
import io.legado.app.help.DirectLinkUpload
import io.legado.app.help.book.isLocal
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.help.coroutine.Coroutine
import io.legado.app.model.BookCover
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.LogUtils
import io.legado.app.utils.createFolderIfNotExist
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.openOutputStream
import io.legado.app.utils.outputStream
import io.legado.app.utils.writeToOutputStream
import io.legado.app.BuildConfig
import io.legado.app.R
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.BufferedInputStream
import java.security.MessageDigest
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 备份
 */
object Backup {

/** 提供用户可读的敏感项目名称，严禁返回密码、Cookie、Token 等原文。 */
    fun sensitiveItemIdentifiers(category: BackupSensitiveCategory): List<String> = when (category) {
        BackupSensitiveCategory.SERVER_CREDENTIALS -> appDb.serverDao.all.map { "服务器 #${it.id}" }
        BackupSensitiveCategory.BOOK_SOURCE_AUTH -> appDb.bookSourceDao.all
            .filter { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() || !it.loginCheckJs.isNullOrBlank() }
            .map { "书源 #${it.bookSourceUrl} (${it.bookSourceName.ifBlank { "无名" }})" }
        BackupSensitiveCategory.REQUEST_HEADERS -> appDb.bookSourceDao.all
            .filter { !it.header.isNullOrBlank() }
            .map { "书源 #${it.bookSourceUrl} (${it.bookSourceName.ifBlank { "无名" }})" }
        BackupSensitiveCategory.RSS_AUTH -> appDb.rssSourceDao.all.map { "RSS #${it.sourceUrl} (${it.sourceName.ifBlank { it.sourceUrl }})" }
        BackupSensitiveCategory.TTS_API_KEYS -> appDb.httpTTSDao.all.map { "HTTP TTS #${it.id}" }
        BackupSensitiveCategory.WEBDAV_CREDENTIALS -> listOf("WebDAV 登录凭据")
        BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS -> listOf("直链上传规则中的凭据")
        BackupSensitiveCategory.OTHER -> emptyList()
    }

    private val readStyleGateway: ReadStyleGateway
        get() = GlobalContext.get().get()

    private val backupSettingsGateway: BackupSettingsGateway
        get() = GlobalContext.get().get()

    private val themeSettingsGateway: ThemeSettingsGateway
        get() = GlobalContext.get().get()

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

    /** 备份完整性报告中必须关注的应用壳和导航键。 */
    private val requiredConfigKeys = setOf(
        PreferKey.mainNavigationOrder,
        PreferKey.showBottomView,
        PreferKey.navExtended,
        PreferKey.navIconHome,
        PreferKey.navIconBookshelf,
        PreferKey.navIconExplore,
        PreferKey.navIconRss,
        PreferKey.navIconMy,
        // 主题、书架和阅读器关键设置
        PreferKey.appTheme,
        PreferKey.themeMode,
        PreferKey.bookshelfLayout,
        PreferKey.bookshelfSort,
        PreferKey.readStyleSelect,
        PreferKey.comicStyleSelect,
        // 该键来自 ReadSettings DataStore，必须和 AppConfigStore 键一样纳入完整性检查。
        PreferKey.textSelectMenuConfig,
        // 网络、TTS 与服务关键设置
        PreferKey.webService,
        PreferKey.webPort,
        PreferKey.ttsEngine,
        PreferKey.ttsSpeechRate,
        PreferKey.webDavUrl,
        PreferKey.webDavDir,
    )

    private val backupFileNames by lazy {
        arrayOf(
            "bookshelf.json",
            "bookmark.json",
            "bookMarking.json",
            "bookGroup.json",
            "bookSource.json",
            "rssSources.json",
            "rssStar.json",
            "replaceRule.json",
            "readRecord.json",
            "readRecordDetail.json",
            "readRecordSession.json",
            "searchHistory.json",
            "sourceSub.json",
            "txtTocRule.json",
            "httpTTS.json",
            "keyboardAssists.json",
            "dictRule.json",
            "homepageModules.json",
            "homepageCustomSets.json",
            "highlightRule.json",
            "highlightTagRule.json",
"tagGroupRule.json",
            "servers.json",
            DirectLinkUpload.ruleFileName,
            "readSettings.json",
            ReadBookConfig.configFileName,
            ReadBookConfig.shareConfigFileName,
            ThemeConfigStore.configFileName,
            BookCover.configFileName,
            "config.xml",
            "manifest.json"
        )
    }

    private fun getNowZipFileName(): String {
        val backupDate = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault())
            .format(Date(System.currentTimeMillis()))
        val deviceName = backupSettingsGateway.currentSettings.webDavDeviceName
        return if (deviceName.isNotBlank()) {
            "backup${backupDate}-${deviceName}.zip"
        } else {
            "backup${backupDate}.zip"
        }.normalizeFileName()
    }

    private fun shouldBackup(): Boolean {
        val lastBackup = LocalConfig.lastBackup
        return lastBackup + TimeUnit.DAYS.toMillis(1) < System.currentTimeMillis()
    }

    fun autoBack(context: Context) {
        if (shouldBackup()) {
            Coroutine.async {
                BackupRestoreLock.withLock {
                    if (shouldBackup()) {
                        val backupZipFileName = getNowZipFileName()
                        if (!AppWebDav.hasBackUp(backupZipFileName)) {
                            backup(context, backupSettingsGateway.currentSettings.backupPath)
                        } else {
                            LocalConfig.lastBackup = System.currentTimeMillis()
                        }
                    }
                }
            }.onError {
                AppLog.put("自动备份失败\n${it.localizedMessage}")
            }
        }
    }

suspend fun backupLocked(context: Context, path: String?, mode: String = "both"): String {
        val operationId = java.util.UUID.randomUUID().toString()
        return BackupRestoreLock.withLock {
            withContext(IO) {
                runCatching { backup(context, path, mode, operationId) }
                    .getOrElse { error ->
                        if (error is CancellationException) throw error
                        // 本地写入、ZIP 生成等前置异常也必须形成可关联的失败结果。
                        BackupRestoreResultBus.emit(
                            BackupRestoreResult(
                                operationId = operationId,
                                outcome = if (mode == "webdav") {
                                    BackupRestoreOutcome.PRECONDITION_FAILED
                                } else {
                                    BackupRestoreOutcome.FAILED
                                },
                                failures = 1,
                                webDavUploadAttempted = mode == "webdav",
                                webDavUploadFailed = mode == "webdav",
                                parseFailed = true,
                            ),
                        )
                        AppLog.put("备份流程失败\n${error.localizedMessage}", error)
                        operationId
                    }
            }
        }
    }

    private suspend fun backup(
        context: Context,
        path: String?,
        mode: String = "both",
        operationId: String = java.util.UUID.randomUUID().toString(),
    ): String {
        var warningCount = 0
        var webDavUploadFailed = false
        var localBackupAttempted = false
        var localBackupCreated = false
        var localBackupFailed = false
        var webDavUploadAttempted = false
        val sensitiveWarnings = mutableListOf<String>()
        val sensitiveEncryptionFailures = mutableSetOf<BackupSensitiveCategory>()
        if (mode == "both" || mode == "webdav") {
            // WebDAV 配置必须在本次操作前同步，失败时不能继续复用旧认证状态。
            val syncResult = AppWebDav.upConfig()
            if (syncResult.isFailure) {
                if (mode == "webdav") {
                    throw syncResult.exceptionOrNull() ?: NoStackTraceException("WebDAV 配置同步失败")
                }
                webDavUploadFailed = true
                warningCount++
                AppLog.put("WebDAV 配置同步失败，本地备份继续执行", syncResult.exceptionOrNull())
            }
        }
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.PREPARE))
        LogUtils.d(TAG, "开始备份 path:$path")
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(
            appDb.bookDao.all.filterNot { BackupConfig.backupIgnoreLocalBook && it.isLocal },
            "bookshelf.json",
            backupPath,
        )
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.PREPARE))
        // 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (BackupConfig.dbIsNotIgnored("bookmark", true)) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
            writeListToJson(appDb.bookMarkingDao.all, "bookMarking.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup", true)) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
if (BackupConfig.dbIsNotIgnored("bookSource", true)) {
            val sanitized = appDb.bookSourceDao.all.map(::sanitizeBookSource)
            if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH) ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS)
            ) {
                GSON.toJson(sanitized).let { json ->
                    runCatching { BackupSensitiveEnvelope.encrypt(aes, json) }
                        .onSuccess { encrypted ->
                            FileUtils.createFileIfNotExist(backupPath + File.separator + "bookSource.json")
                                .writeText(encrypted)
                        }
                        .onFailure {
                            sensitiveEncryptionFailures += BackupSensitiveCategory.BOOK_SOURCE_AUTH
                            sensitiveEncryptionFailures += BackupSensitiveCategory.REQUEST_HEADERS
                            sensitiveWarnings += "备份书源配置加密失败，已跳过敏感数据"
                            AppLog.put("备份书源配置加密失败，已跳过敏感数据", it)
                        }
                }
            } else {
                writeListToJson(sanitized, "bookSource.json", backupPath)
            }
        }
        if (BackupConfig.dbIsNotIgnored("rssSource", true)) {
            val sanitized = appDb.rssSourceDao.all.map(::sanitizeRssSource)
            if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH) ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS)
            ) {
                GSON.toJson(sanitized).let { json ->
                    runCatching { BackupSensitiveEnvelope.encrypt(aes, json) }
                        .onSuccess { encrypted ->
                            FileUtils.createFileIfNotExist(backupPath + File.separator + "rssSources.json")
                                .writeText(encrypted)
                        }
                        .onFailure {
                            sensitiveEncryptionFailures += BackupSensitiveCategory.RSS_AUTH
                            sensitiveEncryptionFailures += BackupSensitiveCategory.REQUEST_HEADERS
                            sensitiveWarnings += "备份 RSS 配置加密失败，已跳过敏感数据"
                            AppLog.put("备份 RSS 配置加密失败，已跳过敏感数据", it)
                        }
                }
            } else {
                writeListToJson(sanitized, "rssSources.json", backupPath)
            }
        }
        if (BackupConfig.dbIsNotIgnored("rssStar", true)) {
            writeListToJson(appDb.rssStarDao.all, "rssStar.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("replaceRule", true)) {
            writeListToJson(appDb.replaceRuleDao.all, "replaceRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("readRecord", true)) {
            writeListToJson(appDb.readRecordDao.all, "readRecord.json", backupPath)
            writeListToJson(appDb.readRecordDao.allDetail, "readRecordDetail.json", backupPath)
            writeListToJson(appDb.readRecordDao.allSession, "readRecordSession.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("searchHistory", true)) {
            writeListToJson(appDb.searchKeywordDao.all, "searchHistory.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("sourceSub", true)) {
            writeListToJson(appDb.ruleSubDao.all, "sourceSub.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("txtTocRule", true)) {
            writeListToJson(appDb.txtTocRuleDao.all, "txtTocRule.json", backupPath)
        }
if (BackupConfig.dbIsNotIgnored("httpTTS", true)) {
            val sanitized = appDb.httpTTSDao.all.map(::sanitizeHttpTts)
            if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)) {
                GSON.toJson(sanitized).let { json ->
                    runCatching { BackupSensitiveEnvelope.encrypt(aes, json) }
                        .onSuccess { encrypted ->
                            FileUtils.createFileIfNotExist(backupPath + File.separator + "httpTTS.json")
                                .writeText(encrypted)
                        }
                        .onFailure {
                            sensitiveEncryptionFailures += BackupSensitiveCategory.TTS_API_KEYS
                            sensitiveWarnings += "备份 HTTP TTS 配置加密失败，已跳过敏感数据"
                            AppLog.put("备份 HTTP TTS 配置加密失败，已跳过敏感数据", it)
                        }
                }
            } else {
                writeListToJson(sanitized, "httpTTS.json", backupPath)
            }
        }
        if (BackupConfig.dbIsNotIgnored("keyboardAssists", true)) {
            writeListToJson(appDb.keyboardAssistsDao.all, "keyboardAssists.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("dictRule", true)) {
            writeListToJson(appDb.dictRuleDao.all, "dictRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageModules", true)) {
            writeListToJson(appDb.homepageModuleDao.getAll(), "homepageModules.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("homepageCustomSets", true)) {
            writeListToJson(
                appDb.homepageCustomSetDao.getAll(),
                "homepageCustomSets.json",
                backupPath
            )
        }
        if (BackupConfig.dbIsNotIgnored("highlightRule", true)) {
            writeListToJson(appDb.highlightRuleDao.getAll(), "highlightRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("highlightTagRule", true)) {
            writeListToJson(appDb.highlightTagRuleDao.getAll(), "highlightTagRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("tagGroupRule", true)) {
            writeListToJson(appDb.tagGroupRuleDao.getAll(), "tagGroupRule.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("server", true) &&
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.SERVER_CREDENTIALS)
        ) {
            GSON.toJson(appDb.serverDao.all).let { json ->
                // 敏感数据加密失败时必须跳过，禁止回退写入明文。
                runCatching { BackupSensitiveEnvelope.encrypt(aes, json) }
                    .onSuccess { encrypted ->
                        FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                            .writeText(encrypted)
                    }
                    .onFailure {
                        sensitiveEncryptionFailures += BackupSensitiveCategory.SERVER_CREDENTIALS
                        sensitiveWarnings += "备份服务器配置加密失败，已跳过敏感数据"
                        AppLog.put("备份服务器配置加密失败，已跳过敏感数据", it)
                    }
            }
        }
        currentCoroutineContext().ensureActive()
        if (!BackupConfig.backupIgnoreReadConfig) {
            readStyleGateway.exportConfigsJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.configFileName)
                    .writeText(it)
            }
            readStyleGateway.exportShareConfigJson().let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ReadBookConfig.shareConfigFileName)
                    .writeText(it)
            }
            // 导出完整 ReadSettings（文本选择菜单、阅读器 UI 设置等）
            readStyleGateway.readSettingsGateway?.exportJson()?.let { json ->
                FileUtils.createFileIfNotExist(backupPath + File.separator + "readSettings.json")
                    .writeText(json)
            }
        }
        if (!BackupConfig.backupIgnoreThemeConfig) {
            GSON.toJson(ThemeConfigStore.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfigStore.configFileName)
                    .writeText(it)
            }
        }
if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS)) {
            DirectLinkUpload.getConfig()?.let {
                GSON.toJson(it).let { json ->
                    runCatching { BackupSensitiveEnvelope.encrypt(aes, json) }
                        .onSuccess { encrypted ->
                            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                                .writeText(encrypted)
                        }
                        .onFailure {
                            sensitiveEncryptionFailures += BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS
                            sensitiveWarnings += "备份直链上传配置加密失败，已跳过敏感数据"
                            AppLog.put("备份直链上传配置加密失败，已跳过敏感数据", it)
                        }
                }
            }
        }
        if (!BackupConfig.backupIgnoreCoverConfig) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }
        currentCoroutineContext().ensureActive()
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.PROCESS_RESOURCES))
        val configMap = AppConfigStore.preferences.asMap()
            .mapKeys { it.key.name }
            .toMutableMap()
        // 文本选择菜单顺序保存在 ReadSettings DataStore，不在 AppConfigStore 中。
        configMap[PreferKey.textSelectMenuConfig] = GlobalContext.get().get<ReadSettingsGateway>().currentSettings.textSelectMenuConfig
        val writtenConfigKeys = mutableSetOf<String>()
        val ignoredConfigKeys = mutableSetOf<String>()
        val unsupportedConfigKeys = mutableSetOf<String>()
        val configToEncode = linkedMapOf<String, Any?>()
        configMap.forEach { (key, value) ->
            val shouldEncode = BackupConfig.keyIsNotIgnore(key, true) &&
                (key != PreferKey.webDavPassword ||
                    BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.WEBDAV_CREDENTIALS))
            if (!shouldEncode) {
                ignoredConfigKeys += key
                return@forEach
            }
            val finalValue = if (key == PreferKey.webDavPassword) {
                runCatching { BackupSensitiveEnvelope.encrypt(aes, value.toString()) }.getOrElse {
                    sensitiveEncryptionFailures += BackupSensitiveCategory.WEBDAV_CREDENTIALS
                    sensitiveWarnings += "备份配置加密失败，已跳过敏感键：$key"
                    AppLog.put("备份配置加密失败，已跳过敏感键：$key", it)
                    return@forEach
                }
            } else value
            configToEncode[key] = finalValue
        }
        val configEncoding = BackupConfigCodec.encode(configToEncode)
        writtenConfigKeys += configEncoding.writtenKeys
        unsupportedConfigKeys += configEncoding.unsupportedKeys
        unsupportedConfigKeys.forEach { key ->
            AppLog.put("备份配置类型不支持，已跳过：$key")
        }
        FileUtils.createFileIfNotExist(backupPath + File.separator + "config.xml")
            .writeText(configEncoding.xml)

        val (resourceFiles, originalResourceAliases, resourceWarnings) = copyBackgroundResources(backupPath)
        val resourceAliases = redactResourceAliases(File(backupPath), originalResourceAliases)
        val manifestWarnings = resourceWarnings + sensitiveWarnings
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.PROCESS_RESOURCES, resourceFiles.size, resourceFiles.size))

        val filesForManifest = backupFileNames
            .filter { it != "manifest.json" }
            .map { File(backupPath, it) }
            .filter(File::isFile)
        val manifestCategoryByFile = filesForManifest.associateWith { manifestCategory(it.name) }
        val manifestCategories = manifestCategoryByFile.values.toSet()
        val ignoredManifestCategories = BackupConfig.backupIgnoreConfig
            .filterValues { it }
            .keys
        val categoryStatus = BackupRestoreCategoryCatalog.allCategories.associateWith { category ->
            when {
                category == "resources" && resourceFiles.isNotEmpty() -> "已写入"
                category in manifestCategories -> "已写入"
                category in ignoredManifestCategories -> "已忽略"
                else -> "未写入"
            }
        }
        val categoryLabels = mapOf(
            "bookshelf" to "书架", "bookmark" to "书签与划线", "bookGroup" to "书籍分组",
            "bookSource" to "书源", "rssSource" to "RSS 源", "rssStar" to "RSS 收藏",
            "replaceRule" to "替换规则", "searchHistory" to "搜索历史", "readRecord" to "阅读记录",
            "server" to "服务器", "sourceSub" to "书源订阅", "txtTocRule" to "文本目录规则",
            "httpTTS" to "HTTP TTS", "keyboardAssists" to "键盘辅助", "dictRule" to "字典规则",
            "homepageModules" to "首页模块", "homepageCustomSets" to "首页自定义布局",
            "highlightRule" to "高亮规则", "highlightTagRule" to "高亮标签规则", "tagGroupRule" to "标签分组",
            "settings" to "应用设置", "readConfig" to "阅读器配置", "themeConfig" to "主题与外观",
            "coverConfig" to "封面配置", "directLinkUploadRule" to "直链上传", "resources" to "背景资源",
            "readSettings" to "完整阅读器设置",
        )
        val categoryStatusDetails = categoryStatus.mapValues { (category, status) ->
            when (status) {
                "已写入" -> ""
                "已忽略" -> "已按备份忽略设置跳过"
                else -> when {
                    category == "bookSource" && sensitiveEncryptionFailures.any {
                        it == BackupSensitiveCategory.BOOK_SOURCE_AUTH || it == BackupSensitiveCategory.REQUEST_HEADERS
                    } -> appCtx.getString(R.string.backup_sensitive_encrypt_failed)
                    category == "rssSource" && sensitiveEncryptionFailures.any {
                        it == BackupSensitiveCategory.RSS_AUTH || it == BackupSensitiveCategory.REQUEST_HEADERS
                    } -> appCtx.getString(R.string.backup_sensitive_encrypt_failed)
                    category == "httpTTS" && BackupSensitiveCategory.TTS_API_KEYS in sensitiveEncryptionFailures ->
                        appCtx.getString(R.string.backup_sensitive_encrypt_failed)
                    category == "server" && BackupSensitiveCategory.SERVER_CREDENTIALS in sensitiveEncryptionFailures ->
                        appCtx.getString(R.string.backup_sensitive_encrypt_failed)
                    category == "directLinkUploadRule" &&
                        BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS in sensitiveEncryptionFailures ->
                        appCtx.getString(R.string.backup_sensitive_encrypt_failed)
                    category in setOf("server", "directLinkUploadRule") -> "未选择对应敏感信息"
                    else -> "本次没有可写入内容"
                }
            }
        }
        fun sensitiveStatus(category: BackupSensitiveCategory, hasValue: Boolean, vararg fileNames: String): String = when {
            !BackupConfig.sensitiveSelection.includes(category) -> "未选择"
            category in sensitiveEncryptionFailures -> appCtx.getString(R.string.backup_sensitive_encrypt_failed)
            !hasValue -> "无可写入内容"
            fileNames.none { fileName -> filesForManifest.any { it.name == fileName } } -> "无可写入内容"
            else -> "已加密写入"
        }
        val bookSources = appDb.bookSourceDao.all
        val rssSources = appDb.rssSourceDao.all
        val httpTtsSources = appDb.httpTTSDao.all
        val sensitiveStatus = mapOf(
            "WEBDAV_CREDENTIALS" to sensitiveStatus(
                BackupSensitiveCategory.WEBDAV_CREDENTIALS,
                PreferKey.webDavPassword in writtenConfigKeys &&
                    !configMap[PreferKey.webDavPassword]?.toString().isNullOrBlank(),
                "config.xml",
            ),
            "SERVER_CREDENTIALS" to sensitiveStatus(
                BackupSensitiveCategory.SERVER_CREDENTIALS,
                appDb.serverDao.all.isNotEmpty(),
                "servers.json",
            ),
            "BOOK_SOURCE_AUTH" to sensitiveStatus(
                BackupSensitiveCategory.BOOK_SOURCE_AUTH,
                bookSources.any { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() || !it.loginCheckJs.isNullOrBlank() },
                "bookSource.json",
            ),
            "REQUEST_HEADERS" to sensitiveStatus(
                BackupSensitiveCategory.REQUEST_HEADERS,
                bookSources.any { !it.header.isNullOrBlank() } || rssSources.any { !it.header.isNullOrBlank() },
                "bookSource.json", "rssSources.json",
            ),
            "RSS_AUTH" to sensitiveStatus(
                BackupSensitiveCategory.RSS_AUTH,
                rssSources.any { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() || !it.loginCheckJs.isNullOrBlank() },
                "rssSources.json",
            ),
            "TTS_API_KEYS" to sensitiveStatus(
                BackupSensitiveCategory.TTS_API_KEYS,
                httpTtsSources.any { !it.header.isNullOrBlank() || !it.loginUrl.isNullOrBlank() ||
                    !it.loginUi.isNullOrBlank() || !it.loginCheckJs.isNullOrBlank() },
                "httpTTS.json",
            ),
            "DIRECT_LINK_CREDENTIALS" to sensitiveStatus(
                BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS,
                filesForManifest.any { it.name == DirectLinkUpload.ruleFileName },
                DirectLinkUpload.ruleFileName,
            ),
        )
        val configIntegrity = BackupConfigIntegrityChecker.check(
            snapshotKeys = configMap.keys,
            written = writtenConfigKeys,
            ignored = ignoredConfigKeys,
            unsupported = unsupportedConfigKeys,
            requiredKeys = requiredConfigKeys,
        )
        // 未设置的键没有进入持久化快照，是正常状态；仍在清单中说明，但不计为备份警告。
        warningCount += configIntegrity.unsupported.size +
            configIntegrity.failed.size + manifestWarnings.size
        val manifestCounts = manifestCategoryCounts(filesForManifest)
        val manifestFileNamesByCategory = manifestCategoryByFile.entries
            .groupBy({ it.value }, { it.key.name })
        val manifestCategoryOrder = (
            listOf("settings", "readSettings", "themeConfig", "readConfig", "coverConfig", "resources") +
                BackupRestoreCategoryCatalog.allCategories + manifestCategories
            ).distinct()
        val manifest = BackupManifestBuilder.build(
            root = File(backupPath),
            files = filesForManifest,
            categories = manifestCategoryOrder.map { category ->
                BackupManifestCategory(
                    id = category,
                    name = categoryLabels[category] ?: appCtx.getString(R.string.restore_category_other),
                    status = categoryStatus[category] ?: "已写入",
                    detail = categoryStatusDetails[category].orEmpty(),
                    count = when (category) {
                        "settings" -> configIntegrity.written.size
                        "resources" -> resourceFiles.size
                        else -> manifestCounts[category] ?: 0
                    },
                    files = if (category == "resources") {
                        resourceFiles.map { "assets/background/${it.name}" }.sorted()
                    } else {
                        manifestFileNamesByCategory[category].orEmpty().sorted()
                    },
                )
            },
            sensitiveCategories = sensitiveStatus.map { (category, status) ->
                BackupManifestSensitiveCategory(
                    id = category,
                    name = appCtx.getString(
                        when (category) {
                            "WEBDAV_CREDENTIALS" -> R.string.backup_sensitive_webdav
                            "SERVER_CREDENTIALS" -> R.string.backup_sensitive_server
                            "BOOK_SOURCE_AUTH" -> R.string.backup_sensitive_source
                            "REQUEST_HEADERS" -> R.string.backup_sensitive_headers
                            "RSS_AUTH" -> R.string.backup_sensitive_rss
                            "TTS_API_KEYS" -> R.string.backup_sensitive_tts
                            else -> R.string.backup_sensitive_direct_link
                        }
                    ),
                    status = status,
                )
            },
            ignoredCategories = BackupConfig.backupIgnoreConfig.filterValues { it }.keys,
            restoreIgnoredCategories = buildSet {
                addAll(BackupConfig.dbIgnoreConfig.filterValues { it }.keys)
                if (BackupConfig.ignoreReadConfig) add("readConfig")
                if (BackupConfig.ignoreThemeConfig) add("themeConfig")
                if (BackupConfig.ignoreCoverConfig) add("coverConfig")
            },
            configItems = (configMap.keys + configIntegrity.notSet).map { key ->
                BackupManifestConfigItem(
                    key = key,
                    name = configKeyLabel(key),
                    status = configKeyStatusLabel(configIntegrity.status(key)),
                )
            },
            appVersion = BuildConfig.VERSION_NAME,
            resources = resourceFiles,
            resourceAliases = resourceAliases,
            warnings = manifestWarnings,
sensitiveIncluded = sensitiveStatus.values.any { it == "已加密写入" },
sensitiveItems = buildSet {
                if (sensitiveStatus["SERVER_CREDENTIALS"] == "已加密写入") {
                    add(BackupSensitiveCategory.SERVER_CREDENTIALS.name)
                    appDb.serverDao.all.forEach { server -> add("server:${server.id}") }
                }
                if (sensitiveStatus["WEBDAV_CREDENTIALS"] == "已加密写入") {
                    add(BackupSensitiveCategory.WEBDAV_CREDENTIALS.name)
                }
                if (sensitiveStatus["BOOK_SOURCE_AUTH"] == "已加密写入") {
                    add(BackupSensitiveCategory.BOOK_SOURCE_AUTH.name)
                    bookSources.filter { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() ||
                        !it.loginCheckJs.isNullOrBlank() }.forEach { source ->
                        add("bookSource:${stableSensitiveIdentifier(source.bookSourceUrl)}")
                    }
                }
                if (sensitiveStatus["REQUEST_HEADERS"] == "已加密写入") {
                    add(BackupSensitiveCategory.REQUEST_HEADERS.name)
                    bookSources.filter { !it.header.isNullOrBlank() }.forEach { source ->
                        add("bookSourceHeader:${stableSensitiveIdentifier(source.bookSourceUrl)}")
                    }
                    rssSources.filter { !it.header.isNullOrBlank() }.forEach { source ->
                        add("rssSourceHeader:${stableSensitiveIdentifier(source.sourceUrl)}")
                    }
                }
                if (sensitiveStatus["RSS_AUTH"] == "已加密写入") {
                    add(BackupSensitiveCategory.RSS_AUTH.name)
                    rssSources.filter { !it.loginUrl.isNullOrBlank() || !it.loginUi.isNullOrBlank() ||
                        !it.loginCheckJs.isNullOrBlank() }.forEach { source ->
                        add("rssSource:${stableSensitiveIdentifier(source.sourceUrl)}")
                    }
                }
                if (sensitiveStatus["TTS_API_KEYS"] == "已加密写入") {
                    add(BackupSensitiveCategory.TTS_API_KEYS.name)
                    httpTtsSources.filter { !it.header.isNullOrBlank() || !it.loginUrl.isNullOrBlank() ||
                        !it.loginUi.isNullOrBlank() || !it.loginCheckJs.isNullOrBlank() }.forEach { source ->
                        add("httpTTS:${source.id}")
                    }
                }
                if (sensitiveStatus["DIRECT_LINK_CREDENTIALS"] == "已加密写入") {
                    add(BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS.name)
                }
            },
            weakProtection = BackupPasswordPolicyResolver.resolve(LocalConfig.password).weakProtection,
        )
        FileUtils.createFileIfNotExist(backupPath + File.separator + "manifest.json")
            .writeText(GSON.toJson(manifest))
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.GENERATE_MANIFEST, filesForManifest.size, filesForManifest.size))

        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (backupSettingsGateway.currentSettings.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (zipBackupDirectory(File(backupPath), File(zipFilePath))) {
            BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.GENERATE_ZIP))
            if (mode == "both" || mode == "local") {
                localBackupAttempted = true
                BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.WRITE_LOCAL))
                try {
                    when {
                        path.isNullOrBlank() -> copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                        path.isContentScheme() -> copyBackup(context, path.toUri(), backupFileName)
                        else -> copyBackup(File(path), backupFileName)
                    }
                    localBackupCreated = true
                } catch (error: Exception) {
                    localBackupFailed = true
                    warningCount++
                    AppLog.put("写入本地备份失败", error)
                    if (mode == "local") throw error
                }
            }
            if ((mode == "both" || mode == "webdav") && !webDavUploadFailed) {
                webDavUploadAttempted = true
                BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.UPLOAD_WEBDAV))
                try {
                    AppWebDav.backUpWebDav(zipFileName)
} catch (e: Exception) {
                    warningCount++
                    webDavUploadFailed = true
                    BackupRestoreProgressBus.emit(
                        BackupRestoreProgress(
                            operationId,
                            stage = BackupRestoreStage.UPLOAD_WEBDAV,
                            warnings = 1,
                        )
                    )
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        } else {
            throw NoStackTraceException("生成备份 ZIP 失败")
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.COMPLETE))
BackupRestoreResultBus.emit(
            BackupRestoreResult(
                operationId = operationId,
                outcome = classifyBackupOutcome(
                    localBackupCreated,
                    webDavUploadAttempted,
                    webDavUploadFailed,
                    warningCount,
                ),
                warnings = warningCount,
                failures = (if (localBackupFailed) 1 else 0) + (if (webDavUploadFailed) 1 else 0),
                writtenConfigKeys = configIntegrity.written.size,
                ignoredConfigKeys = configIntegrity.ignored.size,
                unsupportedConfigKeys = configIntegrity.unsupported.size,
                failedConfigKeys = configIntegrity.failed.size,
                notSetConfigKeys = configIntegrity.notSet.size,
                ignoredConfigKeyNames = configIntegrity.ignored,
                unsupportedConfigKeyNames = configIntegrity.unsupported,
                failedConfigKeyNames = configIntegrity.failed,
                notSetConfigKeyNames = configIntegrity.notSet,
                backupCategoryStatus = manifest.categories.associate { it.id to it.status },
                backupCategoryLabels = manifest.categories.associate { it.id to it.name },
                backupCategoryStatusDetails = manifest.categories.associate { it.id to it.detail },
                backupEmptyCategories = manifest.categories.filter { it.files.isNotEmpty() && it.count == 0 }
                    .mapTo(linkedSetOf<String>()) { it.id },
                backupSensitiveStatus = manifest.sensitiveCategories.associate { it.id to it.status },
                backupConfigKeyStatus = manifest.configItems.associate { it.key to it.status },
                backupConfigKeyLabels = manifest.configItems.associate { it.key to it.name },
                backupFileNames = manifest.files.map { it.name } + manifest.resources.map { it.name },
webDavUploadFailed = webDavUploadFailed,
localBackupAttempted = localBackupAttempted,
localBackupCreated = localBackupCreated,
localBackupFailed = localBackupFailed,
webDavUploadAttempted = webDavUploadAttempted,
            )
        )
        // WebDAV 上传失败时，即使本地备份成功，也不能把本次任务记为自动备份成功。
        if (!webDavUploadFailed) {
            LocalConfig.lastBackup = System.currentTimeMillis()
        }
        return operationId
    }

    private fun manifestCategory(fileName: String): String = when (fileName) {
        "config.xml" -> "settings"
        "readConfig.json", "shareReadConfig.json" -> "readConfig"
        "readSettings.json" -> "readSettings"
        "themeConfig.json" -> "themeConfig"
        "coverRule.json" -> "coverConfig"
        "directLinkUploadRule.json" -> "directLinkUploadRule"
        "bookshelf.json" -> "bookshelf"
        "bookmark.json", "bookMarking.json" -> "bookmark"
        "bookGroup.json" -> "bookGroup"
        "bookSource.json" -> "bookSource"
        "rssSources.json" -> "rssSource"
        "rssStar.json" -> "rssStar"
        "replaceRule.json" -> "replaceRule"
        "readRecord.json", "readRecordDetail.json", "readRecordSession.json" -> "readRecord"
        "searchHistory.json" -> "searchHistory"
        "sourceSub.json" -> "sourceSub"
        "txtTocRule.json" -> "txtTocRule"
        "httpTTS.json" -> "httpTTS"
        "keyboardAssists.json" -> "keyboardAssists"
        "dictRule.json" -> "dictRule"
        "homepageModules.json" -> "homepageModules"
        "homepageCustomSets.json" -> "homepageCustomSets"
        "highlightRule.json" -> "highlightRule"
        "highlightTagRule.json" -> "highlightTagRule"
        "tagGroupRule.json" -> "tagGroupRule"
        "servers.json" -> "server"
        else -> "other"
    }

    /** 清单面向人工核对，状态使用中文；配置键本身仍保留原始名称以便定位。 */
    private fun configKeyStatusLabel(status: BackupConfigKeyStatus): String = when (status) {
        BackupConfigKeyStatus.WRITTEN -> "已写入"
        BackupConfigKeyStatus.IGNORED -> "已忽略"
        BackupConfigKeyStatus.UNSUPPORTED -> "不支持"
        BackupConfigKeyStatus.WRITE_FAILED -> "写入失败"
        BackupConfigKeyStatus.NOT_SET -> "未设置"
    }

    /** 清单同时保留稳定的内部键和用户可读名称，界面只显示名称。 */
    private fun configKeyLabel(key: String): String = when {
        key == PreferKey.webDavUrl -> "WebDAV 地址"
        key == PreferKey.webDavDir -> "WebDAV 目录"
        key == PreferKey.webDavAccount -> "WebDAV 账号"
        key == PreferKey.webDavPassword -> "WebDAV 密码"
        key == PreferKey.backupPath -> "备份路径"
        key == PreferKey.appTheme -> "应用主题"
        key == PreferKey.themeMode -> "主题模式"
        key == PreferKey.language -> "语言"
        key == PreferKey.fontScale -> "字体缩放"
        key == PreferKey.ttsEngine -> "朗读引擎"
        key == PreferKey.ttsSpeechRate -> "朗读语速"
        key == PreferKey.webService -> "Web 服务"
        key == PreferKey.webPort -> "Web 服务端口"
        key == PreferKey.saveTabPosition -> "书架上次选中的分组"
        key == PreferKey.textSelectMenuConfig -> "文本选择菜单配置"
        key == PreferKey.readMenuIconShowText -> "阅读菜单显示图标文字"
        key == PreferKey.readMenuIconStyle -> "阅读菜单图标样式"
        key == PreferKey.defaultCover -> "默认封面"
        key == PreferKey.defaultCoverDark -> "夜间默认封面"
        key == "cover_album_migrated" -> "封面相册迁移状态"
        key == "selected_cover_album_id" -> "当前封面相册"
        key == "privacy_policy_ok" -> "隐私政策确认状态"
        key == "__local_ui_status_migrated_to_settings" -> "界面状态迁移标记"
        else -> "其他配置项"
    }

    /** 记录类别中的数据项数量；加密文件无法解析时回退到当前数据库快照。 */
    private fun manifestCategoryCounts(files: List<File>): Map<String, Int> = buildMap {
        files.groupBy { manifestCategory(it.name) }.forEach { (category, categoryFiles) ->
            val parsed = categoryFiles.mapNotNull { file ->
                GSON.fromJsonArray<Any>(file.readText()).getOrNull()?.size
            }
            val count = if (parsed.size == categoryFiles.size) {
                parsed.sum()
            } else {
                when (category) {
                    "bookshelf" -> appDb.bookDao.all.size
                    "bookmark" -> appDb.bookmarkDao.all.size + appDb.bookMarkingDao.all.size
                    "bookGroup" -> appDb.bookGroupDao.all.size
                    "bookSource" -> appDb.bookSourceDao.all.size
                    "rssSource" -> appDb.rssSourceDao.all.size
                    "rssStar" -> appDb.rssStarDao.all.size
                    "replaceRule" -> appDb.replaceRuleDao.all.size
                    "searchHistory" -> appDb.searchKeywordDao.all.size
                    "readRecord" -> appDb.readRecordDao.all.size
                    "server" -> appDb.serverDao.all.size
                    "sourceSub" -> appDb.ruleSubDao.all.size
                    "txtTocRule" -> appDb.txtTocRuleDao.all.size
                    "httpTTS" -> appDb.httpTTSDao.all.size
                    "keyboardAssists" -> appDb.keyboardAssistsDao.all.size
                    "dictRule" -> appDb.dictRuleDao.all.size
                    else -> categoryFiles.size
                }
            }
            put(category, count)
        }
    }

    /** 按备份根目录保留相对路径，确保 assets 等嵌套资源与 manifest 一致。 */
    private fun zipBackupDirectory(root: File, target: File): Boolean = runCatching {
        val rootPath = root.canonicalFile.toPath()
        ZipOutputStream(FileOutputStream(target)).use { output ->
            root.walkTopDown().filter(File::isFile).forEach { file ->
                val name = rootPath.relativize(file.canonicalFile.toPath()).toString()
                    .replace(File.separatorChar, '/')
                output.putNextEntry(ZipEntry(name))
                BufferedInputStream(FileInputStream(file)).use { input -> input.copyTo(output) }
                output.closeEntry()
            }
        }
        true
    }.getOrDefault(false)

    private fun sanitizeBookSource(source: io.legado.app.data.entities.BookSource) = source.copy(
        header = source.header.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS)
        },
        loginUrl = source.loginUrl.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH)
        },
        loginUi = source.loginUi.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH)
        },
        loginCheckJs = source.loginCheckJs.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH)
        },
    )

    private fun sanitizeRssSource(source: io.legado.app.data.entities.RssSource) = source.copy(
        header = source.header.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS)
        },
        loginUrl = source.loginUrl.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH)
        },
        loginUi = source.loginUi.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH)
        },
        loginCheckJs = source.loginCheckJs.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH)
        },
    )

    private fun sanitizeHttpTts(source: io.legado.app.data.entities.HttpTTS) = source.copy(
        header = source.header.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)
        },
        loginUrl = source.loginUrl.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)
        },
        loginUi = source.loginUi.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)
        },
        loginCheckJs = source.loginCheckJs.takeIf {
            BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)
        },
    )

/** 将配置引用的背景图片复制进 ZIP，避免依赖设备绝对路径或独立 WebDAV 目录。 */
    private fun copyBackgroundResources(rootPath: String): Triple<List<File>, Map<String, String>, List<String>> {
        val targetDir = File(rootPath, "assets/background").apply { mkdirs() }
        val aliases = linkedMapOf<String, String>()
        val configuredPaths = listOf(
            themeSettingsGateway.currentSettings.backgroundImageLight,
            themeSettingsGateway.currentSettings.backgroundImageDark,
            themeSettingsGateway.currentSettings.largeContainerBackgroundImageLight,
            themeSettingsGateway.currentSettings.largeContainerBackgroundImageDark,
            themeSettingsGateway.currentSettings.itemBackgroundImageLight,
            themeSettingsGateway.currentSettings.itemBackgroundImageDark,
        ).filterNotNull()
        val themePackagePaths = ThemeConfigStore.configList.mapNotNull { it.backgroundImgPath }
        val files = mutableListOf<File>()
        val warnings = mutableListOf<String>()
        fun copyPath(path: String) {
            val normalizedPath = path.trim()
            if (normalizedPath.startsWith("content://", ignoreCase = true)) {
                // content:// URI 需要通过 ContentResolver 流式复制
                try {
                    val uri = Uri.parse(normalizedPath)
                    val resolver = appCtx.contentResolver
                    resolver.openInputStream(uri)?.use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        val digest = MessageDigest.getInstance("SHA-256")
                        val tempFile = File.createTempFile("bg_", ".tmp", appCtx.cacheDir)
                        tempFile.outputStream().buffered().use { output ->
                            var count = input.read(buffer)
                            while (count >= 0) {
                                if (count > 0) {
                                    digest.update(buffer, 0, count)
                                    output.write(buffer, 0, count)
                                }
                                count = input.read(buffer)
                            }
                        }
                        val hash = digest.digest().joinToString("") { "%02x".format(it) }
                        val extension = MimeTypeMap.getSingleton().getExtensionFromMimeType(resolver.getType(uri))
                            ?.takeIf { it.isNotBlank() }
                            ?.let { ".${it.lowercase(Locale.ROOT)}" } ?: ".jpg"
                        val target = File(targetDir, "$hash$extension")
                        if (!target.exists()) tempFile.copyTo(target, overwrite = false)
                        tempFile.delete()
                        aliases[path] = "assets/background/${target.name}"
                        if (files.none { it.name == target.name }) files += target
                    } ?: run {
                        val warning = "备份背景图片失败：无法读取 content:// 资源"
                        warnings += warning
                        AppLog.put(warning)
                    }
                } catch (e: Exception) {
                    val warning = "备份背景图片失败：content:// 资源处理异常"
                    warnings += warning
                    AppLog.put(warning, e)
                }
                return
            }
            val isAbsolutePath = normalizedPath.startsWith("/") ||
                normalizedPath.matches(Regex("^[A-Za-z]:[\\\\/].*"))
            val source = if (isAbsolutePath) {
                File(normalizedPath)
            } else {
                appCtx.externalFiles.getFile("bg", normalizedPath)
            }
            if (!source.isFile) {
                warnings += "备份背景图片失败：背景文件不存在"
                return
            }
            val hash = source.sha256()
            val extension = source.extension.takeIf { it.isNotBlank() }
                ?.let { ".${it.lowercase(Locale.ROOT)}" } ?: ""
            val target = File(targetDir, "$hash$extension")
            if (!target.exists()) source.copyTo(target, overwrite = false)
            aliases[source.name] = "assets/background/${target.name}"
            if (files.none { it.name == target.name }) files += target
        }
        (readStyleGateway.allBackgroundImagePaths() + configuredPaths + themePackagePaths)
            .distinct().forEach(::copyPath)
        return Triple(files, aliases, warnings)
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        inputStream().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private suspend fun writeListToJson(list: List<Any>, fileName: String, path: String) {
        currentCoroutineContext().ensureActive()
        withContext(IO) {
            LogUtils.d(TAG, "阅读备份 $fileName 列表大小 ${list.size}")
            // 空列表也必须写入，恢复时才能区分“明确为空”和“备份未包含此类别”。
            val file = FileUtils.createFileIfNotExist(path + File.separator + fileName)
            file.outputStream().buffered().use {
                GSON.writeToOutputStream(it, list)
            }
            LogUtils.d(TAG, "阅读备份 $fileName 写入大小 ${file.length()}")
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(context: Context, uri: Uri, fileName: String) {
        val treeDoc = DocumentFile.fromTreeUri(context, uri)!!
        treeDoc.findFile(fileName)?.delete()
        val fileDoc = treeDoc.createFile("", fileName)
            ?: throw NoStackTraceException("创建文件失败")
        val outputS = fileDoc.openOutputStream()
            ?: throw NoStackTraceException("打开OutputStream失败")
        outputS.use {
            FileInputStream(zipFilePath).use { inputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    @Throws(Exception::class)
    @Suppress("SameParameterValue")
    private fun copyBackup(rootFile: File, fileName: String) {
        FileInputStream(File(zipFilePath)).use { inputS ->
            val file = FileUtils.createFileIfNotExist(rootFile, fileName)
            FileOutputStream(file).use { outputS ->
                inputS.copyTo(outputS)
            }
        }
    }

    fun clearCache() {
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
    }
}

/** 只有实际写入至少一个目标，备份才能报告成功。 */
internal fun classifyBackupOutcome(
    localCreated: Boolean,
    webDavAttempted: Boolean,
    webDavFailed: Boolean,
    warnings: Int,
): BackupRestoreOutcome = when {
    !localCreated && (!webDavAttempted || webDavFailed) -> BackupRestoreOutcome.FAILED
    warnings > 0 -> BackupRestoreOutcome.SUCCESS_WITH_WARNINGS
    else -> BackupRestoreOutcome.SUCCESS
}

/** 清单不保存完整 content URI；备份配置改用稳定占位符，恢复时仍可映射到本机路径。 */
internal fun redactResourceAliases(root: File, aliases: Map<String, String>): Map<String, String> {
    val safeAliases = linkedMapOf<String, String>()
    val configFileNames = setOf(
        "config.xml", ReadBookConfig.configFileName, ReadBookConfig.shareConfigFileName,
        ThemeConfigStore.configFileName, "readSettings.json", "highlightRule.json",
        "highlightTagRule.json", "tagGroupRule.json",
    )
    val configFiles = configFileNames.map { File(root, it) }.filter(File::isFile)
    aliases.forEach { (original, relative) ->
        if (!original.startsWith("content://", ignoreCase = true)) {
            safeAliases[original] = relative
            return@forEach
        }
        val marker = "legado-backup-resource:${stableSensitiveIdentifier(original)}"
        val escapedXml = original.replace("&", "&amp;")
            .replace("<", "&lt;").replace(">", "&gt;")
            .replace("\"", "&quot;").replace("'", "&apos;")
        configFiles.forEach { file ->
            val text = file.readText()
            val replaced = text.replace(original, marker)
                .let { value -> if (file.extension == "xml") value.replace(escapedXml, marker) else value }
            if (replaced != text) file.writeText(replaced)
        }
        safeAliases[marker] = relative
    }
    return safeAliases
}

/** 将可能包含凭据的来源地址转换为不可逆但稳定的清单标识。 */
internal fun stableSensitiveIdentifier(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(16)
}
