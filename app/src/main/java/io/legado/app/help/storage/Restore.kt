package io.legado.app.help.storage

import android.content.Context
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
import io.legado.app.domain.gateway.ReadSettingsGateway
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
        fun openInput() = if (uri.isContentScheme()) {
            DocumentFile.fromSingleUri(context, uri)?.openInputStream()
        } else {
            FileInputStream(File(uri.path!!))
        } ?: error("无法读取备份文件")
        val scan = runCatching { openInput().use { BackupManifestScanner.scanZip(it) } }
            .getOrElse { BackupScanResult(false, warnings = listOf("读取备份失败：${it.localizedMessage}")) }
        val entries = mutableListOf<String>()
        val manifestBytes = kotlin.runCatching {
            val input = openInput()
            ZipInputStream(input).use { zip ->
                var entry = zip.nextEntry
                while (entry != null) {
                    if (!entry.isDirectory) {
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
        val previewDir = File(context.cacheDir, "restore_preview_${System.nanoTime()}")
        runCatching {
            previewDir.mkdirs()
            openInput().use { input -> ZipUtils.unZipToPath(input, previewDir.absolutePath) }
            val detailed = previewUnzipped(previewDir.absolutePath)
            return@withContext detailed.copy(
                warnings = ((if (legacy) listOf("旧版备份缺少 manifest.json") else emptyList()) +
                    scan.warnings + detailed.warnings).distinct(),
            )
        }.onFailure {
            // 临时解压失败时继续返回 ZIP 结构摘要，不阻断用户查看基本信息。
            AppLog.put("恢复预览数据扫描失败：${it.localizedMessage}", it)
        }.also {
            previewDir.deleteRecursively()
        }
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
            warnings = (if (legacy) listOf("旧版备份缺少 manifest.json") else emptyList()) + scan.warnings,
            sensitiveIncluded = scan.manifest?.sensitiveIncluded == true,
            weakProtection = scan.manifest?.weakProtection == true,
            sensitiveItems = scan.manifest?.sensitiveItems.orEmpty(),
            applicationCounts = applicationCategoryCounts(),
            emptyCategories = categoryCounts.filterValues { it == 0 }.keys,
            missingCategories = previewCategories().minus(categoryCounts.keys),
            ignoredCategories = mergedIgnoredCategories(scan.manifest?.ignoredCategories.orEmpty()),
            backupIgnoredCategories = scan.manifest?.ignoredCategories.orEmpty(),
            restoreIgnoredCategories = currentRestoreIgnoredCategories() +
                scan.manifest?.restoreIgnoredCategories.orEmpty(),
            conflictCounts = emptyMap(),
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
            sensitiveItems = BackupManifestScanner.scan(root).manifest?.sensitiveItems.orEmpty(),
            applicationCounts = applicationCategoryCounts(),
            emptyCategories = explicitEmptyCategories(root, categories.keys),
            missingCategories = previewCategories().minus(categories.keys),
            ignoredCategories = mergedIgnoredCategories(
                BackupManifestScanner.scan(root).manifest?.ignoredCategories.orEmpty(),
            ),
            backupIgnoredCategories = BackupManifestScanner.scan(root).manifest?.ignoredCategories.orEmpty(),
            restoreIgnoredCategories = currentRestoreIgnoredCategories() +
                BackupManifestScanner.scan(root).manifest?.restoreIgnoredCategories.orEmpty(),
            conflictCounts = previewConflictCounts(root),
        )
    }

    /** 预览阶段按稳定主键统计冲突，不执行任何写入。 */
    private fun previewConflictCounts(root: File): Map<String, Int> = buildMap {
        File(root, "bookshelf.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<Book>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookshelf", values.count { it.bookUrl in appDb.bookDao.allBookUrls })
        }
        File(root, "bookSource.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<BookSource>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookSource", values.count { it.bookSourceUrl in appDb.bookSourceDao.all.map { source -> source.bookSourceUrl } })
        }
        File(root, "servers.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<Server>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("server", values.count { item -> appDb.serverDao.all.any { it.id == item.id } })
        }
        File(root, "rssSources.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<RssSource>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("rssSource", values.count { item -> appDb.rssSourceDao.all.any { it.sourceUrl == item.sourceUrl } })
        }
        File(root, "replaceRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<ReplaceRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("replaceRule", values.count { item -> appDb.replaceRuleDao.all.any { it.id == item.id } })
        }
        File(root, "bookmark.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<Bookmark>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("bookmark", values.count { item -> appDb.bookmarkDao.all.any { it.bookName == item.bookName && it.bookAuthor == item.bookAuthor } })
        }
        File(root, "homepageModules.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HomepageModule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("homepageModules", values.count { item -> appDb.homepageModuleDao.getAll().any { it.id == item.id } })
        }
        File(root, "homepageCustomSets.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HomepageCustomSet>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("homepageCustomSets", values.count { item -> appDb.homepageCustomSetDao.getAll().any { it.id == item.id } })
        }
        File(root, "highlightRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HighlightRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("highlightRule", values.count { item -> appDb.highlightRuleDao.getAll().any { it.id == item.id } })
        }
        File(root, "highlightTagRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<HighlightTagRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("highlightTagRule", values.count { item -> appDb.highlightTagRuleDao.getAll().any { it.id == item.id } })
        }
        File(root, "tagGroupRule.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<TagGroupRule>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("tagGroupRule", values.count { item -> appDb.tagGroupRuleDao.getAll().any { it.id == item.id } })
        }
        File(root, "readRecord.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<ReadRecord>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("readRecord", values.count { item ->
                appDb.readRecordDao.all.any { existing ->
                    existing.bookName == item.bookName && existing.bookAuthor == item.bookAuthor
                }
            })
        }
        File(root, "rssStar.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<RssStar>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("rssStar", values.count { item ->
                appDb.rssStarDao.all.any { existing -> existing.origin == item.origin && existing.link == item.link }
            })
        }
        File(root, "keyboardAssists.json").takeIf(File::isFile)?.let { file ->
            val values = runCatching { GSON.fromJsonArray<KeyboardAssist>(file.readText()).getOrNull().orEmpty() }.getOrNull()
            if (values != null) put("keyboardAssists", values.count { item ->
                appDb.keyboardAssistsDao.all.any { existing -> existing.type == item.type && existing.key == item.key }
            })
        }
    }

    private fun explicitEmptyCategories(root: File, categories: Set<String>): Set<String> =
        categories.filterTo(linkedSetOf()) { category ->
            val files = root.walkTopDown().filter(File::isFile)
                .filter { previewCategory(it.name) == category }
            files.any { it.readText().trim() == "[]" }
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
    )

    private fun previewCategories(): Set<String> = setOf(
        "bookshelf", "bookmark", "bookGroup", "bookSource", "rssSource", "rssStar",
        "replaceRule", "searchHistory", "readRecord", "server", "settings", "readConfig",
        "themeConfig", "coverConfig", "resources",
    )

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
        else -> name.substringBeforeLast('.', name)
    }

    suspend fun restore(
        context: Context,
        uri: Uri,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
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
                    restoreUnzipped(Backup.backupPath, mode, selectedCategories, categoryModes, backupPassword)
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
        categoryModes: Map<String, RestoreMode> = emptyMap(),
        backupPassword: String? = null,
    ) {
        BackupRestoreLock.withLock {
            restoreUnzipped(path, mode, selectedCategories, categoryModes, backupPassword)
        }
    }

    internal suspend fun restoreUnzipped(
        path: String,
        mode: RestoreMode = RestoreMode.KEEP_APPLICATION,
        selectedCategories: Set<String> = emptySet(),
        categoryModes: Map<String, RestoreMode> = emptyMap(),
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
        restore(path, mode, selectedCategories, categoryModes, backupPassword, allowLegacyPlaintextFallback = scan.legacyFormat)
    }

    private suspend fun restore(
        path: String,
        mode: RestoreMode,
        selectedCategories: Set<String>,
        categoryModes: Map<String, RestoreMode>,
        backupPassword: String? = null,
        allowLegacyPlaintextFallback: Boolean = true,
    ) {
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.READ_BACKUP))
        // 统一由 planner 决定类别是否存在、是否被忽略以及覆盖/保留模式；
        // 各 DAO 仍负责实体主键的具体合并策略。
        val categoryPlans = RestoreCategoryPlanner.planByCategory(
            inputs = restorePlannerCategories(path).map { (category, present, ignored) ->
                RestoreCategoryInput(category = category, backupPresent = present, ignored = ignored)
            },
            modes = categoryModes,
            defaultMode = mode.takeUnless { it == RestoreMode.BY_CATEGORY } ?: RestoreMode.KEEP_APPLICATION,
        ).associateBy { it.category }
        val explicitlyEmptyCategories = File(path).walkTopDown()
            .filter { it.isFile && it.extension.equals("json", ignoreCase = true) }
            .mapNotNull { file ->
                runCatching { (file.readText().trim() == "[]") to previewCategory(file.name) }.getOrNull()
            }
            .filter { it.first }
            .mapTo(hashSetOf()) { it.second }
        fun selected(category: String): Boolean {
            val plan = categoryPlans[category]
            // 全局模式保留旧行为（例如缺少 config.xml 仍生成明确失败报告）；
            // 只有 BY_CATEGORY 才由 planner 过滤缺失/忽略类别。
            val enabled = if (mode == RestoreMode.BY_CATEGORY) {
                category in selectedCategories && plan?.mode != null && !plan.skippedByIgnore
            } else {
                true
            }
            if (enabled) {
                BackupRestoreProgressBus.emit(
                    BackupRestoreProgress(
                        stage = BackupRestoreStage.RESTORE_BOOKSHELF,
                        category = category,
                    )
                )
            }
            return enabled
        }
        fun useBackup(category: String): Boolean =
            if (category in explicitlyEmptyCategories) {
                false
            } else if (mode == RestoreMode.BY_CATEGORY) {
                categoryPlans[category]?.mode == RestoreMode.USE_BACKUP
            } else {
                mode == RestoreMode.USE_BACKUP
            }
        val aes = BackupAES(backupPassword)
        var categoryFailures = 0
        val failedCategories = linkedSetOf<String>()
        val failedCategoryCounts = linkedMapOf<String, Int>()
        var configFileMissing = false
        var configFileParseFailed = false
        fun reportCategoryFailure(category: String, error: Throwable) {
            categoryFailures++
            failedCategories += category
            failedCategoryCounts[category] = (failedCategoryCounts[category] ?: 0) + 1
            AppLog.put("恢复类别失败：$category\n${error.localizedMessage}", error)
            BackupRestoreProgressBus.emit(
                BackupRestoreProgress(
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
                        stage = BackupRestoreStage.RESTORE_RESOURCES,
                        failures = categoryFailures,
                        failedCategoryCounts = failedCategoryCounts.toMap(),
                    )
                )
            }
        }
        if (selected("bookshelf") && BackupConfig.dbIsNotIgnored("bookshelf")) fileToListT<Book>(path, "bookshelf.json")?.let {
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
            val strictReplacementDeletes = if (useBackup("bookshelf")) {
                val retainedUrls = restorePlan.booksToUpsert.mapTo(hashSetOf()) { it.bookUrl }
                existingBooks.filter { book ->
                    book.bookUrl !in retainedUrls && !(BackupConfig.ignoreLocalBook && book.isLocal)
                }
            } else emptyList()
            appDb.runInTransaction {
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
        // 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (selected("bookmark") && BackupConfig.dbIsNotIgnored("bookmark")) {
            try {
                appDb.withTransaction {
                    fileToListT<Bookmark>(path, "bookmark.json")?.let {
                        if (useBackup("bookmark")) appDb.bookmarkDao.deleteAll()
                        appDb.bookmarkDao.insert(*it.toTypedArray())
                    }
                    fileToListT<BookMarking>(path, "bookMarking.json")?.let {
                        if (useBackup("bookmark")) appDb.bookMarkingDao.deleteAll()
                        appDb.bookMarkingDao.insert(*it.toTypedArray())
                    }
                }
            } catch (error: Exception) {
                reportCategoryFailure("bookmark", error)
            }
        }
        if (selected("bookGroup") && BackupConfig.dbIsNotIgnored("bookGroup")) {
            fileToListT<BookGroup>(path, "bookGroup.json")?.let {
                if (useBackup("bookGroup")) appDb.bookGroupDao.replaceAll(it)
                else runCatching {
                    appDb.runInTransaction { it.forEach { item -> appDb.bookGroupDao.insert(item) } }
                }.onFailure { reportCategoryFailure("bookGroup", it) }
            }
        }
        if (selected("bookSource") && BackupConfig.dbIsNotIgnored("bookSource")) {
            fileToListT<BookSource>(path, "bookSource.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("bookSource")) appDb.bookSourceDao.deleteAll()
                        val incoming = if (useBackup("bookSource")) it else {
                            val existing = appDb.bookSourceDao.all.mapTo(hashSetOf()) { source -> source.bookSourceUrl }
                            it.filterNot { source -> source.bookSourceUrl in existing }
                        }
                        appDb.bookSourceDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("bookSource", error)
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
                    appDb.runInTransaction {
                        if (useBackup("rssSource")) appDb.rssSourceDao.deleteAll()
                        val incoming = if (useBackup("rssSource")) it else {
                            val existing = appDb.rssSourceDao.all.mapTo(hashSetOf()) { source -> source.sourceUrl }
                            it.filterNot { source -> source.sourceUrl in existing }
                        }
                        appDb.rssSourceDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("rssSource", error)
                }
            }
        }
        if (selected("rssStar") && BackupConfig.dbIsNotIgnored("rssStar")) {
            fileToListT<RssStar>(path, "rssStar.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("rssStar")) appDb.rssStarDao.deleteAll()
                        val incoming = if (useBackup("rssStar")) it else {
                            val existing = appDb.rssStarDao.all.mapTo(hashSetOf()) { star -> star.origin to star.link }
                            it.filterNot { star -> (star.origin to star.link) in existing }
                        }
                        appDb.rssStarDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("rssStar", error)
                }
            }
        }
        if (selected("replaceRule") && BackupConfig.dbIsNotIgnored("replaceRule")) {
            fileToListT<ReplaceRule>(path, "replaceRule.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("replaceRule")) appDb.replaceRuleDao.deleteAll()
                        val incoming = if (useBackup("replaceRule")) it else {
                            val existing = appDb.replaceRuleDao.all.mapTo(hashSetOf()) { rule -> rule.id }
                            it.filterNot { rule -> rule.id in existing }
                        }
                        appDb.replaceRuleDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("replaceRule", error)
                }
            }
        }
        if (selected("searchHistory") && BackupConfig.dbIsNotIgnored("searchHistory")) {
            fileToListT<SearchKeyword>(path, "searchHistory.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("searchHistory")) appDb.searchKeywordDao.deleteAll()
                        val incoming = if (useBackup("searchHistory")) it else {
                            val existing = appDb.searchKeywordDao.all.mapTo(hashSetOf()) { keyword -> keyword.word }
                            it.filterNot { keyword -> keyword.word in existing }
                        }
                        appDb.searchKeywordDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("searchHistory", error)
                }
            }
        }
        if (selected("sourceSub") && BackupConfig.dbIsNotIgnored("sourceSub")) {
            fileToListT<RuleSub>(path, "sourceSub.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("sourceSub")) appDb.ruleSubDao.deleteAll()
                        val incoming = if (useBackup("sourceSub")) it else {
                            val existing = appDb.ruleSubDao.all.mapTo(hashSetOf()) { item -> item.id }
                            it.filterNot { item -> item.id in existing }
                        }
                        appDb.ruleSubDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("sourceSub", error)
                }
            }
        }
        if (selected("txtTocRule") && BackupConfig.dbIsNotIgnored("txtTocRule")) {
            fileToListT<TxtTocRule>(path, "txtTocRule.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("txtTocRule")) appDb.txtTocRuleDao.deleteAll()
                        val incoming = if (useBackup("txtTocRule")) it else {
                            val existing = appDb.txtTocRuleDao.all.mapTo(hashSetOf()) { item -> item.id }
                            it.filterNot { item -> item.id in existing }
                        }
                        appDb.txtTocRuleDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("txtTocRule", error)
                }
            }
        }
        if (selected("httpTTS") && BackupConfig.dbIsNotIgnored("httpTTS")) {
            fileToListT<HttpTTS>(path, "httpTTS.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("httpTTS")) appDb.httpTTSDao.deleteAll()
                        val incoming = if (useBackup("httpTTS")) it else {
                            val existing = appDb.httpTTSDao.all.mapTo(hashSetOf()) { item -> item.id }
                            it.filterNot { item -> item.id in existing }
                        }
                        appDb.httpTTSDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("httpTTS", error)
                }
            }
        }
        if (selected("dictRule") && BackupConfig.dbIsNotIgnored("dictRule")) {
            fileToListT<DictRule>(path, "dictRule.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("dictRule")) appDb.dictRuleDao.deleteAll()
                        val incoming = if (useBackup("dictRule")) it else {
                            val existing = appDb.dictRuleDao.all.mapTo(hashSetOf()) { item -> item.name }
                            it.filterNot { item -> item.name in existing }
                        }
                        appDb.dictRuleDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("dictRule", error)
                }
            }
        }
        if (selected("keyboardAssists") && BackupConfig.dbIsNotIgnored("keyboardAssists")) {
            fileToListT<KeyboardAssist>(path, "keyboardAssists.json")?.let {
                try {
                    appDb.runInTransaction {
                        if (useBackup("keyboardAssists")) appDb.keyboardAssistsDao.deleteAll()
                        val incoming = if (useBackup("keyboardAssists")) it else {
                            val existing = appDb.keyboardAssistsDao.all.mapTo(hashSetOf()) { item -> item.type to item.key }
                            it.filterNot { item -> (item.type to item.key) in existing }
                        }
                        appDb.keyboardAssistsDao.insert(*incoming.toTypedArray())
                    }
                } catch (error: Exception) {
                    reportCategoryFailure("keyboardAssists", error)
                }
            }
        }
        if (selected("homepageModules") && BackupConfig.dbIsNotIgnored("homepageModules")) {
            fileToListT<HomepageModule>(path, "homepageModules.json")?.let {
                if (useBackup("homepageModules")) appDb.homepageModuleDao.replaceAll(it)
                else {
                    val existing = appDb.homepageModuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                    try {
                        appDb.homepageModuleDao.upsertAll(it.filterNot { item -> item.id in existing })
                    } catch (error: Exception) {
                        reportCategoryFailure("homepageModules", error)
                    }
                }
            }
        }
        if (selected("homepageCustomSets") && BackupConfig.dbIsNotIgnored("homepageCustomSets")) {
            fileToListT<HomepageCustomSet>(path, "homepageCustomSets.json")?.let {
                if (useBackup("homepageCustomSets")) appDb.homepageCustomSetDao.replaceAll(it)
                else {
                    val existing = appDb.homepageCustomSetDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                    runCatching { appDb.homepageCustomSetDao.insertAll(it.filterNot { item -> item.id in existing }) }
                        .onFailure { reportCategoryFailure("homepageCustomSets", it) }
                }
            }
        }
        if (selected("highlightRule") && BackupConfig.dbIsNotIgnored("highlightRule")) {
            fileToListT<HighlightRule>(path, "highlightRule.json")?.let {
                if (useBackup("highlightRule")) appDb.highlightRuleDao.replaceAll(it)
                else {
                    val existing = appDb.highlightRuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                    runCatching { appDb.highlightRuleDao.insertAll(it.filterNot { item -> item.id in existing }) }
                        .onFailure { reportCategoryFailure("highlightRule", it) }
                }
            }
        }
        if (selected("highlightTagRule") && BackupConfig.dbIsNotIgnored("highlightTagRule")) {
            fileToListT<HighlightTagRule>(path, "highlightTagRule.json")?.let {
                if (useBackup("highlightTagRule")) appDb.highlightTagRuleDao.replaceAll(it)
                else {
                    val existing = appDb.highlightTagRuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                    try {
                        appDb.highlightTagRuleDao.insert(*it.filterNot { item -> item.id in existing }.toTypedArray())
                    } catch (error: Exception) {
                        reportCategoryFailure("highlightTagRule", error)
                    }
                }
            }
        }
        if (selected("tagGroupRule") && BackupConfig.dbIsNotIgnored("tagGroupRule")) {
            fileToListT<TagGroupRule>(path, "tagGroupRule.json")?.let {
                if (useBackup("tagGroupRule")) appDb.tagGroupRuleDao.replaceAll(it)
                else {
                    val existing = appDb.tagGroupRuleDao.getAll().mapTo(hashSetOf()) { item -> item.id }
                    try {
                        appDb.tagGroupRuleDao.insert(*it.filterNot { item -> item.id in existing }.toTypedArray())
                    } catch (error: Exception) {
                        reportCategoryFailure("tagGroupRule", error)
                    }
                }
            }
        }
        if (selected("readRecord") && BackupConfig.dbIsNotIgnored("readRecord")) {
            appDb.withTransaction {
                if (useBackup("readRecord")) {
                    appDb.readRecordDao.clear()
                    appDb.readRecordDao.clearDetails()
                    appDb.readRecordDao.clearSessions()
                }
                fileToListT<ReadRecord>(path, "readRecord.json")?.forEach { readRecord ->
                    try { restoreReadRecord(readRecord) } catch (error: Exception) { reportCategoryFailure("readRecord", error) }
                }
                fileToListT<ReadRecordDetail>(path, "readRecordDetail.json")?.forEach { detail ->
                    try { restoreReadRecordDetail(detail) } catch (error: Exception) { reportCategoryFailure("readRecord", error) }
                }
                fileToListT<ReadRecordSession>(path, "readRecordSession.json")?.forEach { session ->
                    try { restoreReadRecordSession(session) } catch (error: Exception) { reportCategoryFailure("readRecord", error) }
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
                        appDb.runInTransaction {
                            if (useBackup("server")) appDb.serverDao.deleteAll()
                            val incoming = if (useBackup("server")) it else {
                                val existing = appDb.serverDao.all.mapTo(hashSetOf()) { server -> server.id }
                                it.filterNot { server -> server.id in existing }
                            }
                            appDb.serverDao.insert(*incoming.toTypedArray())
                        }
                    } catch (error: Exception) {
                        reportCategoryFailure("server", error)
                    }
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
            val json = readText()
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
        BackupRestoreProgressBus.emit(BackupRestoreProgress(BackupRestoreStage.RESTORE_CONFIG))
        val configFile = File(path, "config.xml")
        if (selected("settings") && !configFile.exists()) {
            configFileMissing = true
            reportCategoryFailure("settings", NoStackTraceException("备份缺少 config.xml"))
        } else if (selected("settings")) {
            try {
                val map = readXmlToMap(configFile)
                if (map.isNotEmpty()) {
                    applyConfigMap(
                        map,
                        aes,
                        if (useBackup("settings")) RestoreMode.USE_BACKUP else RestoreMode.KEEP_APPLICATION,
                        allowLegacyPlaintextFallback,
                    )
                    map[PreferKey.textSelectMenuConfig]?.toString()?.let { menuConfig ->
                        get<ReadSettingsGateway>().update { it.copy(textSelectMenuConfig = menuConfig) }
                    }
                }
            } catch (e: Exception) {
                configFileParseFailed = true
                reportCategoryFailure("settings", e)
                AppLog.put("恢复配置 XML 出错\n${e.localizedMessage}", e)
            }
        }
        // 所有独立配置与 config.xml 写入完成后再刷新运行时快照，避免读取到半成品。
        get<ReadStyleGateway>().refresh()
        ReadConfigUpdateBus.post(
            setOf(
                ConfigUpdateAction.UpdateBackground,
                ConfigUpdateAction.UpdateStyle,
                ConfigUpdateAction.ReloadContent,
            )
        )
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
        BackupRestoreResultBus.emit(
            BackupRestoreResult(
                outcome = when {
                    failedCategories.isEmpty() -> BackupRestoreOutcome.SUCCESS
                    failedCategories.size < 3 -> BackupRestoreOutcome.PARTIAL
                    else -> BackupRestoreOutcome.FAILED
                },
                failures = categoryFailures,
                failedCategories = failedCategories,
                failedCategoryCounts = failedCategoryCounts.toMap(),
                configFileMissing = configFileMissing,
                configFileParseFailed = configFileParseFailed,
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

    private fun restorePlannerCategories(path: String): List<Triple<String, Boolean, Boolean>> =
        listOf(
            "resources" to File(path, "assets").exists(),
            "bookshelf" to File(path, "bookshelf.json").isFile,
            "bookmark" to File(path, "bookmark.json").isFile,
            "bookGroup" to File(path, "bookGroup.json").isFile,
            "bookSource" to File(path, "bookSource.json").isFile,
            "rssSource" to File(path, "rssSources.json").isFile,
            "rssStar" to File(path, "rssStar.json").isFile,
            "replaceRule" to File(path, "replaceRule.json").isFile,
            "searchHistory" to File(path, "searchHistory.json").isFile,
            "readRecord" to File(path, "readRecord.json").isFile,
            "server" to File(path, "servers.json").isFile,
            "sourceSub" to File(path, "sourceSub.json").isFile,
            "txtTocRule" to File(path, "txtTocRule.json").isFile,
            "httpTTS" to File(path, "httpTTS.json").isFile,
            "keyboardAssists" to File(path, "keyboardAssists.json").isFile,
            "dictRule" to File(path, "dictRule.json").isFile,
            "homepageModules" to File(path, "homepageModules.json").isFile,
            "homepageCustomSets" to File(path, "homepageCustomSets.json").isFile,
            "highlightRule" to File(path, "highlightRule.json").isFile,
            "highlightTagRule" to File(path, "highlightTagRule.json").isFile,
            "tagGroupRule" to File(path, "tagGroupRule.json").isFile,
            "settings" to File(path, "config.xml").isFile,
            "readConfig" to File(path, ReadBookConfig.configFileName).isFile,
            "themeConfig" to File(path, ThemeConfigStore.configFileName).isFile,
            "coverConfig" to File(path, BookCover.configFileName).isFile,
            "directLinkUploadRule" to File(path, DirectLinkUpload.ruleFileName).isFile,
        ).map { (category, present) ->
            Triple(category, present, when {
                category in BackupConfig.dbIgnoreConfig.filterValues { it }.keys -> true
                category == "readConfig" -> BackupConfig.ignoreReadConfig
                category == "themeConfig" -> BackupConfig.ignoreThemeConfig
                category == "coverConfig" -> BackupConfig.ignoreCoverConfig
                else -> false
            })
        }

    /** 恢复 ZIP 内按 hash 命名的背景资源；单张图片失败不影响其他类别。 */
    private fun restoreBackgroundResources(path: String): Int {
        val scan = BackupManifestScanner.scan(File(path))
        val manifest = scan.manifest
        val targetDir = appCtx.externalFiles.getFile("bg").apply { mkdirs() }
        var failures = 0
        val restoredResources = linkedSetOf<String>()
        if (manifest == null) {
            // 兼容旧 WebDAV background/目录：旧格式没有 hash 清单，只能按文件名迁移。
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
                ).filter(File::isFile).forEach { file ->
                    var text = file.readText()
                    restoredNames.forEach { name ->
                        text = text.replace(name, targetDir.resolve(name).absolutePath)
                    }
                    file.writeText(text)
                }
            }
            return failures
        }
        manifest.resources.forEach { resource ->
            if (!resource.name.startsWith("assets/background/")) return@forEach
            runCatching {
                val source = File(path, resource.name)
                if (!source.isFile) error("资源不存在")
                if (source.length() != resource.size) error("资源大小不匹配")
                if (source.sha256() != resource.sha256) error("资源校验失败")
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
            )
            files.filter(File::isFile).forEach { file ->
                var text = file.readText()
                manifest.resourceAliases.forEach { (alias, relative) ->
                    if (relative !in restoredResources) return@forEach
                    val localName = relative.substringAfterLast('/')
                    text = text.replace(alias, targetDir.resolve(localName).absolutePath)
                }
                file.writeText(text)
            }
        }
        return failures
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
        val normalization = normalizeConfigMapWithReport(
            map = map,
            keyIsNotIgnore = { BackupConfig.keyIsNotIgnore(it) },
            decryptWebDavPassword = {
                runCatching { aes.decryptStr(it) }.getOrNull().also { decrypted ->
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
        val valuesToApply = if (mode == RestoreMode.USE_BACKUP) {
            finalMap
        } else {
            val existingKeys = AppConfigStore.preferences.asMap().keys.map { it.name }.toSet()
            finalMap.filterKeys { it !in existingKeys }
        }
        // 使用带确认的批量写入，确保 Gateway/底栏读取到恢复后的完整快照。
        AppConfigStore.putAllAndAwait(valuesToApply)
        SettingsWriter.awaitPendingWrites()
    }

    private fun readXmlToMap(file: File): Map<String, Any?> {
        val result = BackupConfigCodec.decodeWithReport(file.readText())
        result.warnings.forEach { warning ->
            AppLog.put("恢复配置警告：$warning")
        }
        return result.values
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
