package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.core.net.toUri
import androidx.documentfile.provider.DocumentFile
import io.legado.app.constant.AppLog
import io.legado.app.constant.PreferKey
import io.legado.app.data.appDb
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.domain.gateway.ReadStyleGateway
import io.legado.app.domain.model.BackupSensitiveCategory
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
import io.legado.app.utils.LogUtils
import io.legado.app.utils.compress.ZipUtils
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
import kotlinx.coroutines.withContext
import org.koin.core.context.GlobalContext
import splitties.init.appCtx
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.TimeUnit

/**
 * 备份
 */
object Backup {

    private val readStyleGateway: ReadStyleGateway
        get() = GlobalContext.get().get()

    private val backupSettingsGateway: BackupSettingsGateway
        get() = GlobalContext.get().get()

    val backupPath: String by lazy {
        appCtx.filesDir.getFile("backup").createFolderIfNotExist().absolutePath
    }
    val zipFilePath = "${appCtx.externalFiles.absolutePath}${File.separator}tmp_backup.zip"

    private const val TAG = "Backup"

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

    suspend fun backupLocked(context: Context, path: String?, mode: String = "both") {
        BackupRestoreLock.withLock {
            withContext(IO) {
                backup(context, path, mode)
            }
        }
    }

    private suspend fun backup(context: Context, path: String?, mode: String = "both") {
        BackupRestoreProgressBus.emit(BackupRestoreProgress("准备数据"))
        LogUtils.d(TAG, "开始备份 path:$path")
        LocalConfig.lastBackup = System.currentTimeMillis()
        val aes = BackupAES()
        FileUtils.delete(backupPath)
        writeListToJson(
            appDb.bookDao.all.filterNot { BackupConfig.backupIgnoreLocalBook && it.isLocal },
            "bookshelf.json",
            backupPath,
        )
        BackupRestoreProgressBus.emit(BackupRestoreProgress("导出数据库"))
        // 书签与划线/想法笔记（book_marks）视为一体，统一受既有 bookmark 忽略项控制
        if (BackupConfig.dbIsNotIgnored("bookmark", true)) {
            writeListToJson(appDb.bookmarkDao.all, "bookmark.json", backupPath)
            writeListToJson(appDb.bookMarkingDao.all, "bookMarking.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookGroup", true)) {
            writeListToJson(appDb.bookGroupDao.all, "bookGroup.json", backupPath)
        }
        if (BackupConfig.dbIsNotIgnored("bookSource", true)) {
            writeListToJson(
                appDb.bookSourceDao.all.map(::sanitizeBookSource),
                "bookSource.json",
                backupPath,
            )
        }
        if (BackupConfig.dbIsNotIgnored("rssSource", true)) {
            writeListToJson(
                appDb.rssSourceDao.all.map(::sanitizeRssSource),
                "rssSources.json",
                backupPath,
            )
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
            writeListToJson(
                appDb.httpTTSDao.all.map(::sanitizeHttpTts),
                "httpTTS.json",
                backupPath,
            )
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
                aes.runCatching {
                    encryptBase64(json)
                }.getOrDefault(json).let {
                    FileUtils.createFileIfNotExist(backupPath + File.separator + "servers.json")
                        .writeText(it)
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
        }
        if (!BackupConfig.backupIgnoreThemeConfig) {
            GSON.toJson(ThemeConfigStore.configList).let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + ThemeConfigStore.configFileName)
                    .writeText(it)
            }
        }
        if (BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.DIRECT_LINK_CREDENTIALS)) {
            DirectLinkUpload.getConfig()?.let {
            FileUtils.createFileIfNotExist(backupPath + File.separator + DirectLinkUpload.ruleFileName)
                .writeText(GSON.toJson(it))
            }
        }
        if (!BackupConfig.backupIgnoreCoverConfig) {
            BookCover.getConfig()?.let {
                FileUtils.createFileIfNotExist(backupPath + File.separator + BookCover.configFileName)
                    .writeText(GSON.toJson(it))
            }
        }
        currentCoroutineContext().ensureActive()
        val configMap = AppConfigStore.preferences.asMap()
            .mapKeys { it.key.name }
        val writtenConfigKeys = mutableSetOf<String>()
        val ignoredConfigKeys = mutableSetOf<String>()
        val unsupportedConfigKeys = mutableSetOf<String>()
        val xmlBuilder = StringBuilder()
        xmlBuilder.append("<?xml version='1.0' encoding='utf-8' standalone='yes' ?>\n")
        xmlBuilder.append("<map>\n")
        configMap.forEach { (key, value) ->
            if (BackupConfig.keyIsNotIgnore(key, true) &&
                (key != PreferKey.webDavPassword ||
                    BackupConfig.sensitiveSelection.includes(BackupSensitiveCategory.WEBDAV_CREDENTIALS))
            ) {
                val finalValue = if (key == PreferKey.webDavPassword) {
                    aes.runCatching { encryptBase64(value.toString()) }.getOrElse {
                        AppLog.put("备份配置加密失败，已跳过敏感键：$key", it)
                        return@forEach
                    }
                } else value

                when (finalValue) {
                    is String -> {
                        writtenConfigKeys += key
                        xmlBuilder.append("    <string name=\"$key\">${finalValue.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")}</string>\n")
                    }
                    is Set<*> -> {
                        val values = finalValue.filterIsInstance<String>()
                        writtenConfigKeys += key
                        xmlBuilder.append("    <set name=\"$key\">\n")
                        values.forEach { item ->
                            xmlBuilder.append("        <string>${item.replace("&", "&amp;").replace("<", "&lt;").replace("\"", "&quot;")}</string>\n")
                        }
                        xmlBuilder.append("    </set>\n")
                    }
                    is Int -> { writtenConfigKeys += key; xmlBuilder.append("    <int name=\"$key\" value=\"$finalValue\" />\n") }
                    is Long -> { writtenConfigKeys += key; xmlBuilder.append("    <long name=\"$key\" value=\"$finalValue\" />\n") }
                    is Float -> { writtenConfigKeys += key; xmlBuilder.append("    <float name=\"$key\" value=\"$finalValue\" />\n") }
                    is Boolean -> { writtenConfigKeys += key; xmlBuilder.append("    <boolean name=\"$key\" value=\"$finalValue\" />\n") }
                    else -> {
                        unsupportedConfigKeys += key
                        AppLog.put("备份配置类型不支持，已跳过：$key (${finalValue::class.java.simpleName})")
                    }
                }
            } else ignoredConfigKeys += key
        }
        xmlBuilder.append("</map>")
        FileUtils.createFileIfNotExist(backupPath + File.separator + "config.xml")
            .writeText(xmlBuilder.toString())

        val resourceFiles = copyBackgroundResources(backupPath)
        BackupRestoreProgressBus.emit(BackupRestoreProgress("处理图片资源", resourceFiles.size, resourceFiles.size))

        val filesForManifest = backupFileNames
            .filter { it != "manifest.json" }
            .map { File(backupPath, it) }
            .filter(File::isFile)
        val manifest = BackupManifestBuilder.build(
            root = File(backupPath),
            files = filesForManifest,
            categories = filesForManifest.groupBy { manifestCategory(it.name) }
                .mapValues { (_, files) -> files.map(File::getName) },
            ignoredCategories = BackupConfig.backupIgnoreConfig.filterValues { it }.keys,
            configKeys = writtenConfigKeys,
            ignoredConfigKeys = ignoredConfigKeys,
            unsupportedConfigKeys = unsupportedConfigKeys,
            appVersion = BuildConfig.VERSION_NAME,
            resources = resourceFiles,
            sensitiveIncluded = filesForManifest.any { it.name == "servers.json" } ||
                PreferKey.webDavPassword in writtenConfigKeys,
            sensitiveItems = buildSet {
                if (filesForManifest.any { it.name == "servers.json" }) {
                    add(BackupSensitiveCategory.SERVER_CREDENTIALS.name)
                }
                if (PreferKey.webDavPassword in writtenConfigKeys) {
                    add(BackupSensitiveCategory.WEBDAV_CREDENTIALS.name)
                }
            },
        )
        FileUtils.createFileIfNotExist(backupPath + File.separator + "manifest.json")
            .writeText(GSON.toJson(manifest))
        BackupRestoreProgressBus.emit(BackupRestoreProgress("生成校验清单", filesForManifest.size, filesForManifest.size))

        currentCoroutineContext().ensureActive()
        val zipFileName = getNowZipFileName()
        val paths = backupFileNames
            .map { File(backupPath, it) }
            .filter(File::isFile)
            .plus(resourceFiles)
            .map(File::getAbsolutePath)
        FileUtils.delete(zipFilePath)
        FileUtils.delete(zipFilePath.replace("tmp_", ""))
        val backupFileName = if (backupSettingsGateway.currentSettings.onlyLatestBackup) {
            "backup.zip"
        } else {
            zipFileName
        }
        if (ZipUtils.zipFiles(paths, zipFilePath)) {
            BackupRestoreProgressBus.emit(BackupRestoreProgress("生成 ZIP"))
            if (mode == "both" || mode == "local") {
                when {
                    path.isNullOrBlank() -> {
                        copyBackup(context.getExternalFilesDir(null)!!, backupFileName)
                    }

                    path.isContentScheme() -> {
                        copyBackup(context, path.toUri(), backupFileName)
                    }

                    else -> {
                        copyBackup(File(path), backupFileName)
                    }
                }
            }
            if (mode == "both" || mode == "webdav") {
                BackupRestoreProgressBus.emit(BackupRestoreProgress("上传 WebDAV"))
                try {
                    AppWebDav.backUpWebDav(zipFileName)
                } catch (e: Exception) {
                    AppLog.put("上传备份至webdav失败\n$e", e)
                }
            }
        }
        FileUtils.delete(backupPath)
        FileUtils.delete(zipFilePath)
        currentCoroutineContext().ensureActive()
        BackupRestoreProgressBus.emit(BackupRestoreProgress("完成"))
    }

    private fun manifestCategory(fileName: String): String = when (fileName) {
        "config.xml" -> "settings"
        "readConfig.json", "shareReadConfig.json" -> "reader"
        "themeConfig.json" -> "theme"
        "background.json" -> "resources"
        else -> "database"
    }

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
    private fun copyBackgroundResources(rootPath: String): List<File> {
        val targetDir = File(rootPath, "assets/background").apply { mkdirs() }
        return readStyleGateway.allBackgroundImagePaths().mapNotNull { path ->
            val source = if (path.contains(File.separator)) File(path) else appCtx.externalFiles.getFile("bg", path)
            if (!source.isFile) return@mapNotNull null
            val hash = source.sha256()
            val extension = source.extension.takeIf { it.isNotBlank() }?.let { ".${it.lowercase(Locale.ROOT)}" } ?: ""
            val target = File(targetDir, "$hash$extension")
            if (!target.exists()) source.copyTo(target, overwrite = false)
            target
        }
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
