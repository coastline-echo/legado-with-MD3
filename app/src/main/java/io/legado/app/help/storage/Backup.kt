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
                                outcome = BackupRestoreOutcome.FAILED,
                                failures = 1,
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

        val (resourceFiles, resourceAliases, resourceWarnings) = copyBackgroundResources(backupPath)
        val manifestWarnings = resourceWarnings + sensitiveWarnings
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.PROCESS_RESOURCES, resourceFiles.size, resourceFiles.size))

        val filesForManifest = backupFileNames
            .filter { it != "manifest.json" }
            .map { File(backupPath, it) }
            .filter(File::isFile)
        val configIntegrity = BackupConfigIntegrityChecker.check(
            snapshotKeys = configMap.keys,
            written = writtenConfigKeys,
            ignored = ignoredConfigKeys,
            unsupported = unsupportedConfigKeys,
            requiredKeys = requiredConfigKeys,
        )
        warningCount += configIntegrity.unsupported.size +
            configIntegrity.failed.size + configIntegrity.notSet.size + manifestWarnings.size
        val manifest = BackupManifestBuilder.build(
            root = File(backupPath),
            files = filesForManifest,
            categories = filesForManifest.groupBy { manifestCategory(it.name) }
                .mapValues { (_, files) -> files.map(File::getName) },
            categoryCounts = manifestCategoryCounts(filesForManifest),
            ignoredCategories = BackupConfig.backupIgnoreConfig.filterValues { it }.keys,
            restoreIgnoredCategories = buildSet {
                addAll(BackupConfig.dbIgnoreConfig.filterValues { it }.keys)
                if (BackupConfig.ignoreReadConfig) add("readConfig")
                if (BackupConfig.ignoreThemeConfig) add("themeConfig")
                if (BackupConfig.ignoreCoverConfig) add("coverConfig")
            },
            configKeys = writtenConfigKeys,
            ignoredConfigKeys = ignoredConfigKeys,
            unsupportedConfigKeys = unsupportedConfigKeys,
            failedConfigKeys = configIntegrity.failed,
            notSetConfigKeys = configIntegrity.notSet,
            appVersion = BuildConfig.VERSION_NAME,
            resources = resourceFiles,
            resourceAliases = resourceAliases,
            warnings = manifestWarnings,
sensitiveIncluded = filesForManifest.any { it.name == "servers.json" } ||
                filesForManifest.any { it.name == DirectLinkUpload.ruleFileName } ||
                PreferKey.webDavPassword in writtenConfigKeys ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH) ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS) ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH) ||
                BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS),
sensitiveItems = buildSet {
                if (filesForManifest.any { it.name == "servers.json" }) {
                    add(BackupSensitiveCategory.SERVER_CREDENTIALS.name)
                    appDb.serverDao.all.forEach { server -> add("server:${server.id}") }
                }
                if (PreferKey.webDavPassword in writtenConfigKeys) {
                    add(BackupSensitiveCategory.WEBDAV_CREDENTIALS.name)
                }
                if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.BOOK_SOURCE_AUTH)) {
                    add(BackupSensitiveCategory.BOOK_SOURCE_AUTH.name)
                    appDb.bookSourceDao.all.forEach { source -> add("bookSource:${stableSensitiveIdentifier(source.bookSourceUrl)}") }
                }
                if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.REQUEST_HEADERS)) {
                    add(BackupSensitiveCategory.REQUEST_HEADERS.name)
                    appDb.bookSourceDao.all.filter { !it.header.isNullOrBlank() }.forEach { source -> add("bookSourceHeader:${stableSensitiveIdentifier(source.bookSourceUrl)}") }
                }
                if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.RSS_AUTH)) {
                    add(BackupSensitiveCategory.RSS_AUTH.name)
                    appDb.rssSourceDao.all.forEach { source -> add("rssSource:${stableSensitiveIdentifier(source.sourceUrl)}") }
                }
                if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.TTS_API_KEYS)) {
                    add(BackupSensitiveCategory.TTS_API_KEYS.name)
                    appDb.httpTTSDao.all.forEach { source -> add("httpTTS:${source.id}") }
                }
                if (filesForManifest.any { it.name == DirectLinkUpload.ruleFileName }) {
                    add(BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS.name)
                }
            },
            weakProtection = BackupPasswordPolicyResolver.resolve(
                BackupPasswordResolver.resolve(
                    explicitPassword = null,
                    storedBackupPassword = LocalConfig.backupPassword,
                    applicationPassword = LocalConfig.password,
                ).password,
            ).weakProtection,
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
                outcome = if (warningCount == 0) BackupRestoreOutcome.SUCCESS
                else BackupRestoreOutcome.SUCCESS_WITH_WARNINGS,
                warnings = warningCount,
                writtenConfigKeys = configIntegrity.written.size,
                ignoredConfigKeys = configIntegrity.ignored.size,
                unsupportedConfigKeys = configIntegrity.unsupported.size,
                failedConfigKeys = configIntegrity.failed.size,
                notSetConfigKeys = configIntegrity.notSet.size,
                ignoredConfigKeyNames = configIntegrity.ignored,
                unsupportedConfigKeyNames = configIntegrity.unsupported,
                failedConfigKeyNames = configIntegrity.failed,
                notSetConfigKeyNames = configIntegrity.notSet,
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

/** 将可能包含凭据的来源地址转换为不可逆但稳定的清单标识。 */
internal fun stableSensitiveIdentifier(value: String): String {
    val digest = MessageDigest.getInstance("SHA-256")
    return digest.digest(value.toByteArray(Charsets.UTF_8))
        .joinToString("") { byte -> "%02x".format(byte) }
        .take(16)
}
