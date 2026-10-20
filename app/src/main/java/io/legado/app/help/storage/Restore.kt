package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import androidx.room.withTransaction
import io.legado.app.BuildConfig
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookGroup
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.Bookmark
import io.legado.app.data.entities.BookMarking
import io.legado.app.data.entities.DictRule
import io.legado.app.data.entities.HighlightRule
import io.legado.app.data.entities.HighlightTagRule
import io.legado.app.data.entities.HomepageCustomSet
import io.legado.app.data.entities.HomepageModule
import io.legado.app.data.entities.HttpTTS
import io.legado.app.data.entities.KeyboardAssist
import io.legado.app.data.entities.ReplaceRule
import io.legado.app.data.entities.RssSource
import io.legado.app.data.entities.RssStar
import io.legado.app.data.entities.RuleSub
import io.legado.app.data.entities.SearchKeyword
import io.legado.app.data.entities.Server
import io.legado.app.data.entities.TagGroupRule
import io.legado.app.data.entities.TxtTocRule
import io.legado.app.data.entities.readRecord.ReadRecord
import io.legado.app.data.entities.readRecord.ReadRecordDetail
import io.legado.app.data.entities.readRecord.ReadRecordSession
import io.legado.app.data.entities.readRecord.ReadRecordIdentity
import io.legado.app.data.repository.ReadRecordRepository
import io.legado.app.data.repository.HttpTtsRepository
import io.legado.app.data.entities.ReadAloudVoiceEntity
import io.legado.app.help.readaloud.speech.HttpTtsCatalogParser
import io.legado.app.domain.gateway.AppLocaleGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.gateway.ReadSettingsGateway
import io.legado.app.domain.model.settings.ReadSettings
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.ui.book.read.ConfigUpdateAction
import io.legado.app.ui.book.read.ReadConfigUpdateBus
import io.legado.app.help.DirectLinkUpload
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.LauncherIconHelp
import io.legado.app.help.book.isLocal
import io.legado.app.help.book.upType
import io.legado.app.help.config.AppConfigStore
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.config.ReadBookConfig
import io.legado.app.help.config.SettingsWriter
import io.legado.app.help.config.ThemeConfigStore
import io.legado.app.model.BookCover
import io.legado.app.model.localBook.LocalBook
import io.legado.app.utils.ACache
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonArray
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.getPrefString
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
import io.legado.app.utils.isJson
import io.legado.app.utils.isUri
import io.legado.app.utils.openInputStream
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.Dispatchers.Main
import kotlinx.coroutines.Dispatchers.IO
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.koin.core.component.KoinComponent
import org.koin.core.component.get
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/**
 * 恢复
 */
object Restore : KoinComponent {

    private const val TAG = "Restore"

    // 阅读器当前只使用本地记录分区。旧版备份可能保留设备 Android ID，
    // 恢复时须归一化，否则同一本书会因 deviceId 不同而显示为两条记录。
    private const val LOCAL_READ_RECORD_DEVICE_ID = ""

/** 只读取 ZIP 结构和 manifest，不触碰应用数据。 */
    suspend fun preview(context: Context, uri: Uri): RestorePreview = withContext(IO) {
        fun openInput() = if (uri.isContentScheme()) {
            DocumentFile.fromSingleUri(context, uri)?.openInputStream()
        } else {
            FileInputStream(File(uri.path!!))
        } ?: error("无法读取备份文件")

        // 单次读取 ZIP 到临时目录，避免重复打开输入流
        val previewDir = File(context.cacheDir, "restore_preview_${System.nanoTime()}")
            val scan = runCatching {
                previewDir.mkdirs()
                openInput().use { input -> ZipUtils.unZipToPath(input, previewDir.absolutePath) }
            // 兼容旧版导出的 ZIP 外层多包一层目录的情况。
            val root = normalizedBackupRoot(previewDir)
            val scanResult = BackupManifestScanner.scan(root)
            val detailed = previewUnzipped(root.absolutePath, scanResult)
            detailed.copy(
                warnings = (scanResult.warnings + detailed.warnings).distinct(),
            )
        }.getOrElse {
            AppLog.put("恢复预览数据扫描失败：${it.localizedMessage}", it)
            RestorePreview(false, 0, 0, emptyMap(), listOf("读取备份失败：${it.localizedMessage}"))
        }.also {
            previewDir.deleteRecursively()
        }
        return@withContext scan
    }

    /** 兼容原版或旧版 ZIP 将全部文件放在单层目录下的导出结构。 */
    private fun normalizedBackupRoot(root: File): File {
        var current = root
        while (true) {
            val files = current.listFiles().orEmpty()
            if (files.any { it.isFile } || files.any { it.isDirectory && it.name == "assets" }) return current
            val directories = files.filter(File::isDirectory)
            if (directories.size != 1) return current
            current = directories.single()
        }
    }

    /** WebDAV 已下载并解压到临时目录时使用的预览入口。 */
    internal fun previewUnzipped(
        path: String,
        scan: BackupScanResult = BackupManifestScanner.scan(File(path)),
    ): RestorePreview {
        val root = File(path)
        val files = root.walkTopDown().filter(File::isFile).map { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }.toList()
        val payloadFiles = files.filterNot { it == "manifest.json" || it == "integrity.json" }
        val resourcePaths = payloadFiles.filter { it.startsWith("assets/") || it.startsWith("background/") }
        val categories = payloadFiles.filterNot { it in resourcePaths || it == "httpTtsVoices.json" }
            .groupingBy(::previewCategory).eachCount().toMutableMap()
        scan.manifest?.categories
            ?.filter { it.files.isNotEmpty() }
            ?.forEach { categories[it.id] = it.count }
        resourcePaths.size.takeIf { it > 0 }?.let { categories["resources"] = it }
        val legacy = !File(root, "manifest.json").isFile
        return RestorePreview(
            legacyFormat = legacy,
            fileCount = payloadFiles.size - resourcePaths.size,
            resourceCount = resourcePaths.size,
            categories = categories,
            warnings = scan.warnings,
            sensitiveIncluded = scan.manifest?.sensitiveIncluded == true,
            weakProtection = scan.manifest?.weakProtection == true,
            sensitiveItems = scan.manifest?.sensitiveItems.orEmpty(),
            applicationCounts = applicationCategoryCounts(),
            emptyCategories = explicitEmptyCategories(root, categories.keys),
            missingCategories = previewCategories().minus(categories.keys),
            ignoredCategories = mergedIgnoredCategories(scan.manifest?.ignoredCategories.orEmpty()),
            backupIgnoredCategories = scan.manifest?.ignoredCategories.orEmpty(),
            restoreIgnoredCategories = currentRestoreIgnoredCategories() +
                scan.manifest?.restoreIgnoredCategories.orEmpty(),
            conflictCounts = previewConflictCounts(root),
        )
    }

/** 预览阶段按稳定主键统计冲突，不执行任何写入。 */
    private fun previewConflictCounts(root: File): Map<String, Int> = buildMap {
        // 每类只读取一次现有身份，避免按备份项重复访问 DAO。
        val bookUrls = appDb.bookDao.allBookUrls.toHashSet()
        val sourceUrls = appDb.bookSourceDao.all.mapTo(hashSetOf()) { it.bookSourceUrl }
        val serverIds = appDb.serverDao.all.mapTo(hashSetOf()) { it.id }
        val rssSourceUrls = appDb.rssSourceDao.all.mapTo(hashSetOf()) { it.sourceUrl }
        val replaceRuleIds = appDb.replaceRuleDao.all.mapTo(hashSetOf()) { it.id }
        val bookmarkTimes = appDb.bookmarkDao.all.mapTo(hashSetOf()) { it.time }
        val markingIds = appDb.bookMarkingDao.all.mapTo(hashSetOf()) { it.id }
        val groupIds = appDb.bookGroupDao.all.mapTo(hashSetOf()) { it.groupId }
        val searchWords = appDb.searchKeywordDao.all.mapTo(hashSetOf()) { it.word }
        val homepageModuleIds = appDb.homepageModuleDao.getAll().mapTo(hashSetOf()) { it.id }
        val homepageSetIds = appDb.homepageCustomSetDao.getAll().mapTo(hashSetOf()) { it.id }
        val highlightIds = appDb.highlightRuleDao.getAll().mapTo(hashSetOf()) { it.id }
        val highlightTagIds = appDb.highlightTagRuleDao.getAll().mapTo(hashSetOf()) { it.id }
        val tagGroupIds = appDb.tagGroupRuleDao.getAll().mapTo(hashSetOf()) { it.id }
        val readRecordIds = appDb.readRecordDao.all.mapTo(hashSetOf()) { it.bookName to it.bookAuthor }
        val rssStarIds = appDb.rssStarDao.all.mapTo(hashSetOf()) { it.origin to it.link }
        val keyboardIds = appDb.keyboardAssistsDao.all.mapTo(hashSetOf()) { it.type to it.key }
        val httpTtsIds = appDb.httpTTSDao.all.mapTo(hashSetOf()) { it.id }
        val sourceSubIds = appDb.ruleSubDao.all.mapTo(hashSetOf()) { it.id }
        val tocRuleIds = appDb.txtTocRuleDao.all.mapTo(hashSetOf()) { it.id }
        val dictRuleNames = appDb.dictRuleDao.all.mapTo(hashSetOf()) { it.name }
        // 所有目录类别都返回显式数量；没有可比较身份时也必须显示 0，不能让 UI 把缺失误判为未扫描。
        putAll(BackupRestoreCategoryCatalog.allCategories.associateWith { 0 })
        File(root, "bookshelf.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<Book>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookshelf", values.count { it.bookUrl in bookUrls })
        }
        readJsonArrayOrNull<BookSource>(root, "bookSource.json")?.let { values ->
            put("bookSource", values.count { it.bookSourceUrl in sourceUrls })
        }
        readJsonArrayOrNull<Server>(root, "servers.json")?.let { values ->
            put("server", values.count { it.id in serverIds })
        }
        readJsonArrayOrNull<RssSource>(root, "rssSources.json")?.let { values ->
            put("rssSource", values.count { it.sourceUrl in rssSourceUrls })
        }
        File(root, "replaceRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<ReplaceRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("replaceRule", values.count { it.id in replaceRuleIds })
        }
        File(root, "bookmark.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<Bookmark>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookmark", values.count { it.time in bookmarkTimes })
        }
        File(root, "bookMarking.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<BookMarking>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookmark", (this["bookmark"] ?: 0) + values.count { item ->
                item.id in markingIds
            })
        }
        File(root, "bookGroup.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<BookGroup>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookGroup", values.count { it.groupId in groupIds })
        }
        File(root, "searchHistory.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<SearchKeyword>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("searchHistory", values.count { it.word in searchWords })
        }
        File(root, "homepageModules.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HomepageModule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("homepageModules", values.count { it.id in homepageModuleIds })
        }
        File(root, "homepageCustomSets.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HomepageCustomSet>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("homepageCustomSets", values.count { it.id in homepageSetIds })
        }
        File(root, "highlightRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HighlightRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("highlightRule", values.count { it.id in highlightIds })
        }
        File(root, "highlightTagRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HighlightTagRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("highlightTagRule", values.count { it.id in highlightTagIds })
        }
        File(root, "tagGroupRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<TagGroupRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("tagGroupRule", values.count { it.id in tagGroupIds })
        }
        File(root, "readRecord.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<ReadRecord>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("readRecord", values.count { item ->
                (item.bookName to item.bookAuthor) in readRecordIds
            })
        }
        File(root, "rssStar.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<RssStar>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("rssStar", values.count { item ->
                (item.origin to item.link) in rssStarIds
            })
        }
File(root, "keyboardAssists.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<KeyboardAssist>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("keyboardAssists", values.count { item ->
                (item.type to item.key) in keyboardIds
            })
        }
        readJsonArrayOrNull<HttpTTS>(root, "httpTTS.json")?.let { values ->
            put("httpTTS", values.count { it.id in httpTtsIds })
        }
        readJsonArrayOrNull<RuleSub>(root, "sourceSub.json")?.let { values ->
            put("sourceSub", values.count { it.id in sourceSubIds })
        }
        readJsonArrayOrNull<TxtTocRule>(root, "txtTocRule.json")?.let { values ->
            put("txtTocRule", values.count { it.id in tocRuleIds })
        }
        readJsonArrayOrNull<DictRule>(root, "dictRule.json")?.let { values ->
            put("dictRule", values.count { it.name in dictRuleNames })
        }
        File(root, DirectLinkUpload.ruleFileName).takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonObject<DirectLinkUpload>(file.readText()).getOrNull() }.getOrNull()
            if (values != null) put("directLinkUploadRule", if (ACache.get(cacheDir = false).getAsString(DirectLinkUpload.ruleFileName).isNullOrBlank()) 0 else 1)
        }
        File(root, "readSettings.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonObject<ReadSettings>(file.readText()).getOrNull() }.getOrNull()
            if (values != null) put("readSettings", 1)
        }
        if (File(root, "config.xml").isFile) {
            put("settings", 1)
        }
        if (File(root, ReadBookConfig.configFileName).isFile && File(ReadBookConfig.configFilePath).isFile) {
            put("readConfig", 1)
        }
        if (File(root, ThemeConfigStore.configFileName).isFile && File(ThemeConfigStore.configFilePath).isFile) {
            put("themeConfig", 1)
        }
        if (File(root, BookCover.configFileName).isFile && BookCover.getConfig() != null) {
            put("coverConfig", 1)
        }
    }

private fun explicitEmptyCategories(root: File, categories: Set<String>): Set<String> =
        categories.filterTo(linkedSetOf()) { category ->
            val files = root.walkTopDown().filter(File::isFile)
                .filter { previewCategory(it.name) == category }
                // HTTP 音色为空不代表 HTTP TTS 引擎为空。
                .filter { category != "httpTTS" || it.name == "httpTTS.json" }
            files.any() && files.all { it.readText().trim() == "[]" }
        }

    /** 读取可能加密的 JSON 数组，预览阶段无密码时返回 null 跳过冲突统计。 */
    private inline fun <reified T> readJsonArrayOrNull(root: File, fileName: String): List<T>? {
        return runCatching {
            val file = File(root, fileName)
            if (!file.isFile) return@runCatching null
            val text = file.readText()
            // 如果是加密内容（非 JSON 数组），预览阶段无法解密，返回 null
            if (!text.isJsonArray()) return@runCatching null
            GSON.fromJsonArray<T>(text).getOrNull()
        }.getOrNull()?.orEmpty()
    }

    /** 预览阶段读取应用现有数量，不执行任何恢复写入。 */
    private fun applicationCategoryCounts(): Map<String, Int> = linkedMapOf(
        "bookshelf" to appDb.bookDao.all.size,
        "bookmark" to appDb.bookmarkDao.all.size,
        "bookGroup" to appDb.bookGroupDao.all.size,
        "bookSource" to appDb.bookSourceDao.all.size,
        "rssSource" to appDb.rssSourceDao.all.size,
        "rssStar" to appDb.rssStarDao.all.size,
        "replaceRule" to appDb.replaceRuleDao.all.size,
        "searchHistory" to appDb.searchKeywordDao.all.size,
        "readRecord" to appDb.readRecordDao.all.size,
        "server" to appDb.serverDao.all.size,
        "sourceSub" to appDb.ruleSubDao.all.size,
        "txtTocRule" to appDb.txtTocRuleDao.all.size,
        "httpTTS" to appDb.httpTTSDao.all.size,
        "keyboardAssists" to appDb.keyboardAssistsDao.all.size,
        "dictRule" to appDb.dictRuleDao.all.size,
        "homepageModules" to appDb.homepageModuleDao.getAll().size,
        "homepageCustomSets" to appDb.homepageCustomSetDao.getAll().size,
        "highlightRule" to appDb.highlightRuleDao.getAll().size,
        "highlightTagRule" to appDb.highlightTagRuleDao.getAll().size,
        "tagGroupRule" to appDb.tagGroupRuleDao.getAll().size,
    )

private fun previewCategories(): Set<String> = BackupRestoreCategoryCatalog.allCategories

    private fun mergedIgnoredCategories(backupIgnored: Set<String>): Set<String> = buildSet {
        addAll(backupIgnored)
        BackupConfig.dbIgnoreConfig.filterValues { it }.keys.forEach(::add)
        if (BackupConfig.ignoreReadConfig) add("readConfig")
        if (BackupConfig.ignoreThemeConfig) add("themeConfig")
        if (BackupConfig.ignoreCoverConfig) add("coverConfig")
    }

    private fun currentRestoreIgnoredCategories(): Set<String> = buildSet {
        addAll(BackupConfig.dbIgnoreConfig.filterValues { it }.keys)
        if (BackupConfig.ignoreReadConfig) add("readConfig")
        if (BackupConfig.ignoreThemeConfig) add("themeConfig")
        if (BackupConfig.ignoreCoverConfig) add("coverConfig")
    }

private fun previewCategory(name: String): String = when (name) {
        "bookshelf.json" -> "bookshelf"
        "bookmark.json", "bookMarking.json" -> "bookmark"
        "bookGroup.json" -> "bookGroup"
        "bookSource.json" -> "bookSource"
        "rssSources.json" -> "rssSource"
        "rssStar.json" -> "rssStar"
        "replaceRule.json" -> "replaceRule"
        "readRecord.json", "readRecordDetail.json", "readRecordSession.json" -> "readRecord"
        "searchHistory.json" -> "searchHistory"
        "config.xml" -> "settings"
        "readConfig.json", "shareReadConfig.json" -> "readConfig"
        "themeConfig.json" -> "themeConfig"
        "assets/background" -> "resources"
        "readSettings.json" -> "readSettings"
        "httpTTS.json", "httpTtsVoices.json" -> "httpTTS"
        else -> name.substringBeforeLast('.', name)
    }

suspend fun restore(
        context: Context,
        uri: Uri,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
        backupPassword: String? = null,
    ): String {
        val operationId = java.util.UUID.randomUUID().toString()
        BackupRestoreLock.withLock {
            LogUtils.d(TAG, "开始恢复备份 uri:$uri")
            val unzipResult = kotlin.runCatching {
                FileUtils.delete(Backup.backupPath)
                if (uri.isContentScheme()) {
                    DocumentFile.fromSingleUri(context, uri)!!.openInputStream()!!.use {
                        ZipUtils.unZipToPath(it, Backup.backupPath)
                    }
                } else {
                    ZipUtils.unZipToPath(File(uri.path!!), Backup.backupPath)
                }
            }.onFailure {
                AppLog.put("复制解压文件出错\n${it.localizedMessage}", it)
                BackupRestoreResultBus.emit(
                    BackupRestoreResult(
                        operationId = operationId,
                        outcome = BackupRestoreOutcome.FAILED,
                        failures = 1,
                        unzipFailed = true,
                    )
                )
            }
            if (unzipResult.isSuccess) {
                kotlin.runCatching {
                    // 旧版 ZIP 可能把备份文件放在唯一的外层目录中。
                    val root = normalizedBackupRoot(File(Backup.backupPath))
                    val restoreOperationId = restoreUnzipped(
                        root.absolutePath,
                        mode,
                        selectedCategories,
                        categoryModes,
                        backupPassword,
                        operationId,
                    )
                    BackupRestoreResultBus.getResult(restoreOperationId)
                        ?.takeUnless {
                            it.outcome == BackupRestoreOutcome.FAILED ||
                                it.outcome == BackupRestoreOutcome.PRECONDITION_FAILED
                        }
                        ?.let { LocalConfig.lastBackup = System.currentTimeMillis() }
                }.onFailure {
                    appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
                    AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
                    BackupRestoreResultBus.emit(
                        BackupRestoreResult(
                            operationId = operationId,
                            outcome = BackupRestoreOutcome.FAILED,
                            failures = 1,
                            parseFailed = true,
                        )
                    )
                }
            }
        }
        return operationId
    }

suspend fun restoreLocked(
        path: String,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
        backupPassword: String? = null,
    ): String {
        return BackupRestoreLock.withLock {
            restoreUnzipped(path, mode, selectedCategories, categoryModes, backupPassword)
        }
    }

    internal suspend fun restoreUnzipped(
        path: String,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
        backupPassword: String? = null,
        operationId: String = java.util.UUID.randomUUID().toString(),
    ): String {
        val scan = BackupManifestScanner.scan(File(path))
        scan.warnings.forEach { warning ->
            AppLog.put("恢复备份校验警告：$warning")
        }
        BackupRestoreProgressBus.emit(
            BackupRestoreProgress(
                operationId,
                stage = BackupRestoreStage.READ_BACKUP,
                warnings = scan.warnings.size,
            )
        )
        restore(
            path,
            mode,
            selectedCategories,
            categoryModes,
            backupPassword,
            allowLegacyPlaintextFallback = scan.legacyFormat,
            operationId = operationId,
            backupManifest = scan.manifest,
        )
        return operationId
    }

private suspend fun restore(
        path: String,
        mode: RestoreMode,
        selectedCategories: Set<String>,
        categoryModes: Map<String, RestoreMode>,
        backupPassword: String? = null,
    allowLegacyPlaintextFallback: Boolean = true,
    operationId: String,
    backupManifest: BackupManifest? = null,
    ) {
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.READ_BACKUP))
        // 统一由 planner 决定类别是否存在、是否被忽略以及覆盖/保留模式；
        // 各 DAO 仍负责实体主键的具体合并策略。
        val backupRestoreIgnoredCategories = BackupManifestScanner.scan(File(path)).manifest
            ?.restoreIgnoredCategories.orEmpty()
        val plannerInputs = restorePlannerCategories(path, backupRestoreIgnoredCategories, backupPassword)
        val categoryPlans = RestoreCategoryPlanner.planByCategory(
            inputs = plannerInputs,
            modes = if (mode == RestoreMode.CLEAR_THEN_RESTORE) emptyMap() else categoryModes,
            defaultMode = mode.takeUnless { it == RestoreMode.BY_CATEGORY } ?: RestoreMode.KEEP_APPLICATION,
        ).associateBy { it.category }
        // 按同一类别目录记录选择范围；只把用户未勾选的类别展示为“未处理”，
        // 不参与实际恢复统计，也不把忽略/失败误报成“无变化”。
        val availableCategories = plannerInputs
            .filter { it.backupPresent &&
                (mode != RestoreMode.CLEAR_THEN_RESTORE || it.backupItemIds.isNotEmpty()) }
            .mapTo(linkedSetOf()) { it.category }
        val chosenCategories = if (mode == RestoreMode.BY_CATEGORY || mode == RestoreMode.CLEAR_THEN_RESTORE) {
            availableCategories.intersect(selectedCategories)
        } else {
            availableCategories
        }
        val explicitlyEmptyCategories = File(path).walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .filter { it.name != "httpTtsVoices.json" }
            .groupBy { previewCategory(it.name) }
            .filterValues { files -> files.all { it.readText().trim() == "[]" } }
            .keys
        // 按类别/清空恢复只统计用户勾选且实际可执行的类别；
        // 之前把未勾选类别的 planner 结果也带进 categoryStats，导致详情页
        // 显示“已恢复”类别和数量高于实际执行内容。
        val progressCategories = categoryPlans.values
            .filter {
                it.mode != null &&
                    !it.skippedByIgnore &&
                    (mode != RestoreMode.BY_CATEGORY && mode != RestoreMode.CLEAR_THEN_RESTORE ||
                        it.category in selectedCategories)
            }
            .map { it.category }
.toSet()
        val reportedCategories = hashSetOf<String>()
        fun stageForCategory(category: String): BackupRestoreStage = when (category) {
            "bookshelf" -> BackupRestoreStage.RESTORE_BOOKSHELF
            "bookmark", "bookGroup", "bookSource", "rssSource", "rssStar", "replaceRule", "searchHistory", "readRecord", "server", "sourceSub", "txtTocRule", "httpTTS", "keyboardAssists", "dictRule", "homepageModules", "homepageCustomSets", "highlightRule", "highlightTagRule", "tagGroupRule" -> BackupRestoreStage.RESTORE_DATABASE
            "settings", "readConfig", "themeConfig", "coverConfig", "directLinkUploadRule", "readSettings" -> BackupRestoreStage.RESTORE_CONFIG
            "resources" -> BackupRestoreStage.RESTORE_RESOURCES
            else -> BackupRestoreStage.RESTORE_CONFIG
        }
        fun selected(category: String): Boolean {
            val plan = categoryPlans[category]
            // 全局模式保留旧行为（例如缺少 config.xml 仍生成明确失败报告）；
            // 只有 BY_CATEGORY 才由 planner 过滤缺失/忽略类别。
            val enabled = if (mode == RestoreMode.BY_CATEGORY || mode == RestoreMode.CLEAR_THEN_RESTORE) {
                category in selectedCategories && plan?.mode != null && !plan.skippedByIgnore
            } else {
                // 备份 manifest 的恢复忽略和当前设备忽略在所有模式都优先于类别选择。
                plan?.skippedByIgnore != true
            }
            if (enabled && reportedCategories.add(category)) {
                BackupRestoreProgressBus.emit(
                    BackupRestoreProgress(
                        operationId,
                        stage = stageForCategory(category),
                        current = reportedCategories.size,
                        total = progressCategories.size,
                        category = category,
                    )
                )
            }
            return enabled
        }
        fun useBackup(category: String): Boolean =
            if (category in explicitlyEmptyCategories) {
                false
            } else if (mode == RestoreMode.KEEP_APPLICATION && category in BackupRestoreCategoryCatalog.configurationCategories) {
                // 普通恢复保留数据库中的应用独有记录，但配置文件和设置键按备份更新。
                true
            } else if (mode == RestoreMode.BY_CATEGORY) {
                categoryPlans[category]?.mode == RestoreMode.USE_BACKUP ||
                    categoryPlans[category]?.mode == RestoreMode.CLEAR_THEN_RESTORE
            } else {
                mode == RestoreMode.USE_BACKUP || mode == RestoreMode.CLEAR_THEN_RESTORE
            }
        // 空输入表示沿用当前设备本地密码；只有非空输入才覆盖默认密码。
        // 跨设备恢复时，用户输入原设备设置的同一个本地密码即可解密。
        val aes = BackupAES(backupPassword.takeIf { !it.isNullOrEmpty() })
        var categoryFailures = 0
        val failedCategories = linkedSetOf<String>()
        val failedCategoryCounts = linkedMapOf<String, Int>()
        val failedCategoryDetails = linkedMapOf<String, MutableList<String>>()
        var configFileMissing = false
        var configFileParseFailed = false
        val decryptionFailedConfigKeys = linkedSetOf<String>()
        fun reportCategoryFailure(category: String, error: Throwable) {
            categoryFailures++
            failedCategories += category
            failedCategoryCounts[category] = (failedCategoryCounts[category] ?: 0) + 1
            error.localizedMessage?.takeIf { it.isNotBlank() }?.let { detail ->
                failedCategoryDetails.getOrPut(category) { mutableListOf() }.add(detail)
            }
            AppLog.put("恢复类别失败：$category\n${error.localizedMessage}", error)
            BackupRestoreProgressBus.emit(
                BackupRestoreProgress(
                    operationId,
                    stage = BackupRestoreStage.RESTORE_CONFIG,
                    category = category,
                    failures = categoryFailures,
                    failedCategoryCounts = failedCategoryCounts.toMap(),
                )
            )
        }
        if (selected("resources")) {
            val resourceFailures = restoreBackgroundResources(path)
if (resourceFailures > 0) {
                categoryFailures += resourceFailures
                failedCategories += "resources"
                failedCategoryCounts["resources"] =
                    (failedCategoryCounts["resources"] ?: 0) + resourceFailures
                BackupRestoreProgressBus.emit(
                    BackupRestoreProgress(
                        operationId,
                        stage = BackupRestoreStage.RESTORE_RESOURCES,
                        failures = categoryFailures,
                        failedCategoryCounts = failedCategoryCounts.toMap(),
                    )
                )
            }
        }
        if (selected("bookshelf") && BackupConfig.dbIsNotIgnored("bookshelf")) {
            try {
                readListStrict<Book>(path, "bookshelf.json") { reportCategoryFailure("bookshelf", it) }?.let {
            val existingBooks = appDb.bookDao.all
            it.forEach { book ->
                book.upType()
            }
            val restorePlan = planBookRestore(
                restoredBooks = it,
                existingBooks = existingBooks,
                ignoreLocalBook = BackupConfig.ignoreLocalBook,
                locationStatus = ::localBookLocationStatus,
            )
            restorePlan.booksToUpsert
                .filter { book -> book.isLocal }
                .forEach { book -> book.coverUrl = LocalBook.getCoverPath(book) }
            val strictReplacementDeletes = if (categoryPlans["bookshelf"]?.clearApplication == true) {
                val retainedUrls = restorePlan.booksToUpsert.mapTo(hashSetOf()) { it.bookUrl }
                existingBooks.filter { book ->
                    book.bookUrl !in retainedUrls && !(BackupConfig.ignoreLocalBook && book.isLocal)
                }
            } else emptyList()
            appDb.withTransaction {
                val booksToDelete = (restorePlan.booksToDelete + strictReplacementDeletes)
                    .distinctBy { it.bookUrl }
                if (booksToDelete.isNotEmpty()) {
                    appDb.bookDao.delete(*booksToDelete.toTypedArray())
                }
                if (restorePlan.booksToUpdate.isNotEmpty()) {
                    appDb.bookDao.update(*restorePlan.booksToUpdate.toTypedArray())
                }
                if (restorePlan.booksToInsert.isNotEmpty()) {
                    appDb.bookDao.insert(*restorePlan.booksToInsert.toTypedArray())
                }
            }
                }
            } catch (error: Exception) {
                reportCategoryFailure("bookshelf", error)
            }
        }
// 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (selected("bookmark") && BackupConfig.dbIsNotIgnored("bookmark")) {
            val bookmarkFile = File(path, "bookmark.json")
            val markingFile = File(path, "bookMarking.json")
            val bookmarks = readListStrict<Bookmark>(path, "bookmark.json") { reportCategoryFailure("bookmark", it) }
            val markings = readListStrict<BookMarking>(path, "bookMarking.json") { reportCategoryFailure("bookmark", it) }
            val parseFailed = (bookmarkFile.isFile && bookmarks == null) ||
                (markingFile.isFile && markings == null)
            if (!parseFailed && (bookmarks != null || markings != null)) {
                try {
                    appDb.withTransaction {
                        if (categoryPlans["bookmark"]?.clearApplication == true) {
                            // 旧备份可能只有书签文件；缺少划线文件时不可删除设备上的划线。
                            if (bookmarks != null) appDb.bookmarkDao.deleteAll()
                            if (markings != null) appDb.bookMarkingDao.deleteAll()
                        }
                        bookmarks?.let { values ->
                            val existing = appDb.bookmarkDao.all.mapTo(hashSetOf()) { it.time }
                            val (toUpdate, toInsert) = values.partition { it.time in existing }
                            toUpdate.forEach { appDb.bookmarkDao.update(it) }
                            appDb.bookmarkDao.insert(*toInsert.toTypedArray())
                        }
                        markings?.let { values ->
                            val existing = appDb.bookMarkingDao.all.mapTo(hashSetOf()) { it.id }
                            val (toUpdate, toInsert) = values.partition { it.id in existing }
                            toUpdate.forEach { appDb.bookMarkingDao.upsert(it) }
                            appDb.bookMarkingDao.insert(*toInsert.toTypedArray())
                        }
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("bookmark", error)
                }
            }
        }
if (selected("bookGroup") && BackupConfig.dbIsNotIgnored("bookGroup")) {
            readListStrict<BookGroup>(path, "bookGroup.json") { reportCategoryFailure("bookGroup", it) }?.let { groups ->
                val plan = categoryPlans["bookGroup"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.bookGroupDao.deleteAll()
                        }
                        val existing = appDb.bookGroupDao.all.mapTo(hashSetOf()) { it.groupId }
                        val (toUpdate, toInsert) = groups.partition { it.groupId in existing }
                        appDb.bookGroupDao.update(*toUpdate.toTypedArray())
                        appDb.bookGroupDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("bookGroup", error)
                }
            }
        }
        if (selected("bookSource") && BackupConfig.dbIsNotIgnored("bookSource")) {
            File(path, "bookSource.json").takeIf {
                it.exists()
            }?.runCatching {
                val sources = readSensitiveListStrict<BookSource>(this, aes)
                val plan = categoryPlans["bookSource"]
                appDb.withTransaction {
                    if (plan?.clearApplication == true) {
                        appDb.bookSourceDao.deleteAll()
                    }
                    val existing = appDb.bookSourceDao.all.mapTo(hashSetOf()) { it.bookSourceUrl }
                    val (toUpdate, toInsert) = sources.partition { it.bookSourceUrl in existing }
                    appDb.bookSourceDao.update(*toUpdate.toTypedArray())
                    appDb.bookSourceDao.insert(*toInsert.toTypedArray())
                        }
            }?.onFailure {
                reportCategoryFailure("bookSource", it)
                AppLog.put("恢复书源配置出错\n${it.localizedMessage}", it)
            }
        }
        if (selected("rssSource") && BackupConfig.dbIsNotIgnored("rssSource")) {
            File(path, "rssSources.json").takeIf {
                it.exists()
            }?.runCatching {
                val sources = readSensitiveListStrict<RssSource>(this, aes)
                val plan = categoryPlans["rssSource"]
                appDb.withTransaction {
                    if (plan?.clearApplication == true) {
                        appDb.rssSourceDao.deleteAll()
                    }
                    val existing = appDb.rssSourceDao.all.mapTo(hashSetOf()) { it.sourceUrl }
                    val (toUpdate, toInsert) = sources.partition { it.sourceUrl in existing }
                    appDb.rssSourceDao.update(*toUpdate.toTypedArray())
                    appDb.rssSourceDao.insert(*toInsert.toTypedArray())
                        }
            }?.onFailure {
                reportCategoryFailure("rssSource", it)
                AppLog.put("恢复 RSS 配置出错\n${it.localizedMessage}", it)
            }
        }
if (selected("rssStar") && BackupConfig.dbIsNotIgnored("rssStar")) {
            readListStrict<RssStar>(path, "rssStar.json") { reportCategoryFailure("rssStar", it) }?.let { stars ->
                val plan = categoryPlans["rssStar"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.rssStarDao.deleteAll()
                        }
                        val existing = appDb.rssStarDao.all.mapTo(hashSetOf()) { "${it.origin}|${it.link}" }
                        val (toUpdate, toInsert) = stars.partition { "${it.origin}|${it.link}" in existing }
                        appDb.rssStarDao.update(*toUpdate.toTypedArray())
                        appDb.rssStarDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("rssStar", error)
                }
            }
        }
if (selected("replaceRule") && BackupConfig.dbIsNotIgnored("replaceRule")) {
            readListStrict<ReplaceRule>(path, "replaceRule.json") { reportCategoryFailure("replaceRule", it) }?.let { rules ->
                val plan = categoryPlans["replaceRule"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.replaceRuleDao.deleteAll()
                        }
                        val existing = appDb.replaceRuleDao.all.mapTo(hashSetOf()) { it.id }
                        val (toUpdate, toInsert) = rules.partition { it.id in existing }
                        appDb.replaceRuleDao.update(*toUpdate.toTypedArray())
                        appDb.replaceRuleDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("replaceRule", error)
                }
            }
        }
if (selected("searchHistory") && BackupConfig.dbIsNotIgnored("searchHistory")) {
            readListStrict<SearchKeyword>(path, "searchHistory.json") { reportCategoryFailure("searchHistory", it) }?.let { keywords ->
                val plan = categoryPlans["searchHistory"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.searchKeywordDao.deleteAll()
                        }
                        val existing = appDb.searchKeywordDao.all.mapTo(hashSetOf()) { it.word }
                        val (toUpdate, toInsert) = keywords.partition { it.word in existing }
                        appDb.searchKeywordDao.update(*toUpdate.toTypedArray())
                        appDb.searchKeywordDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("searchHistory", error)
                }
            }
        }
if (selected("sourceSub") && BackupConfig.dbIsNotIgnored("sourceSub")) {
            readListStrict<RuleSub>(path, "sourceSub.json") { reportCategoryFailure("sourceSub", it) }?.let { subs ->
                val plan = categoryPlans["sourceSub"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.ruleSubDao.deleteAll()
                        }
                        val existing = appDb.ruleSubDao.all.mapTo(hashSetOf()) { it.id }
                        val (toUpdate, toInsert) = subs.partition { it.id in existing }
                        appDb.ruleSubDao.update(*toUpdate.toTypedArray())
                        appDb.ruleSubDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("sourceSub", error)
                }
            }
        }
        if (selected("txtTocRule") && BackupConfig.dbIsNotIgnored("txtTocRule")) {
            readListStrict<TxtTocRule>(path, "txtTocRule.json") { reportCategoryFailure("txtTocRule", it) }?.let { rules ->
                val plan = categoryPlans["txtTocRule"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.txtTocRuleDao.deleteAll()
                        }
                        val existing = appDb.txtTocRuleDao.all.mapTo(hashSetOf()) { it.id }
                        val (toUpdate, toInsert) = rules.partition { it.id in existing }
                        appDb.txtTocRuleDao.update(*toUpdate.toTypedArray())
                        appDb.txtTocRuleDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("txtTocRule", error)
                }
            }
        }
        if (selected("httpTTS") && BackupConfig.dbIsNotIgnored("httpTTS")) {
            File(path, "httpTTS.json").takeIf {
                it.exists()
            }?.runCatching {
                val ttss = readSensitiveListStrict<HttpTTS>(this, aes)
                // Main 将语音目录保存在一个独立的权威表中。
                // 在仓库的一个事务中将源和声音一起提交之前，解析每个输入。
                val content = readText()
                val json = if (content.isJsonArray()) content else BackupSensitiveEnvelope.decrypt(aes, content)
                val drafts = HttpTtsCatalogParser.parseList(json)
                require(drafts.size == ttss.size) { "httpTTS 目录条数与源条数不一致" }
                val voiceFile = File(path, "httpTtsVoices.json")
                val voices = if (voiceFile.isFile) {
                    GSON.fromJsonArray<ReadAloudVoiceEntity>(voiceFile.readText()).getOrThrow()
                } else null
                get<HttpTtsRepository>().restore(
                    entries = ttss.zip(drafts),
                    voices = voices,
                    clearApplication = categoryPlans["httpTTS"]?.clearApplication == true,
                )
            }?.onFailure {
                reportCategoryFailure("httpTTS", it)
                AppLog.put("恢复 HTTP TTS 配置出错\n${it.localizedMessage}", it)
            }
        }
        if (selected("dictRule") && BackupConfig.dbIsNotIgnored("dictRule")) {
            readListStrict<DictRule>(path, "dictRule.json") { reportCategoryFailure("dictRule", it) }?.let { rules ->
                val plan = categoryPlans["dictRule"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.dictRuleDao.deleteAll()
                        }
                        val existing = appDb.dictRuleDao.all.mapTo(hashSetOf()) { it.name }
                        val (toUpdate, toInsert) = rules.partition { it.name in existing }
                        appDb.dictRuleDao.update(*toUpdate.toTypedArray())
                        appDb.dictRuleDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("dictRule", error)
                }
            }
        }
if (selected("keyboardAssists") && BackupConfig.dbIsNotIgnored("keyboardAssists")) {
            readListStrict<KeyboardAssist>(path, "keyboardAssists.json") { reportCategoryFailure("keyboardAssists", it) }?.let { assists ->
                val plan = categoryPlans["keyboardAssists"]
                try {
                    appDb.withTransaction {
                        if (plan?.clearApplication == true) {
                            appDb.keyboardAssistsDao.deleteAll()
                        }
                        val existing = appDb.keyboardAssistsDao.all.mapTo(hashSetOf()) { "${it.type}|${it.key}" }
                        val (toUpdate, toInsert) = assists.partition { "${it.type}|${it.key}" in existing }
                        appDb.keyboardAssistsDao.update(*toUpdate.toTypedArray())
                        appDb.keyboardAssistsDao.insert(*toInsert.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("keyboardAssists", error)
                }
            }
        }
        if (selected("homepageModules") && BackupConfig.dbIsNotIgnored("homepageModules")) {
            readListStrict<HomepageModule>(path, "homepageModules.json") { reportCategoryFailure("homepageModules", it) }?.let {
                try {
                    appDb.withTransaction {
                        if (categoryPlans["homepageModules"]?.clearApplication == true) {
                            appDb.homepageModuleDao.replaceAll(it)
                        } else {
                            appDb.homepageModuleDao.upsertAll(it)
                        }
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("homepageModules", error)
                }
            }
        }
        if (selected("homepageCustomSets") && BackupConfig.dbIsNotIgnored("homepageCustomSets")) {
            readListStrict<HomepageCustomSet>(path, "homepageCustomSets.json") { reportCategoryFailure("homepageCustomSets", it) }?.let {
                runCatching {
                    appDb.withTransaction {
                        if (categoryPlans["homepageCustomSets"]?.clearApplication == true) {
                            appDb.homepageCustomSetDao.replaceAll(it)
                        } else {
                            it.forEach { item -> appDb.homepageCustomSetDao.upsert(item) }
                        }
                    }
                }.onFailure { reportCategoryFailure("homepageCustomSets", it) }
            }
        }
        if (selected("highlightRule") && BackupConfig.dbIsNotIgnored("highlightRule")) {
            readListStrict<HighlightRule>(path, "highlightRule.json") { reportCategoryFailure("highlightRule", it) }?.let {
                runCatching {
                    appDb.withTransaction {
                        if (categoryPlans["highlightRule"]?.clearApplication == true) {
                            appDb.highlightRuleDao.replaceAll(it)
                        } else {
                            val existing = appDb.highlightRuleDao.getAll()
                            val byId = existing.associateBy { item -> item.id }.toMutableMap()
                            val byIdentity = existing.associateBy(::highlightRuleIdentity).toMutableMap()
                            it.distinctBy(::highlightRuleIdentity).forEach { item ->
                                val matched = byId[item.id] ?: byIdentity[highlightRuleIdentity(item)]
                                if (matched != null) {
                                    val normalized = item.copy(id = matched.id)
                                    appDb.highlightRuleDao.update(normalized)
                                    byId[matched.id] = normalized
                                    byIdentity[highlightRuleIdentity(normalized)] = normalized
                                } else {
                                    appDb.highlightRuleDao.insertAll(listOf(item))
                                    byId[item.id] = item
                                    byIdentity[highlightRuleIdentity(item)] = item
                                }
                            }
                        }
                    }
                }.onFailure { reportCategoryFailure("highlightRule", it) }
            }
        }
        if (selected("highlightTagRule") && BackupConfig.dbIsNotIgnored("highlightTagRule")) {
            readListStrict<HighlightTagRule>(path, "highlightTagRule.json") { reportCategoryFailure("highlightTagRule", it) }?.let {
                runCatching {
                    appDb.withTransaction {
                        if (categoryPlans["highlightTagRule"]?.clearApplication == true) {
                            appDb.highlightTagRuleDao.replaceAll(it)
                        } else {
                            val existing = appDb.highlightTagRuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                            appDb.highlightTagRuleDao.update(*it.filter { item -> item.id in existing }.toTypedArray())
                            appDb.highlightTagRuleDao.insert(*it.filter { item -> item.id !in existing }.toTypedArray())
                        }
                    }
                }.onFailure { reportCategoryFailure("highlightTagRule", it) }
            }
        }
        if (selected("tagGroupRule") && BackupConfig.dbIsNotIgnored("tagGroupRule")) {
            readListStrict<TagGroupRule>(path, "tagGroupRule.json") { reportCategoryFailure("tagGroupRule", it) }?.let {
                runCatching {
                    appDb.withTransaction {
                        if (categoryPlans["tagGroupRule"]?.clearApplication == true) {
                            appDb.tagGroupRuleDao.replaceAll(it)
                        } else {
                            val existing = appDb.tagGroupRuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                            appDb.tagGroupRuleDao.update(*it.filter { item -> item.id in existing }.toTypedArray())
                            appDb.tagGroupRuleDao.insert(*it.filter { item -> item.id !in existing }.toTypedArray())
                        }
                    }
                }.onFailure { reportCategoryFailure("tagGroupRule", it) }
            }
        }
if (selected("readRecord") && BackupConfig.dbIsNotIgnored("readRecord")) {
            val records = readListStrict<ReadRecord>(path, "readRecord.json") { reportCategoryFailure("readRecord", it) }
            val details = readListStrict<ReadRecordDetail>(path, "readRecordDetail.json") { reportCategoryFailure("readRecord", it) }
            val sessions = readListStrict<ReadRecordSession>(path, "readRecordSession.json") { reportCategoryFailure("readRecord", it) }
            val parseFailed = (File(path, "readRecord.json").isFile && records == null) ||
                (File(path, "readRecordDetail.json").isFile && details == null) ||
                (File(path, "readRecordSession.json").isFile && sessions == null)
            if (!parseFailed) {
                try {
                    appDb.withTransaction {
                        if (categoryPlans["readRecord"]?.clearApplication == true) {
                            appDb.readRecordDao.clear()
                            appDb.readRecordDao.clearDetails()
                            appDb.readRecordDao.clearSessions()
                        }
                        records.orEmpty().forEach { restoreReadRecord(it) }
                        details.orEmpty().forEach { restoreReadRecordDetail(it) }
                        sessions.orEmpty().forEach { restoreReadRecordSession(it) }
                        // 归一化和汇总重算属于阅读记录同一类别，必须和导入处于同一事务。
                        reconcileReadRecordAliases()
                        get<ReadRecordRepository>().reconcileRestoredReadRecordTotals()
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("readRecord", error)
                }
            }
        }
        if (selected("server") && BackupConfig.dbIsNotIgnored("server")) {
            File(path, "servers.json").takeIf {
                it.exists()
            }?.runCatching {
                val servers = readSensitiveListStrict<Server>(this, aes)
                appDb.withTransaction {
                    if (categoryPlans["server"]?.clearApplication == true) appDb.serverDao.deleteAll()
                    val existing = appDb.serverDao.all.mapTo(hashSetOf()) { server -> server.id }
                    appDb.serverDao.update(*servers.filter { server -> server.id in existing }.toTypedArray())
                    appDb.serverDao.insert(*servers.filter { server -> server.id !in existing }.toTypedArray())
                }
            }?.onFailure {
                reportCategoryFailure("server", it)
                AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
            }
        }
File(path, DirectLinkUpload.ruleFileName).takeIf {
            selected("directLinkUploadRule") &&
            it.exists()
        }?.runCatching {
            var json = readText()
            if (!json.isJson()) {
                json = BackupSensitiveEnvelope.decrypt(aes, json)
            }
            if (!json.isJson()) error("直链上传配置格式无效")
            val cache = ACache.get(cacheDir = false)
            if (useBackup("directLinkUploadRule") || cache.getAsString(DirectLinkUpload.ruleFileName).isNullOrBlank()) {
                cache.put(DirectLinkUpload.ruleFileName, json)
            }
        }?.onFailure {
            reportCategoryFailure("directLinkUploadRule", it)
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        if (selected("themeConfig") && !BackupConfig.ignoreThemeConfig) {
            File(path, ThemeConfigStore.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                if (!readText().isJson()) error("主题配置格式无效")
                val target = File(ThemeConfigStore.configFilePath)
                if (useBackup("themeConfig") || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ThemeConfigStore.configFilePath)
                    ThemeConfigStore.upConfig()
                }
            }?.onFailure {
                reportCategoryFailure("themeConfig", it)
                AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
            }
        }
        File(path, BookCover.configFileName).takeIf {
            selected("coverConfig") &&
            it.exists() && !BackupConfig.ignoreCoverConfig
        }?.runCatching {
            val json = readText()
            if (!json.isJson()) error("封面规则配置格式无效")
            if (useBackup("coverConfig") || BookCover.getConfig() == null) {
                BookCover.saveCoverRule(json)
            }
        }?.onFailure {
            reportCategoryFailure("coverConfig", it)
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (selected("readConfig") && !BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                if (!readText().isJson()) error("阅读器配置格式无效")
                val target = File(ReadBookConfig.configFilePath)
                if (useBackup("readConfig") || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ReadBookConfig.configFilePath)
                }
            }?.onFailure {
                reportCategoryFailure("readConfig", it)
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                if (!readText().isJson()) error("共享阅读配置格式无效")
                val target = File(ReadBookConfig.shareConfigFilePath)
                if (useBackup("readConfig") || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ReadBookConfig.shareConfigFilePath)
                }
            }?.onFailure {
                reportCategoryFailure("readConfig", it)
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            // 两个文件都落地后再整体重读：分开重读会让 shareConfig 的兜底
            // （configList[5]）取到还没被覆盖的旧列表。refresh 顺带发布 state。
            get<ReadStyleGateway>().refresh()
            // refresh 只重建 Compose 侧 state；阅读器开着时渲染层的两份快照（RenderStyle/
            // TipStyle）与已排版内容不会跟着刷新，得走配置总线让 controller 重建并重排。
            // 阅读器没开时无人消费，重开后由 Compose 阅读路由的分页重建入口兜底。
            ReadConfigUpdateBus.post(
                setOf(
                    ConfigUpdateAction.UpdateBackground,
                    ConfigUpdateAction.UpdateStyle,
                    ConfigUpdateAction.ReloadContent,
                    ConfigUpdateAction.RebuildWholeBookPageIndex,
                )
            )
        }
        // 恢复配置文件 (手动解析 XML，替代反射逻辑)
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.RESTORE_CONFIG))
        val configFile = File(path, "config.xml")
        if (selected("settings") && !configFile.exists()) {
            configFileMissing = true
            reportCategoryFailure("settings", NoStackTraceException("备份缺少 config.xml"))
        } else if (selected("settings")) {
            val map = try {
                readXmlToMap(configFile)
            } catch (e: Exception) {
                configFileParseFailed = true
                reportCategoryFailure("settings", e)
                AppLog.put("恢复配置 XML 出错\n${e.localizedMessage}", e)
                null
            }
            if (!map.isNullOrEmpty()) {
                try {
                    val decryptionFailures = applyConfigMap(
                        map,
                        aes,
                        if (useBackup("settings")) RestoreMode.USE_BACKUP else RestoreMode.KEEP_APPLICATION,
                        allowLegacyPlaintextFallback,
                        clearMissing = categoryPlans["settings"]?.mode == RestoreMode.CLEAR_THEN_RESTORE,
                    )
                    decryptionFailedConfigKeys += decryptionFailures
                    if (decryptionFailures.isNotEmpty()) {
                        reportCategoryFailure(
                            "settings",
                            NoStackTraceException("配置敏感字段解密失败：${decryptionFailures.joinToString()}"),
                        )
                    }
                } catch (e: Exception) {
                    reportCategoryFailure("settings", e)
                    AppLog.put("应用配置写入失败\n${e.localizedMessage}", e)
                }
            }
        }
        // 恢复完整 ReadSettings（文本选择菜单、阅读器 UI 设置等）
        File(path, "readSettings.json").takeIf {
            it.isFile && selected("readSettings") && useBackup("readSettings")
        }?.runCatching {
            val json = readText()
            get<ReadSettingsGateway>().importJson(
                json,
                clearMissing = categoryPlans["readSettings"]?.clearApplication == true,
            )
        }?.onFailure {
            reportCategoryFailure("readSettings", it)
            AppLog.put("恢复阅读设置出错\n${it.localizedMessage}", it)
        }
        // 所有独立配置与 config.xml 写入完成后再刷新运行时快照，避免读取到半成品。
        runCatching { get<ReadStyleGateway>().refresh() }.onFailure {
            reportCategoryFailure("readConfig", it)
            AppLog.put("恢复后刷新阅读配置失败\n${it.localizedMessage}", it)
        }
        runCatching {
            ReadConfigUpdateBus.post(
                setOf(
                    ConfigUpdateAction.UpdateBackground,
                    ConfigUpdateAction.UpdateStyle,
                    ConfigUpdateAction.ReloadContent,
                    ConfigUpdateAction.RefreshTextSelectionMenu,
                    ConfigUpdateAction.RefreshReadMenuButtons,
                )
            )
        }.onFailure {
            reportCategoryFailure("readConfig", it)
            AppLog.put("恢复后通知阅读配置失败\n${it.localizedMessage}", it)
        }

        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.REFRESH_RUNTIME))
        runCatching {
            withContext(Main) {
                delay(100)
                get<AppLocaleGateway>().apply { setLanguage(currentLanguage) }
                if (!BuildConfig.DEBUG) {
                    LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
                }
                ThemeConfigStore.applyDayNight(appCtx)
            }
        }.onFailure {
            reportCategoryFailure("settings", it)
            AppLog.put("恢复后应用主题/语言状态失败\n${it.localizedMessage}", it)
        }
        BackupRestoreProgressBus.emit(BackupRestoreProgress(operationId, BackupRestoreStage.COMPLETE))
        val categoryStats = categoryPlans.values
            .filter { it.category in progressCategories }
            .associate { plan ->
            val stats = plan.toStats(failedCategoryCounts[plan.category] ?: 0)
            plan.category to if (plan.category == "bookmark" && plan.clearApplication) {
                // 同一类别有两个文件；旧备份缺哪个文件就保留对应本机数据。
                val deleted = if ("bookmark" in failedCategories) 0 else plan.applicationItemIds.count { id ->
                    (id.startsWith("bookmark:") && File(path, "bookmark.json").isFile) ||
                        (id.startsWith("marking:") && File(path, "bookMarking.json").isFile)
                }
                stats.copy(deleted = deleted)
            } else stats
        }
BackupRestoreResultBus.emit(
            BackupRestoreResult(
                operationId = operationId,
                // 结果按本次实际尝试的类别集合判断，不再用“失败类别数量 < 3”的固定阈值。
                outcome = classifyRestoreOutcome(
                    failedCategories = failedCategories,
                    attemptedCategories = progressCategories,
                    configFileMissing = configFileMissing,
                    configFileParseFailed = configFileParseFailed,
                ),
                failures = categoryFailures,
                failedCategories = failedCategories,
                failedCategoryCounts = failedCategoryCounts.toMap(),
                failedCategoryDetails = failedCategoryDetails.mapValues { (_, details) -> details.toList() },
                categoryStats = categoryStats,
                restoreAvailableCategories = availableCategories,
                restoreSelectedCategories = chosenCategories,
                configFileMissing = configFileMissing,
                configFileParseFailed = configFileParseFailed,
                decryptionFailedConfigKeyNames = decryptionFailedConfigKeys.toSet(),
                backupCategoryStatus = backupManifest?.categories?.associate { it.id to it.status }.orEmpty(),
                backupCategoryLabels = backupManifest?.categories?.associate { it.id to it.name }.orEmpty(),
                backupCategoryStatusDetails = backupManifest?.categories?.associate { it.id to it.detail }.orEmpty(),
                backupEmptyCategories = backupManifest?.categories
                    ?.filter { it.files.isNotEmpty() && it.count == 0 }
                    ?.mapTo(linkedSetOf<String>()) { it.id }.orEmpty(),
                backupSensitiveStatus = backupManifest?.sensitiveCategories?.associate { it.id to it.status }.orEmpty(),
                backupConfigKeyStatus = backupManifest?.technical?.configItems
                    ?.filter { !it.key.isNullOrBlank() }
                    ?.associate { it.key!! to it.status }
                    .orEmpty(),
                backupConfigKeyLabels = backupManifest?.technical?.configItems
                    ?.filter { !it.key.isNullOrBlank() }
                    ?.associate { it.key!! to it.name }
                    .orEmpty(),
                backupFileNames = backupManifest?.let { manifest ->
                    manifest.files.map { it.name } + manifest.resources.map { it.name }
                }.orEmpty(),
            )
        )
    }

    private fun localBookLocationStatus(bookUrl: String): LocalBookLocationStatus {
        val uri = bookUrl.takeIf { it.isUri() }?.toUri()
        if (uri?.isContentScheme() == true) {
            // Provider 离线、临时权限问题与文件确实删除无法可靠区分，失败时保守保留记录。
            return kotlin.runCatching {
                if (appCtx.contentResolver.openInputStream(uri)?.use { true } == true) {
                    LocalBookLocationStatus.Available
                } else {
                    LocalBookLocationStatus.Unknown
                }
            }.getOrDefault(LocalBookLocationStatus.Unknown)
        }

        val file = File(uri?.path ?: bookUrl)
        if (file.isFile) return LocalBookLocationStatus.Available
        return when (runCatching { Environment.getExternalStorageState(file) }.getOrNull()) {
            Environment.MEDIA_MOUNTED,
            Environment.MEDIA_MOUNTED_READ_ONLY -> LocalBookLocationStatus.Missing

            Environment.MEDIA_UNKNOWN -> LocalBookLocationStatus.Unknown
            null -> LocalBookLocationStatus.Unknown
            else -> LocalBookLocationStatus.Unknown
        }
    }

private fun restorePlannerCategories(
    path: String,
    backupRestoreIgnoredCategories: Set<String> = emptySet(),
    backupPassword: String? = null,
): List<RestoreCategoryInput> {
        return BackupRestoreCategoryCatalog.databaseFiles.map { (category, fileName) ->
            val file = File(path, fileName)
            val present = file.isFile || (category == "bookmark" && File(path, "bookMarking.json").isFile)
            val ignored = when {
                category in backupRestoreIgnoredCategories -> true
                category in BackupConfig.dbIgnoreConfig.filterValues { it }.keys -> true
                else -> false
            }
            val backupIds = if (present) getBackupItemIds(category, file, backupPassword) else emptySet<String>()
            val appIds = getApplicationItemIds(category)
            RestoreCategoryInput(
                category = category,
                backupPresent = present,
                backupItemIds = backupIds,
                applicationItemIds = appIds,
                ignored = ignored,
            )
        } + listOf(
            // 文件型类别用稳定的虚拟身份参与统计，避免主题和阅读配置恢复后结果仍显示为 0。
            RestoreCategoryInput(
                category = "resources",
                backupPresent = File(path, "assets").exists() || File(path, "background").isDirectory,
                backupItemIds = setOf("resources").takeIf {
                    File(path, "assets").exists() || File(path, "background").isDirectory
                }.orEmpty(),
                ignored = "resources" in backupRestoreIgnoredCategories,
            ),
            RestoreCategoryInput(
                category = "settings",
                backupPresent = File(path, "config.xml").isFile,
                backupItemIds = setOf("settings").takeIf { File(path, "config.xml").isFile }.orEmpty(),
                ignored = "settings" in backupRestoreIgnoredCategories,
            ),
            RestoreCategoryInput(
                category = "readConfig",
                backupPresent = File(path, ReadBookConfig.configFileName).isFile,
                backupItemIds = setOf("readConfig").takeIf { File(path, ReadBookConfig.configFileName).isFile }.orEmpty(),
                ignored = "readConfig" in backupRestoreIgnoredCategories || BackupConfig.ignoreReadConfig,
            ),
            RestoreCategoryInput(
                category = "themeConfig",
                backupPresent = File(path, ThemeConfigStore.configFileName).isFile,
                backupItemIds = setOf("themeConfig").takeIf { File(path, ThemeConfigStore.configFileName).isFile }.orEmpty(),
                ignored = "themeConfig" in backupRestoreIgnoredCategories || BackupConfig.ignoreThemeConfig,
            ),
            RestoreCategoryInput(
                category = "coverConfig",
                backupPresent = File(path, BookCover.configFileName).isFile,
                backupItemIds = setOf("coverConfig").takeIf { File(path, BookCover.configFileName).isFile }.orEmpty(),
                ignored = "coverConfig" in backupRestoreIgnoredCategories || BackupConfig.ignoreCoverConfig,
            ),
            RestoreCategoryInput(
                category = "directLinkUploadRule",
                backupPresent = File(path, DirectLinkUpload.ruleFileName).isFile,
                backupItemIds = setOf("directLinkUploadRule").takeIf { File(path, DirectLinkUpload.ruleFileName).isFile }.orEmpty(),
                ignored = "directLinkUploadRule" in backupRestoreIgnoredCategories,
            ),
            RestoreCategoryInput(
                category = "readSettings",
                backupPresent = File(path, "readSettings.json").isFile,
                backupItemIds = setOf("readSettings").takeIf { File(path, "readSettings.json").isFile }.orEmpty(),
                ignored = "readSettings" in backupRestoreIgnoredCategories,
            ),
        )
    }

    private fun getBackupItemIds(category: String, file: File, backupPassword: String?): Set<String> {
        return try {
            when (category) {
                "bookshelf" -> GSON.fromJsonArray<Book>(file.readText()).getOrNull().orEmpty().map { it.bookUrl }.toSet()
                "bookmark" -> {
                    val bookmarks = if (file.isFile) {
                        GSON.fromJsonArray<Bookmark>(file.readText()).getOrThrow()
                            .mapTo(mutableSetOf()) { "bookmark:${it.time}" }
                    } else emptySet()
                    val markingFile = File(file.parentFile, "bookMarking.json")
                    val markings = if (markingFile.isFile) {
                        GSON.fromJsonArray<BookMarking>(markingFile.readText()).getOrThrow()
                            .mapTo(mutableSetOf()) { "marking:${it.id}" }
                    } else emptySet()
                    bookmarks + markings
                }
                "bookGroup" -> GSON.fromJsonArray<BookGroup>(file.readText()).getOrNull().orEmpty().map { it.groupId.toString() }.toSet()
                "bookSource" -> GSON.fromJsonArray<BookSource>(file.readText()).getOrNull().orEmpty().map { it.bookSourceUrl }.toSet()
                "rssSource" -> GSON.fromJsonArray<RssSource>(file.readText()).getOrNull().orEmpty().map { it.sourceUrl }.toSet()
                "rssStar" -> GSON.fromJsonArray<RssStar>(file.readText()).getOrNull().orEmpty().map { "${it.origin}|${it.link}" }.toSet()
                "replaceRule" -> GSON.fromJsonArray<ReplaceRule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "searchHistory" -> GSON.fromJsonArray<SearchKeyword>(file.readText()).getOrNull().orEmpty().map { it.word }.toSet()
                "readRecord" -> GSON.fromJsonArray<ReadRecord>(file.readText()).getOrNull().orEmpty().map { "${it.bookName}|${it.bookAuthor}" }.toSet()
                "server" -> readSensitiveListStrict<Server>(
                    file, BackupAES(backupPassword.takeIf { !it.isNullOrEmpty() })
                ).map { it.id.toString() }.toSet()
                "sourceSub" -> GSON.fromJsonArray<RuleSub>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "txtTocRule" -> GSON.fromJsonArray<TxtTocRule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "httpTTS" -> readSensitiveListStrict<HttpTTS>(
                    file, BackupAES(backupPassword.takeIf { !it.isNullOrEmpty() })
                ).map { it.id.toString() }.toSet()
                "keyboardAssists" -> GSON.fromJsonArray<KeyboardAssist>(file.readText()).getOrNull().orEmpty().map { "${it.type}|${it.key}" }.toSet()
                "dictRule" -> GSON.fromJsonArray<DictRule>(file.readText()).getOrNull().orEmpty().map { it.name }.toSet()
                "homepageModules" -> GSON.fromJsonArray<HomepageModule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "homepageCustomSets" -> GSON.fromJsonArray<HomepageCustomSet>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "highlightRule" -> GSON.fromJsonArray<HighlightRule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "highlightTagRule" -> GSON.fromJsonArray<HighlightTagRule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "tagGroupRule" -> GSON.fromJsonArray<TagGroupRule>(file.readText()).getOrNull().orEmpty().map { it.id.toString() }.toSet()
                "readSettings" -> setOf("readSettings")
                else -> emptySet<String>()
            }
        } catch (e: Exception) {
            // 无法解密或 JSON 损坏不等于明确空数组；正式导入会严格解析并上报失败。
            if (category == "server" || category == "httpTTS" || category == "bookmark")
                setOf("unreadable:$category")
            else emptySet()
        }
    }

    private fun getApplicationItemIds(category: String): Set<String> {
        return when (category) {
            "bookshelf" -> appDb.bookDao.all.map { it.bookUrl }.toSet()
            "bookmark" -> appDb.bookmarkDao.all.map { "bookmark:${it.time}" }.toSet() +
                appDb.bookMarkingDao.all.map { "marking:${it.id}" }.toSet()
            "bookGroup" -> appDb.bookGroupDao.all.map { it.groupId.toString() }.toSet()
            "bookSource" -> appDb.bookSourceDao.all.map { it.bookSourceUrl }.toSet()
            "rssSource" -> appDb.rssSourceDao.all.map { it.sourceUrl }.toSet()
            "rssStar" -> appDb.rssStarDao.all.map { "${it.origin}|${it.link}" }.toSet()
            "replaceRule" -> appDb.replaceRuleDao.all.map { it.id.toString() }.toSet()
            "searchHistory" -> appDb.searchKeywordDao.all.map { it.word }.toSet()
            "readRecord" -> appDb.readRecordDao.all.map { "${it.bookName}|${it.bookAuthor}" }.toSet()
            "server" -> appDb.serverDao.all.map { it.id.toString() }.toSet()
            "sourceSub" -> appDb.ruleSubDao.all.map { it.id.toString() }.toSet()
            "txtTocRule" -> appDb.txtTocRuleDao.all.map { it.id.toString() }.toSet()
            "httpTTS" -> appDb.httpTTSDao.all.map { it.id.toString() }.toSet()
            "keyboardAssists" -> appDb.keyboardAssistsDao.all.map { "${it.type}|${it.key}" }.toSet()
            "dictRule" -> appDb.dictRuleDao.all.map { it.name }.toSet()
            "homepageModules" -> appDb.homepageModuleDao.getAll().map { it.id.toString() }.toSet()
            "homepageCustomSets" -> appDb.homepageCustomSetDao.getAll().map { it.id.toString() }.toSet()
            "highlightRule" -> appDb.highlightRuleDao.getAll().map { it.id.toString() }.toSet()
            "highlightTagRule" -> appDb.highlightTagRuleDao.getAll().map { it.id.toString() }.toSet()
            "tagGroupRule" -> appDb.tagGroupRuleDao.getAll().map { it.id.toString() }.toSet()
            "readSettings" -> setOf("readSettings")
            else -> emptySet<String>()
        }
    }

    /** 恢复 ZIP 内按 hash 命名的背景资源；单张图片失败不影响其他类别。 */
    private suspend fun restoreBackgroundResources(path: String): Int {
        val scan = BackupManifestScanner.scan(File(path))
        val manifest = scan.manifest
        val targetDir = appCtx.externalFiles.getFile("bg").apply { mkdirs() }
        var failures = 0
        val restoredResources = linkedSetOf<String>()
        // 兼容旧 WebDAV background/目录，以及调试期曾写入资源但未登记
        // manifest.resources 的 ZIP：这两类都只能按文件名迁移，不能做摘要校验。
        // 不能仅以 manifest 是否存在判断，否则“清单存在但资源列表为空”的备份会被静默跳过。
        if (manifest == null || manifest.resources.isEmpty()) {
            val legacyDirs = listOf(File(path, "assets/background"), File(path, "background"))
            val restoredNames = linkedSetOf<String>()
            legacyDirs.flatMap { it.listFiles()?.filter(File::isFile).orEmpty() }
                .distinctBy { it.name }
                .forEach { source ->
                runCatching {
                    source.copyTo(targetDir.resolve(source.name), overwrite = true)
                    restoredNames += source.name
                }.onFailure {
                    failures++
                    AppLog.put("恢复旧版背景图片失败：${source.name}\n${it.localizedMessage}", it)
                }
            }
            if (restoredNames.isNotEmpty()) {
                listOf(
                    File(path, "config.xml"),
                    File(path, ReadBookConfig.configFileName),
                    File(path, ReadBookConfig.shareConfigFileName),
                    File(path, ThemeConfigStore.configFileName),
                    File(path, "readSettings.json"),
                    File(path, "highlightRule.json"),
                    File(path, "highlightTagRule.json"),
                    File(path, "tagGroupRule.json"),
                ).filter(File::isFile).forEach { file ->
                    var text = file.readText()
                    restoredNames.forEach { name ->
                        text = text.replace(name, targetDir.resolve(name).absolutePath)
                    }
                    file.writeText(text)
                }

                // 同步更新设备上的 SharedPreferences 背景图片键
                val backupConfigFile = File(path, "config.xml")
                if (backupConfigFile.isFile) {
                    val backupConfigMap = try {
                        readXmlToMap(backupConfigFile)
                    } catch (e: Exception) {
                        AppLog.put("读取备份配置失败，无法同步背景图片设置：${e.localizedMessage}", e)
                        emptyMap<String, Any?>()
                    }
                    val backgroundKeys = setOf(
                        PreferKey.bgImage,
                        PreferKey.bgImageN,
                        PreferKey.bgImageBlurring,
                        PreferKey.bgImageNBlurring,
                    )
                    val backgroundValues = backupConfigMap.filterKeys { it in backgroundKeys }
                    if (backgroundValues.isNotEmpty()) {
                        AppConfigStore.putAllAndAwait(backgroundValues)
                        SettingsWriter.awaitPendingWrites()
                    }
                }
            }
            return failures
        }
        manifest.resources.forEach { resource ->
            val targetDir = resourceTargetDirectory(resource.name) ?: return@forEach
            runCatching {
                val source = File(path, resource.name)
                if (!source.isFile) error("资源不存在")
                if (source.length() != resource.size) error("资源大小不匹配")
                if (source.sha256() != resource.sha256) error("资源校验失败")
                targetDir.mkdirs()
                val target = File(targetDir, source.name)
                source.copyTo(target, overwrite = true)
                restoredResources += resource.name
            }.onFailure { error ->
                failures++
                AppLog.put("恢复背景图片失败：${resource.name}\n${error.localizedMessage}", error)
            }
        }
        if (manifest.resourceAliases.isNotEmpty()) {
            val files = listOf(
                File(path, "config.xml"),
                File(path, ReadBookConfig.configFileName),
                File(path, ReadBookConfig.shareConfigFileName),
                File(path, ThemeConfigStore.configFileName),
                File(path, "readSettings.json"),
                File(path, "highlightRule.json"),
                File(path, "highlightTagRule.json"),
                File(path, "tagGroupRule.json"),
            )
            files.filter(File::isFile).forEach { file ->
                var text = file.readText()
                manifest.resourceAliases.forEach { (alias, relative) ->
                    if (relative !in restoredResources) return@forEach
                    val target = resourceTargetDirectory(relative)?.resolve(relative.substringAfterLast('/'))
                        ?: return@forEach
                    // 新清单使用稳定占位符；调试期样本可能只记录了旧文件名。
                    // 旧文件名出现在绝对路径末尾时，必须替换整段路径，不能只替换文件名，
                    // 否则会得到“旧目录 + 新绝对路径”的无效引用。
                    val pathPattern = Regex(
                        "(?:(?:[A-Za-z]:)?[/\\\\])[^\"'<>\\s]*${Regex.escape(alias)}"
                    )
                    text = text.replace(pathPattern, target.absolutePath)
                        .replace(alias, target.absolutePath)
                }
                file.writeText(text)
            }

            // 同步更新设备上的 SharedPreferences 背景图片键，避免只选 resources 不选 settings 时配置不同步
            val backupConfigFile = File(path, "config.xml")
            if (backupConfigFile.isFile) {
                val backupConfigMap = try {
                    readXmlToMap(backupConfigFile)
                } catch (e: Exception) {
                    AppLog.put("读取备份配置失败，无法同步背景图片设置：${e.localizedMessage}", e)
                    emptyMap<String, Any?>()
                }
                val backgroundKeys = setOf(
                    PreferKey.bgImage,
                    PreferKey.bgImageN,
                    PreferKey.bgImageBlurring,
                    PreferKey.bgImageNBlurring,
                )
                val backgroundValues = backupConfigMap.filterKeys { it in backgroundKeys }
                if (backgroundValues.isNotEmpty()) {
                    AppConfigStore.putAllAndAwait(backgroundValues)
                    SettingsWriter.awaitPendingWrites()
                }
            }
        }
        return failures
    }

    private fun resourceTargetDirectory(relative: String): File? = when {
        relative.startsWith("assets/background/") -> appCtx.externalFiles.getFile("bg")
        relative.startsWith("assets/readMenuCustomIcons/") -> File(appCtx.filesDir, "read_menu_icons")
        relative.startsWith("assets/titleBarCustomIcons/") -> File(appCtx.filesDir, "title_bar_icons")
        relative.startsWith("assets/bookmarkBadge/") -> File(appCtx.filesDir, "bookmark_badge_resources")
        relative.startsWith("assets/highlightResources/") -> File(appCtx.filesDir, "highlight_resources")
        else -> null
    }

    /** 计算资源摘要，恢复前与 manifest 对比，避免损坏文件进入应用配置。 */
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

    /** 导入汇总记录时统一到本地分区，取已有与导入两者中的较大时长，保证重复导入幂等。 */
    private suspend fun restoreReadRecord(readRecord: ReadRecord) {
        val localRecord = readRecord.copy(
            deviceId = LOCAL_READ_RECORD_DEVICE_ID,
            bookName = ReadRecordIdentity.bookName(readRecord.bookName),
            bookAuthor = ReadRecordIdentity.author(readRecord.bookAuthor)
        )
        val existing = appDb.readRecordDao.getReadRecord(
            localRecord.deviceId,
            localRecord.bookName,
            localRecord.bookAuthor
        )
        appDb.readRecordDao.insert(
            existing?.copy(
                readTime = maxOf(existing.readTime, localRecord.readTime),
                lastRead = maxOf(existing.lastRead, localRecord.lastRead)
            ) ?: localRecord
        )
    }

    /** 导入每日详情时统一到本地分区，取已有与导入两者中的较大统计值，保证重复导入幂等。 */
    private suspend fun restoreReadRecordDetail(detail: ReadRecordDetail) {
        val localDetail = detail.copy(
            deviceId = LOCAL_READ_RECORD_DEVICE_ID,
            bookName = ReadRecordIdentity.bookName(detail.bookName),
            bookAuthor = ReadRecordIdentity.author(detail.bookAuthor)
        )
        val existing = appDb.readRecordDao.getDetail(
            localDetail.deviceId,
            localDetail.bookName,
            localDetail.bookAuthor,
            localDetail.date
        )
        appDb.readRecordDao.insertDetail(
            existing?.copy(
                readTime = maxOf(existing.readTime, localDetail.readTime),
                readWords = maxOf(existing.readWords, localDetail.readWords),
                firstReadTime = minPositive(existing.firstReadTime, localDetail.firstReadTime),
                lastReadTime = maxOf(existing.lastReadTime, localDetail.lastReadTime)
            ) ?: localDetail
        )
    }

    /** 导入会话时统一到本地分区，并按完整会话身份跳过已有副本。 */
    private suspend fun restoreReadRecordSession(session: ReadRecordSession) {
        val localSession = session.copy(
            deviceId = LOCAL_READ_RECORD_DEVICE_ID,
            bookName = ReadRecordIdentity.bookName(session.bookName),
            bookAuthor = ReadRecordIdentity.author(session.bookAuthor)
        )
        val existing = appDb.readRecordDao.getSession(
            localSession.deviceId,
            localSession.bookName,
            localSession.bookAuthor,
            localSession.bookUrl,
            localSession.startTime,
            localSession.endTime,
            localSession.words
        )
        if (existing == null) {
            appDb.readRecordDao.insertSession(localSession)
        }
    }

    /** 导入完成后，将能唯一匹配书架作者的旧空作者记录迁移到规范作者。 */
    private suspend fun reconcileReadRecordAliases() {
        val repository = get<ReadRecordRepository>()
        appDb.readRecordDao.all
            .filter { it.bookAuthor.isBlank() }
            .forEach { source ->
                val authors = appDb.bookDao.findByName(source.bookName)
                    .asSequence()
                    .map { it.author.trim() }
                    .filter { it.isNotBlank() }
                    .distinct()
                    .toList()
                val author = authors.singleOrNull() ?: return@forEach
                repository.mergeIndependentReadRecordsInto(
                    targetRecord = ReadRecord(
                        deviceId = source.deviceId,
                        bookName = source.bookName,
                        bookAuthor = ReadRecordIdentity.author(author),
                    ),
                    sourceRecords = listOf(source),
                )
            }
    }

    private fun minPositive(left: Long, right: Long): Long {
        return when {
            left <= 0L -> right
            right <= 0L -> left
            else -> minOf(left, right)
        }
    }

    private suspend fun applyConfigMap(
        map: Map<String, Any?>,
        aes: BackupAES,
        mode: RestoreMode,
        allowLegacyPlaintextFallback: Boolean,
        clearMissing: Boolean = false,
    ): Set<String> {
        val normalization = normalizeConfigMapWithReport(
            map = map,
            keyIsNotIgnore = { BackupConfig.keyIsNotIgnore(it) },
            decryptWebDavPassword = {
                runCatching { BackupSensitiveEnvelope.decrypt(aes, it) }.getOrNull().also { decrypted ->
                    if (decrypted == null) {
                        AppLog.put("恢复 WebDAV 密码解密失败，已跳过敏感配置")
                    }
                }
            },
            hasLocalWebDavPassword = !appCtx.getPrefString(PreferKey.webDavPassword)
                .isNullOrBlank(),
            allowLegacyPlaintextFallback = allowLegacyPlaintextFallback,
        )
        normalization.decryptionFailedKeys.forEach { key ->
            AppLog.put("恢复配置解密失败，已跳过：$key")
        }
        normalization.unsupportedKeys.forEach { key ->
            AppLog.put("恢复配置类型不支持，已跳过：$key")
        }
        val finalMap = normalization.values
        // 经快照层批量恢复：立即对读侧生效（onRestoreFinish 的读取不再依赖回灌时机），单次原子 edit 落盘
        val existingKeys = AppConfigStore.preferences.asMap().keys.map { it.name }.toSet()
        val valuesToApply = when {
            clearMissing -> {
                // 清空配置类别时，备份中不存在的可恢复键也必须移除，
                // 否则本机后来改成的值会在恢复后继续生效（例如书架布局）。
                val removedKeys = existingKeys
                    .asSequence()
                    // 备份端明确忽略的键不能因为清空恢复而被删除；
                    // 恢复端本身忽略的键同样保留在本机。
                    .filter {
                        it !in finalMap &&
                            BackupConfig.keyIsNotIgnore(it, isBackup = true) &&
                            BackupConfig.keyIsNotIgnore(it)
                    }
                    .associateWith { null }
                removedKeys + finalMap
            }
            mode == RestoreMode.USE_BACKUP -> finalMap
            else -> finalMap.filterKeys { it !in existingKeys }
        }
        // 使用带确认的批量写入，确保 Gateway/底栏读取到恢复后的完整快照。
        AppConfigStore.putAllAndAwait(valuesToApply)
        SettingsWriter.awaitPendingWrites()
        return normalization.decryptionFailedKeys
    }

    /** 高亮规则跨设备恢复时不依赖随机 UUID，避免同一规则重复导入。 */
    private fun highlightRuleIdentity(rule: HighlightRule): String = listOf(
        rule.name,
        rule.pattern,
        rule.targetScope,
        rule.configName.orEmpty(),
    ).joinToString("\u0000")

    private fun readXmlToMap(file: File): Map<String, Any?> {
        val result = BackupConfigCodec.decodeWithReport(file.readText())
        result.warnings.forEach { warning ->
            AppLog.put("恢复配置警告：$warning")
        }
        return result.values
    }

    /** 严格读取恢复类别；解析失败必须交给调用方形成类别级失败报告。 */
    private inline fun <reified T> readListStrict(
        path: String,
        fileName: String,
        onFailure: (Throwable) -> Unit,
    ): List<T>? = runCatching {
        val file = File(path, fileName)
        if (!file.isFile) return@runCatching null
        LogUtils.d(TAG, "严格读取恢复备份 $fileName 文件大小 ${file.length()}")
        FileInputStream(file).use { input -> GSON.fromJsonArray<T>(input).getOrThrow() }
    }.onFailure(onFailure).getOrNull()

    /** 严格读取敏感类别：密码、信封或 JSON 损坏都必须成为可见的类别失败。 */
    private inline fun <reified T> readSensitiveListStrict(file: File, aes: BackupAES): List<T> {
        val content = file.readText()
        val json = if (content.isJsonArray()) content else BackupSensitiveEnvelope.decrypt(aes, content)
        return GSON.fromJsonArray<T>(json).getOrThrow()
    }

}

internal fun classifyRestoreOutcome(
    failedCategories: Set<String>,
    attemptedCategories: Set<String>,
    configFileMissing: Boolean,
    configFileParseFailed: Boolean,
): BackupRestoreOutcome = when {
    failedCategories.isEmpty() && !configFileMissing && !configFileParseFailed -> BackupRestoreOutcome.SUCCESS
    failedCategories.isEmpty() -> BackupRestoreOutcome.SUCCESS_WITH_WARNINGS
    attemptedCategories.isNotEmpty() && failedCategories.containsAll(attemptedCategories) -> BackupRestoreOutcome.FAILED
    else -> BackupRestoreOutcome.PARTIAL
}
