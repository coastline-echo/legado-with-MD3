package io.legado.app.help.storage

import android.content.Context
import android.database.sqlite.SQLiteConstraintException
import android.net.Uri
import android.os.Environment
import androidx.room.withTransaction
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
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
import io.legado.app.domain.gateway.AppLocaleGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.domain.model.BackupRestoreStage
import io.legado.app.ui.book.read.ConfigUpdateAction
import io.legado.app.ui.book.read.ReadConfigUpdateBus
import io.legado.app.help.DirectLinkUpload
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
import io.legado.app.utils.getPrefString
import io.legado.app.utils.externalFiles
import io.legado.app.utils.getFile
import io.legado.app.utils.isContentScheme
import io.legado.app.utils.isJsonArray
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
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

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
        val entries = mutableListOf<String>()
        var manifest: BackupManifest? = null
        val manifestBytes = kotlin.runCatching {
            val input = if (uri.isContentScheme()) {
                DocumentFile.fromSingleUri(context, uri)?.openInputStream()
            } else {
                FileInputStream(File(uri.path!!))
            } ?: error("无法读取备份文件")
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
                        if (entry.name == "manifest.json") {
                            val bytes = ByteArrayOutputStream()
                            zip.copyTo(bytes)
                            manifest = runCatching {
                                GSON.fromJson(bytes.toString(Charsets.UTF_8), BackupManifest::class.java)
                            }.getOrNull()
                        }
                        entries += entry.name
                        zip.closeEntry()
                    }
                    entry = zip.nextEntry
                }
            }
            entries
        }.getOrElse {
            return@withContext RestorePreview(false, 0, 0, emptyMap(), listOf("读取备份失败：${it.localizedMessage}"))
        }
        val legacy = "manifest.json" !in manifestBytes
        val categoryCounts = manifestBytes.filter { !it.startsWith("assets/") }
            .groupingBy(::previewCategory)
            .eachCount()
            .toMutableMap()
        manifestBytes.count { it.startsWith("assets/") }.takeIf { it > 0 }?.let {
            categoryCounts["resources"] = it
        }
        RestorePreview(
            legacyFormat = legacy,
            fileCount = manifestBytes.count { !it.startsWith("assets/") },
            resourceCount = manifestBytes.count { it.startsWith("assets/") },
            categories = categoryCounts,
            warnings = if (legacy) listOf("旧版备份缺少 manifest.json") else emptyList(),
            sensitiveIncluded = manifest?.sensitiveIncluded == true,
            weakProtection = manifest?.weakProtection == true,
        )
    }

    /** WebDAV 已下载并解压到临时目录时使用的预览入口。 */
    internal fun previewUnzipped(path: String): RestorePreview {
        val root = File(path)
        val files = root.walkTopDown().filter(File::isFile).map { root.toPath().relativize(it.toPath()).toString().replace(File.separatorChar, '/') }.toList()
        val categories = files.filterNot { it.startsWith("assets/") }.groupingBy(::previewCategory).eachCount().toMutableMap()
        files.count { it.startsWith("assets/") }.takeIf { it > 0 }?.let { categories["resources"] = it }
        val legacy = !File(root, "manifest.json").isFile
        return RestorePreview(
            legacyFormat = legacy,
            fileCount = files.count { !it.startsWith("assets/") },
            resourceCount = files.count { it.startsWith("assets/") },
            categories = categories,
            warnings = if (legacy) listOf("旧版备份缺少 manifest.json") else BackupManifestScanner.scan(root).warnings,
            sensitiveIncluded = BackupManifestScanner.scan(root).manifest?.sensitiveIncluded == true,
            weakProtection = BackupManifestScanner.scan(root).manifest?.weakProtection == true,
        )
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
        else -> name.substringBeforeLast('.', name)
    }

    suspend fun restore(
        context: Context,
        uri: Uri,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        backupPassword: String? = null,
    ) {
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
            }
            if (unzipResult.isSuccess) {
                kotlin.runCatching {
                    restoreUnzipped(Backup.backupPath, mode, selectedCategories, backupPassword)
                    LocalConfig.lastBackup = System.currentTimeMillis()
                }.onFailure {
                    appCtx.toastOnUi("恢复备份出错\n${it.localizedMessage}")
                    AppLog.put("恢复备份出错\n${it.localizedMessage}", it)
                }
            }
        }
    }

    suspend fun restoreLocked(
        path: String,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        backupPassword: String? = null,
    ) {
        BackupRestoreLock.withLock {
            restoreUnzipped(path, mode, selectedCategories, backupPassword)
        }
    }

    internal suspend fun restoreUnzipped(
        path: String,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        backupPassword: String? = null,
    ) {
        val scan = BackupManifestScanner.scan(File(path))
        scan.warnings.forEach { warning ->
            AppLog.put("恢复备份校验警告：$warning")
        }
        BackupRestoreProgressBus.emit(
            BackupRestoreProgress(
                stage = BackupRestoreStage.READ_BACKUP,
                warnings = scan.warnings.size,
            )
        )
        restore(path, mode, selectedCategories, backupPassword, allowLegacyPlaintextFallback = scan.legacyFormat)
    }

    private suspend fun restore(
        path: String,
        mode: RestoreMode,
        selectedCategories: Set<String>,
        backupPassword: String? = null,
        allowLegacyPlaintextFallback: Boolean = true,
    ) {
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.READ_BACKUP))
        fun selected(category: String): Boolean =
            mode != RestoreMode.BY_CATEGORY || category in selectedCategories
        val aes = BackupAES(backupPassword)
        if (selected("resources")) restoreBackgroundResources(path)
        if (selected("bookshelf")) fileToListT<Book>(path, "bookshelf.json")?.let {
            it.forEach { book ->
                book.upType()
            }
            val restorePlan = planBookRestore(
                restoredBooks = it,
                existingBooks = appDb.bookDao.all,
                ignoreLocalBook = BackupConfig.ignoreLocalBook,
                locationStatus = ::localBookLocationStatus,
            )
            restorePlan.booksToUpsert
                .filter { book -> book.isLocal }
                .forEach { book -> book.coverUrl = LocalBook.getCoverPath(book) }
            appDb.runInTransaction {
                if (restorePlan.booksToDelete.isNotEmpty()) {
                    appDb.bookDao.delete(*restorePlan.booksToDelete.toTypedArray())
                }
                if (restorePlan.booksToUpdate.isNotEmpty()) {
                    appDb.bookDao.update(*restorePlan.booksToUpdate.toTypedArray())
                }
                if (restorePlan.booksToInsert.isNotEmpty()) {
                    appDb.bookDao.insert(*restorePlan.booksToInsert.toTypedArray())
                }
            }
        }
        // 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (selected("bookmark") && BackupConfig.dbIsNotIgnored("bookmark")) {
            fileToListT<Bookmark>(path, "bookmark.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.bookmarkDao.deleteAll()
                    appDb.bookmarkDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
            fileToListT<BookMarking>(path, "bookMarking.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.bookMarkingDao.deleteAll()
                    appDb.bookMarkingDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("bookGroup") && BackupConfig.dbIsNotIgnored("bookGroup")) {
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.bookGroupDao.replaceAll(it)
                else it.forEach { item -> runCatching { appDb.bookGroupDao.insert(item) } }
            }
        }
        if (selected("bookSource") && BackupConfig.dbIsNotIgnored("bookSource")) {
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.bookSourceDao.deleteAll()
                    appDb.bookSourceDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            } ?: run {
                val bookSourceFile = File(path, "bookSource.json")
                if (bookSourceFile.exists()) {
                    val json = bookSourceFile.readText()
                    ImportOldData.importOldSource(json)
                }
            }
        }
        if (selected("rssSource") && BackupConfig.dbIsNotIgnored("rssSource")) {
            fileToListT<RssSource>(path, "rssSources.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.rssSourceDao.deleteAll()
                    appDb.rssSourceDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("rssStar") && BackupConfig.dbIsNotIgnored("rssStar")) {
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.rssStarDao.deleteAll()
                    appDb.rssStarDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("replaceRule") && BackupConfig.dbIsNotIgnored("replaceRule")) {
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.replaceRuleDao.deleteAll()
                    appDb.replaceRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("searchHistory") && BackupConfig.dbIsNotIgnored("searchHistory")) {
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.searchKeywordDao.deleteAll()
                    appDb.searchKeywordDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("sourceSub") && BackupConfig.dbIsNotIgnored("sourceSub")) {
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.ruleSubDao.deleteAll()
                    appDb.ruleSubDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("txtTocRule") && BackupConfig.dbIsNotIgnored("txtTocRule")) {
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.txtTocRuleDao.deleteAll()
                    appDb.txtTocRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("httpTTS") && BackupConfig.dbIsNotIgnored("httpTTS")) {
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.httpTTSDao.deleteAll()
                    appDb.httpTTSDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("dictRule") && BackupConfig.dbIsNotIgnored("dictRule")) {
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.dictRuleDao.deleteAll()
                    appDb.dictRuleDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("keyboardAssists") && BackupConfig.dbIsNotIgnored("keyboardAssists")) {
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                try {
                    if (mode == RestoreMode.USE_BACKUP) appDb.keyboardAssistsDao.deleteAll()
                    appDb.keyboardAssistsDao.insert(*it.toTypedArray())
                } catch (_: SQLiteConstraintException) {
                }
            }
        }
        if (selected("homepageModules") && BackupConfig.dbIsNotIgnored("homepageModules")) {
            fileToListT<HomepageModule>(path, "homepageModules.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.homepageModuleDao.replaceAll(it)
                else runCatching { appDb.homepageModuleDao.upsertAll(it) }
            }
        }
        if (selected("homepageCustomSets") && BackupConfig.dbIsNotIgnored("homepageCustomSets")) {
            fileToListT<HomepageCustomSet>(path, "homepageCustomSets.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.homepageCustomSetDao.replaceAll(it)
                else runCatching { appDb.homepageCustomSetDao.insertAll(it) }
            }
        }
        if (selected("highlightRule") && BackupConfig.dbIsNotIgnored("highlightRule")) {
            fileToListT<HighlightRule>(path, "highlightRule.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.highlightRuleDao.replaceAll(it)
                else runCatching { appDb.highlightRuleDao.insertAll(it) }
            }
        }
        if (selected("highlightTagRule") && BackupConfig.dbIsNotIgnored("highlightTagRule")) {
            fileToListT<HighlightTagRule>(path, "highlightTagRule.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.highlightTagRuleDao.replaceAll(it)
                else runCatching { appDb.highlightTagRuleDao.insert(*it.toTypedArray()) }
            }
        }
        if (selected("tagGroupRule") && BackupConfig.dbIsNotIgnored("tagGroupRule")) {
            fileToListT<TagGroupRule>(path, "tagGroupRule.json")?.let {
                if (mode == RestoreMode.USE_BACKUP) appDb.tagGroupRuleDao.replaceAll(it)
                else runCatching { appDb.tagGroupRuleDao.insert(*it.toTypedArray()) }
            }
        }
        if (selected("readRecord") && BackupConfig.dbIsNotIgnored("readRecord")) {
            appDb.withTransaction {
                if (mode == RestoreMode.USE_BACKUP) {
                    appDb.readRecordDao.clear()
                    appDb.readRecordDao.clearDetails()
                    appDb.readRecordDao.clearSessions()
                }
                fileToListT<ReadRecord>(path, "readRecord.json")?.forEach { readRecord ->
                    try { restoreReadRecord(readRecord) } catch (_: SQLiteConstraintException) { }
                }
                fileToListT<ReadRecordDetail>(path, "readRecordDetail.json")?.forEach { detail ->
                    try { restoreReadRecordDetail(detail) } catch (_: SQLiteConstraintException) { }
                }
                fileToListT<ReadRecordSession>(path, "readRecordSession.json")?.forEach { session ->
                    try { restoreReadRecordSession(session) } catch (_: SQLiteConstraintException) { }
                }
            }
            reconcileReadRecordAliases()
            // 会话导入按身份去重（幂等），汇总/明细取较大值后按会话重算，
            // 避免同一备份重复导入导致阅读时长翻倍。
            get<ReadRecordRepository>().reconcileRestoredReadRecordTotals()
        }
        if (selected("server") && BackupConfig.dbIsNotIgnored("server")) {
            File(path, "servers.json").takeIf {
                it.exists()
            }?.runCatching {
                var json = readText()
                if (!json.isJsonArray()) {
                    json = aes.decryptStr(json)
                }
                    GSON.fromJsonArray<Server>(json).getOrNull()?.let {
                    try {
                        if (mode == RestoreMode.USE_BACKUP) appDb.serverDao.deleteAll()
                        appDb.serverDao.insert(*it.toTypedArray())
                    } catch (_: SQLiteConstraintException) {
                    }
                }
            }?.onFailure {
                AppLog.put("恢复服务器配置出错\n${it.localizedMessage}", it)
            }
        }
        File(path, DirectLinkUpload.ruleFileName).takeIf {
            selected("directLinkUploadRule") &&
            it.exists()
        }?.runCatching {
            val json = readText()
            val cache = ACache.get(cacheDir = false)
            if (mode == RestoreMode.USE_BACKUP || cache.getAsString(DirectLinkUpload.ruleFileName).isNullOrBlank()) {
                cache.put(DirectLinkUpload.ruleFileName, json)
            }
        }?.onFailure {
            AppLog.put("恢复直链上传出错\n${it.localizedMessage}", it)
        }
        //恢复主题配置
        if (selected("themeConfig") && !BackupConfig.ignoreThemeConfig) {
            File(path, ThemeConfigStore.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                val target = File(ThemeConfigStore.configFilePath)
                if (mode == RestoreMode.USE_BACKUP || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ThemeConfigStore.configFilePath)
                    ThemeConfigStore.upConfig()
                }
            }?.onFailure {
                AppLog.put("恢复主题出错\n${it.localizedMessage}", it)
            }
        }
        File(path, BookCover.configFileName).takeIf {
            selected("coverConfig") &&
            it.exists() && !BackupConfig.ignoreCoverConfig
        }?.runCatching {
            val json = readText()
            if (mode == RestoreMode.USE_BACKUP || BookCover.getConfig() == null) {
                BookCover.saveCoverRule(json)
            }
        }?.onFailure {
            AppLog.put("恢复封面规则出错\n${it.localizedMessage}", it)
        }
        if (selected("readConfig") && !BackupConfig.ignoreReadConfig) {
            //恢复阅读界面配置
            File(path, ReadBookConfig.configFileName).takeIf {
                it.exists()
            }?.runCatching {
                val target = File(ReadBookConfig.configFilePath)
                if (mode == RestoreMode.USE_BACKUP || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ReadBookConfig.configFilePath)
                }
            }?.onFailure {
                AppLog.put("恢复阅读界面出错\n${it.localizedMessage}", it)
            }
            File(path, ReadBookConfig.shareConfigFileName).takeIf {
                it.exists()
            }?.runCatching {
                val target = File(ReadBookConfig.shareConfigFilePath)
                if (mode == RestoreMode.USE_BACKUP || !target.isFile) {
                    FileUtils.copyFileAtomic(this, ReadBookConfig.shareConfigFilePath)
                }
            }?.onFailure {
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
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.RESTORE_CONFIG))
        val configFile = File(path, "config.xml")
        if (selected("settings") && configFile.exists()) {
            try {
                val map = readXmlToMap(configFile)
                if (map.isNotEmpty()) {
                    applyConfigMap(map, aes, mode, allowLegacyPlaintextFallback)
                }
            } catch (e: Exception) {
                AppLog.put("恢复配置 XML 出错\n${e.localizedMessage}", e)
            }
        }
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.RESTORE_BOOKSHELF))

        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.RESTORE_RESOURCES))

        appCtx.toastOnUi(R.string.restore_success)
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.REFRESH_RUNTIME))
        withContext(Main) {
            delay(100)
            get<AppLocaleGateway>().apply { setLanguage(currentLanguage) }
            if (!BuildConfig.DEBUG) {
                LauncherIconHelp.changeIcon(appCtx.getPrefString(PreferKey.launcherIcon))
            }
            ThemeConfigStore.applyDayNight(appCtx)
        }
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.COMPLETE))
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

    /** 恢复 ZIP 内按 hash 命名的背景资源；单张图片失败不影响其他类别。 */
    private fun restoreBackgroundResources(path: String) {
        val manifest = BackupManifestScanner.scan(File(path)).manifest ?: return
        val targetDir = appCtx.externalFiles.getFile("bg").apply { mkdirs() }
        manifest.resources.forEach { resource ->
            if (!resource.name.startsWith("assets/background/")) return@forEach
            runCatching {
                val source = File(path, resource.name)
                if (!source.isFile) error("资源不存在")
                if (source.length() != resource.size) error("资源大小不匹配")
                val target = File(targetDir, source.name)
                source.copyTo(target, overwrite = true)
            }.onFailure { error ->
                AppLog.put("恢复背景图片失败：${resource.name}\n${error.localizedMessage}", error)
            }
        }
        if (manifest.resourceAliases.isNotEmpty()) {
            val files = listOf(
                File(path, "config.xml"),
                File(path, ReadBookConfig.configFileName),
                File(path, ReadBookConfig.shareConfigFileName),
                File(path, ThemeConfigStore.configFileName),
            )
            files.filter(File::isFile).forEach { file ->
                var text = file.readText()
                manifest.resourceAliases.forEach { (alias, relative) ->
                    val localName = relative.substringAfterLast('/')
                    text = text.replace(alias, targetDir.resolve(localName).absolutePath)
                }
                file.writeText(text)
            }
        }
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
    ) {
        val finalMap = normalizeConfigMap(
            map = map,
            keyIsNotIgnore = { BackupConfig.keyIsNotIgnore(it) },
            decryptWebDavPassword = { runCatching { aes.decryptStr(it) }.getOrNull() },
            hasLocalWebDavPassword = !appCtx.getPrefString(PreferKey.webDavPassword)
                .isNullOrBlank(),
            allowLegacyPlaintextFallback = allowLegacyPlaintextFallback,
        )
        // 经快照层批量恢复：立即对读侧生效（onRestoreFinish 的读取不再依赖回灌时机），单次原子 edit 落盘
        val valuesToApply = if (mode == RestoreMode.USE_BACKUP) {
            finalMap
        } else {
            val existingKeys = AppConfigStore.preferences.asMap().keys.map { it.name }.toSet()
            finalMap.filterKeys { it !in existingKeys }
        }
        AppConfigStore.putAll(valuesToApply)
        // 恢复完成提示前等待落盘，dataStore.edit 返回即持久化完成
        SettingsWriter.awaitPendingWrites()
    }

    private fun readXmlToMap(file: File): Map<String, Any?> {
        val map = mutableMapOf<String, Any?>()
        try {
            val factory = XmlPullParserFactory.newInstance()
            val parser = factory.newPullParser()
            FileInputStream(file).use { fis ->
                parser.setInput(fis, "UTF-8")
                var eventType = parser.eventType
                while (eventType != XmlPullParser.END_DOCUMENT) {
                    if (eventType == XmlPullParser.START_TAG) {
                        val tagName = parser.name
                        val name = parser.getAttributeValue(null, "name")
                        if (name != null) {
                            when (tagName) {
                                "string" -> map[name] = parser.nextText()
                                "set" -> {
                                    val values = linkedSetOf<String>()
                                    var nested = parser.next()
                                    while (nested != XmlPullParser.END_DOCUMENT) {
                                        if (nested == XmlPullParser.START_TAG && parser.name == "string") {
                                            values += parser.nextText()
                                        } else if (nested == XmlPullParser.END_TAG && parser.name == "set") {
                                            break
                                        }
                                        nested = parser.next()
                                    }
                                    map[name] = values
                                }
                                "int" -> map[name] = parser.getAttributeValue(null, "value").toInt()
                                "long" -> map[name] = parser.getAttributeValue(null, "value").toLong()
                                "float" -> map[name] = parser.getAttributeValue(null, "value").toFloat()
                                "boolean" -> map[name] = parser.getAttributeValue(null, "value").toBoolean()
                            }
                        }
                    }
                    eventType = parser.next()
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return map
    }

    private inline fun <reified T> fileToListT(path: String, fileName: String): List<T>? {
        try {
            val file = File(path, fileName)
            if (file.exists()) {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件大小 ${file.length()}")
                FileInputStream(file).use {
                    return GSON.fromJsonArray<T>(it).getOrThrow().also { list ->
                        LogUtils.d(TAG, "阅读恢复备份 $fileName 列表大小 ${list.size}")
                    }
                }
            } else {
                LogUtils.d(TAG, "阅读恢复备份 $fileName 文件不存在")
            }
        } catch (e: Exception) {
            AppLog.put("$fileName\n读取解析出错\n${e.localizedMessage}", e)
            appCtx.toastOnUi("$fileName\n读取文件出错\n${e.localizedMessage}")
        }
        return null
    }

}
