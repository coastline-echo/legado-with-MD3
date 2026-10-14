package io.legado.app.help

import android.net.Uri
import io.legado.app.R
import io.legado.app.constant.AppLog
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookProgress
import io.legado.app.exception.NoStackTraceException
import io.legado.app.domain.gateway.BackupSettingsGateway
import io.legado.app.help.config.LocalConfig
import io.legado.app.help.storage.Backup
import io.legado.app.help.storage.BackupRestoreLock
import io.legado.app.help.storage.BackupRestoreResultBus
import io.legado.app.help.storage.Restore
import io.legado.app.domain.model.RestoreMode
import io.legado.app.domain.model.RestorePreview
import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.domain.model.BackupRestoreResult
import io.legado.app.lib.webdav.Authorization
import io.legado.app.lib.webdav.WebDav
import io.legado.app.lib.webdav.WebDavException
import io.legado.app.lib.webdav.WebDavFile
import io.legado.app.model.remote.RemoteBookWebDav
import io.legado.app.utils.AlphanumComparator
import io.legado.app.utils.FileUtils
import io.legado.app.utils.GSON
import io.legado.app.utils.NetworkUtils
import io.legado.app.utils.UrlUtil
import io.legado.app.utils.compress.ZipUtils
import io.legado.app.utils.fromJsonObject
import io.legado.app.utils.isJson
import io.legado.app.utils.normalizeFileName
import io.legado.app.utils.toastOnUi
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import splitties.init.appCtx
import org.koin.core.context.GlobalContext
import java.io.File

/**
 * webDav初始化会访问网络,不要放到主线程
 */
object AppWebDav {
    private val backupGateway by lazy { GlobalContext.get().get<BackupSettingsGateway>() }

    private const val defaultWebDavUrl = "https://dav.jianguoyun.com/dav/"
    private val bookProgressUrl get() = "${rootWebDavUrl}bookProgress/"
    private val exportsWebDavUrl get() = "${rootWebDavUrl}books/"
    private val bgWebDavUrl get() = "${rootWebDavUrl}background/"

    private val configMutex = Mutex()
    private var appliedConfig: AppliedWebDavConfig? = null

    @Volatile
    var authorization: Authorization? = null
        private set

    @Volatile
    var defaultBookWebDav: RemoteBookWebDav? = null

    val isOk get() = authorization != null

    val isJianGuoYun get() = rootWebDavUrl.startsWith(defaultWebDavUrl, true)

    private val rootWebDavUrl: String
        get() {
            val configUrl = backupGateway.currentSettings.webDavUrl.trim()
            var url = if (configUrl.isEmpty()) defaultWebDavUrl else configUrl
            if (!url.endsWith("/")) url = "${url}/"
            backupGateway.currentSettings.webDavDir.trim().let {
                if (it.isNotEmpty()) {
                    url = "${url}${it}/"
                }
            }
            return url
        }

    suspend fun upConfig(): Result<Unit> {
        return configMutex.withLock {
            val config = AppliedWebDavConfig(
                url = backupGateway.currentSettings.webDavUrl,
                account = backupGateway.currentSettings.webDavAccount,
                password = backupGateway.currentSettings.webDavPassword,
                dir = backupGateway.currentSettings.webDavDir,
            )
            if (appliedConfig == config) return@withLock Result.success(Unit)

            kotlin.runCatching {
                authorization = null
                defaultBookWebDav = null
                if (config.account.isNullOrEmpty() || config.password.isNullOrEmpty()) {
                    throw NoStackTraceException("WebDAV 账号或密码为空")
                }
                val mAuthorization = Authorization(config.account, config.password)
                checkAuthorization(mAuthorization)
                check(WebDav(rootWebDavUrl, mAuthorization).makeAsDir()) { "WebDAV 根目录创建失败" }
                check(WebDav(bookProgressUrl, mAuthorization).makeAsDir()) { "WebDAV 阅读进度目录创建失败" }
                check(WebDav(exportsWebDavUrl, mAuthorization).makeAsDir()) { "WebDAV 导出目录创建失败" }
                check(WebDav(bgWebDavUrl, mAuthorization).makeAsDir()) { "WebDAV 背景目录创建失败" }
                val rootBooksUrl = "${rootWebDavUrl}books/"
                defaultBookWebDav = RemoteBookWebDav(rootBooksUrl, mAuthorization)
                authorization = mAuthorization
                appliedConfig = config
            }
        }
    }

    private data class AppliedWebDavConfig(
        val url: String,
        val account: String,
        val password: String,
        val dir: String,
    )

    @Throws(WebDavException::class)
    private suspend fun checkAuthorization(authorization: Authorization) {
        if (!WebDav(rootWebDavUrl, authorization).check()) {
            //appCtx.removePref(PreferKey.webDavPassword)
            appCtx.toastOnUi(R.string.webdav_application_authorization_error)
            throw WebDavException(appCtx.getString(R.string.webdav_application_authorization_error))
        }
    }

    @Throws(Exception::class)
    suspend fun getBackupNames(): ArrayList<String> {
        val names = arrayListOf<String>()
        authorization?.let {
            var files = WebDav(rootWebDavUrl, it).listFiles()
            files = files.sortedWith { o1, o2 ->
                AlphanumComparator.compare(o1.displayName, o2.displayName)
            }.reversed()
            files.forEach { webDav ->
                val name = webDav.displayName
                if (name.startsWith("backup")) {
                    names.add(name)
                }
            }
        } ?: throw NoStackTraceException("webDav没有配置")
        return names
    }

@Throws(WebDavException::class, NoStackTraceException::class)
    suspend fun restoreWebDav(name: String, mode: RestoreMode = RestoreMode.KEEP_APPLICATION, selectedCategories: Set<String> = emptySet(), categoryModes: Map<String, RestoreMode> = emptyMap(), backupPassword: String? = null): String {
        val auth = authorization ?: throw NoStackTraceException("WebDAV 未配置或认证失败")
        val webDav = WebDav(rootWebDavUrl + name, auth)
        val operationId = java.util.UUID.randomUUID().toString()
        return BackupRestoreLock.withLock {
            var downloaded = false
            try {
                webDav.downloadTo(Backup.zipFilePath, true)
                downloaded = true
                FileUtils.delete(Backup.backupPath)
                ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
                // 旧版 WebDAV 将背景图放在独立目录；新格式优先使用 ZIP 内资源。
                if (!File(Backup.backupPath, "manifest.json").isFile) {
                    downloadLegacyBackgrounds(File(Backup.backupPath, "assets/background"))
                }
                Restore.restoreUnzipped(
                    path = Backup.backupPath,
                    mode = mode,
                    selectedCategories = selectedCategories,
                    categoryModes = categoryModes,
                    backupPassword = backupPassword,
                    operationId = operationId,
                )
                BackupRestoreResultBus.getResult(operationId)
                    ?.takeUnless {
                        it.outcome == BackupRestoreOutcome.FAILED ||
                            it.outcome == BackupRestoreOutcome.PRECONDITION_FAILED
                    }
                    ?.let { LocalConfig.lastBackup = System.currentTimeMillis() }
                operationId
            } catch (error: Exception) {
                BackupRestoreResultBus.emit(
                    BackupRestoreResult(
                        operationId = operationId,
                        outcome = BackupRestoreOutcome.FAILED,
                        failures = 1,
                        webDavDownloadFailed = !downloaded,
                        unzipFailed = downloaded,
                    )
                )
                throw error
            }
        }
    }

    suspend fun hasBackUp(backUpName: String): Boolean {
        authorization?.let {
            val url = "$rootWebDavUrl${backUpName}"
            return WebDav(url, it).exists()
        }
        return false
    }

    suspend fun lastBackUp(): Result<WebDavFile?> {
        return kotlin.runCatching {
            authorization?.let {
                var lastBackupFile: WebDavFile? = null
                WebDav(rootWebDavUrl, it).listFiles().reversed().forEach { webDavFile ->
                    if (webDavFile.displayName.startsWith("backup")) {
                        if (lastBackupFile == null
                            || webDavFile.lastModify > lastBackupFile.lastModify
                        ) {
                            lastBackupFile = webDavFile
                        }
                    }
                }
                lastBackupFile
            }
        }
    }

    suspend fun testWebDav(): Boolean {
        return kotlin.runCatching {
            val account = backupGateway.currentSettings.webDavAccount
            val password = backupGateway.currentSettings.webDavPassword
            if (account.isNullOrEmpty() || password.isNullOrEmpty()) {
                appCtx.toastOnUi("账号或密码为空")
                return false
            }

            val auth = Authorization(account, password)
            checkAuthorization(auth)

            appCtx.toastOnUi("WebDAV 服务可用")
            true
        }.getOrElse {
            it.printStackTrace()
            if (it !is WebDavException) {
                appCtx.toastOnUi(it.message ?: "未知错误")
            }
            false
        }
    }



    /**
     * webDav备份
     * @param fileName 备份文件名
     */
    @Throws(Exception::class)
    suspend fun backUpWebDav(fileName: String) {
        if (!NetworkUtils.isAvailable()) {
            throw NoStackTraceException("网络不可用")
        }
        authorization?.let {
            val putUrl = "$rootWebDavUrl$fileName"
            WebDav(putUrl, it).upload(Backup.zipFilePath)
        } ?: throw NoStackTraceException("WebDAV 未配置或认证失败")
    }

    /**
     * 获取云端所有背景名称
     */
    private suspend fun getAllBgWebDavFiles(): Result<List<WebDavFile>> {
        return kotlin.runCatching {
            if (!NetworkUtils.isAvailable())
                throw NoStackTraceException("网络未连接")
            authorization.let {
                it ?: throw NoStackTraceException("webDav未配置")
                WebDav(bgWebDavUrl, it).listFiles()
            }
        }
    }

    /**
     * 上传背景图片
     */
    suspend fun upBgs(files: Array<File>) {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bgWebDavFiles = getAllBgWebDavFiles().getOrThrow()
            .map { it.displayName }
            .toSet()
        files.forEach {
            if (!bgWebDavFiles.contains(it.name) && it.exists()) {
                WebDav("$bgWebDavUrl${it.name}", authorization)
                    .upload(it)
            }
        }
}

    suspend fun previewWebDav(name: String): RestorePreview {
        val auth = authorization ?: throw NoStackTraceException("webDav没有配置")
        return BackupRestoreLock.withLock {
            WebDav(rootWebDavUrl + name, auth).downloadTo(Backup.zipFilePath, true)
            FileUtils.delete(Backup.backupPath)
            ZipUtils.unZipToPath(File(Backup.zipFilePath), Backup.backupPath)
            if (!File(Backup.backupPath, "manifest.json").isFile) {
                downloadLegacyBackgrounds(File(Backup.backupPath, "assets/background"))
            }
            Restore.previewUnzipped(Backup.backupPath)
        }
    }

    private suspend fun downloadLegacyBackgrounds(targetDir: File) {
        val auth = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        targetDir.mkdirs()
        getAllBgWebDavFiles().getOrNull()?.forEach { remote ->
            val name = remote.displayName
            runCatching {
                WebDav("$bgWebDavUrl$name", auth).downloadTo(File(targetDir, name).absolutePath, true)
            }.onFailure { error ->
                AppLog.put("下载旧版背景失败：$name\n${error.localizedMessage}", error)
            }
        }
    }

    @Suppress("unused")
    suspend fun exportWebDav(byteArray: ByteArray, fileName: String): Result<Unit> {
        if (!NetworkUtils.isAvailable()) return Result.failure(NoStackTraceException("网络不可用"))
        return try {
            val auth = authorization ?: return Result.failure(NoStackTraceException("WebDAV 未配置或认证失败"))
            // 如果导出的本地文件存在,开始上传
            val putUrl = exportsWebDavUrl + fileName
            WebDav(putUrl, auth).upload(byteArray, "text/plain")
            Result.success(Unit)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
            Result.failure(e)
        }
    }

    suspend fun exportWebDav(uri: Uri, fileName: String): Result<Unit> {
        if (!NetworkUtils.isAvailable()) return Result.failure(NoStackTraceException("网络不可用"))
        return try {
            val auth = authorization ?: return Result.failure(NoStackTraceException("WebDAV 未配置或认证失败"))
            // 如果导出的本地文件存在,开始上传
            val putUrl = exportsWebDavUrl + fileName
            WebDav(putUrl, auth).upload(uri, "text/plain")
            Result.success(Unit)
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("WebDav导出失败\n${e.localizedMessage}", e, true)
            Result.failure(e)
        }
    }

    suspend fun uploadBookProgress(
        book: Book,
        toast: Boolean = false,
        onSuccess: (() -> Unit)? = null
    ) {
        val authorization = authorization ?: return
        if (!backupGateway.currentSettings.syncBookProgress) return
        if (!NetworkUtils.isAvailable()) return
        try {
            val bookProgress = BookProgress(book)
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(book.name, book.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            book.syncTime = System.currentTimeMillis()
            onSuccess?.invoke()
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e, toast)
        }
    }

    suspend fun uploadBookProgress(
        bookProgress: BookProgress,
        onSuccess: (() -> Unit)? = null
    ): Boolean {
        try {
            val authorization = authorization ?: return false
            if (!backupGateway.currentSettings.syncBookProgress) return false
            if (!NetworkUtils.isAvailable()) return false
            val json = GSON.toJson(bookProgress)
            val url = getProgressUrl(bookProgress.name, bookProgress.author)
            WebDav(url, authorization).upload(json.toByteArray(), "application/json")
            onSuccess?.invoke()
            return true
        } catch (e: Exception) {
            currentCoroutineContext().ensureActive()
            AppLog.put("上传进度失败\n${e.localizedMessage}", e)
            return false
        }
    }

    private fun getProgressUrl(name: String, author: String): String {
        return bookProgressUrl + getProgressFileName(name, author)
    }

    private fun getProgressFileName(name: String, author: String): String {
        return UrlUtil.replaceReservedChar("${name}_${author}".normalizeFileName()) + ".json"
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(book: Book): BookProgress? {
        return getBookProgress(book.name, book.author)
    }

    /**
     * 获取书籍进度
     */
    suspend fun getBookProgress(name: String, author: String): BookProgress? {
        val url = getProgressUrl(name, author)
        kotlin.runCatching {
            val authorization = authorization ?: return null
            WebDav(url, authorization).download().let { byteArray ->
                val json = String(byteArray)
                if (json.isJson()) {
                    return GSON.fromJsonObject<BookProgress>(json).getOrNull()

                }



            }
        }.onFailure {
            currentCoroutineContext().ensureActive()
            AppLog.put("获取书籍进度失败\n${it.localizedMessage}", it)
        }
        return null
    }

    suspend fun downloadAllBookProgress() {
        val authorization = authorization ?: return
        if (!NetworkUtils.isAvailable()) return
        val bookProgressFiles = WebDav(bookProgressUrl, authorization).listFiles()
        val map = hashMapOf<String, WebDavFile>()
        bookProgressFiles.forEach {
            map[it.displayName] = it
        }
        appDb.bookDao.all.forEach { book ->
            val progressFileName = getProgressFileName(book.name, book.author)
            val webDavFile = map[progressFileName]
            webDavFile ?: return@forEach
            if (webDavFile.lastModify <= book.syncTime) {
                //本地同步时间大于上传时间不用同步
                return@forEach
            }
            getBookProgress(book)?.let { bookProgress ->
                if (bookProgress.durChapterIndex > book.durChapterIndex
                    || (bookProgress.durChapterIndex == book.durChapterIndex
                            && bookProgress.durChapterPos > book.durChapterPos)
                ) {
                    book.durChapterIndex = bookProgress.durChapterIndex
                    book.durChapterPos = bookProgress.durChapterPos
                    book.durChapterTitle = bookProgress.durChapterTitle
                    book.durChapterTime = bookProgress.durChapterTime
                    book.syncTime = System.currentTimeMillis()
                    appDb.bookDao.update(book)
                }
            }
        }
    }

}
