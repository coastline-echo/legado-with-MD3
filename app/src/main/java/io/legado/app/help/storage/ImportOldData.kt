package io.legado.app.help.storage

import android.content.Context
import android.net.Uri
import androidx.documentfile.provider.DocumentFile
import com.jayway.jsonpath.DocumentContext
import io.legado.app.R
import io.legado.app.constant.AppConst
import io.legado.app.constant.BookSourceType
import io.legado.app.constant.BookType
import io.legado.app.data.appDb
import io.legado.app.data.entities.Book
import io.legado.app.data.entities.BookSource
import io.legado.app.data.entities.rule.*
import io.legado.app.exception.NoStackTraceException
import io.legado.app.help.ReplaceAnalyzer
import io.legado.app.utils.*
import io.legado.app.utils.compress.ZipUtils
import splitties.init.appCtx
import java.io.File

object ImportOldData {

    data class ImportResult(
        val processedFiles: Int = 0,
        val importedItems: Int = 0,
        val failedFiles: Int = 0,
        val skippedFiles: Int = 0,
    )

    internal val legacyFileNames = setOf("myBookShelf.json", "myBookSource.json", "myBookReplaceRule.json")

    @Suppress("RegExpRedundantEscape")
    private val headerPattern = Regex("@Header:\\{.+?\\}", RegexOption.IGNORE_CASE)
    @Suppress("RegExpRedundantEscape")
    private val jsPattern = Regex("\\{\\{.+?\\}\\}", RegexOption.IGNORE_CASE)

    fun importUri(context: Context, uri: Uri): ImportResult {
        var processedFiles = 0
        var importedItems = 0
        var failedFiles = 0
        var skippedFiles = 0
        if (uri.isContentScheme()) {
            val documents = DocumentFile.fromTreeUri(context, uri)?.listFiles()?.toList()
                ?: DocumentFile.fromSingleUri(context, uri)?.let(::listOf)
                ?: emptyList()
            if (documents.isEmpty()) {
                context.toastOnUi(context.getString(R.string.import_old_no_files))
                return ImportResult(skippedFiles = 1)
            }
            // 单文件 ZIP（院办/旧版应用常见导出格式）先解压，再扫描三个旧版 JSON。
            if (documents.size == 1 && documents[0].name?.lowercase()?.endsWith(".zip") == true) {
                val temp = File(context.cacheDir, "legacy_import_${System.nanoTime()}").apply { mkdirs() }
                return try {
                    context.contentResolver.openInputStream(documents[0].uri)?.use {
                        ZipUtils.unZipToPath(it, temp.absolutePath)
                    } ?: return ImportResult(failedFiles = 1)
                    importDirectory(temp)
                } finally {
                    temp.deleteRecursively()
                }
            }
            documents.forEach { doc ->
                when (doc.name) {
                    "myBookShelf.json" ->
                        kotlin.runCatching {
                            doc.uri.readText(context).let { json ->
                                val importCount = importOldBookshelf(json)
                                processedFiles++
                                importedItems += importCount
                                context.toastOnUi("成功导入书架${importCount}")
                            }
                        }.onFailure {
                            failedFiles++
                            context.toastOnUi("导入书架失败\n${it.localizedMessage}")
                        }
                    "myBookSource.json" ->
                        kotlin.runCatching {
                            doc.uri.readText(context).let { json ->
                                val importCount = importOldSource(json)
                                processedFiles++
                                importedItems += importCount
                                context.toastOnUi("成功导入书源${importCount}")
                            }
                        }.onFailure {
                            failedFiles++
                            context.toastOnUi("导入源失败\n${it.localizedMessage}")
                        }
                    "myBookReplaceRule.json" ->
                        kotlin.runCatching {
                            doc.uri.readText(context).let { json ->
                                val importCount = importOldReplaceRule(json)
                                processedFiles++
                                importedItems += importCount
                                context.toastOnUi("成功导入替换规则${importCount}")
                            }
                        }.onFailure {
                            failedFiles++
                            context.toastOnUi("导入替换规则失败\n${it.localizedMessage}")
                        }
                    else -> {
                        skippedFiles++
                        context.toastOnUi(context.getString(R.string.import_old_unsupported))
                    }
                }
            }
        } else {
            uri.path?.let { path ->
                val file = File(path)
                if (file.isFile) {
                    if (file.extension.equals("zip", ignoreCase = true)) {
                        val temp = File(context.cacheDir, "legacy_import_${System.nanoTime()}").apply { mkdirs() }
                        return try {
                            ZipUtils.unZipToPath(file, temp.absolutePath)
                            importDirectory(temp)
                        } finally {
                            temp.deleteRecursively()
                        }
                    }
                    kotlin.runCatching {
                        val json = file.readText()
                        when (file.name) {
                            "myBookShelf.json" -> {
                                val count = importOldBookshelf(json)
                                processedFiles++; importedItems += count
                                context.toastOnUi("成功导入书架${count}")
                            }
                            "myBookSource.json" -> {
                                val count = importOldSource(json)
                                processedFiles++; importedItems += count
                                context.toastOnUi("成功导入书源${count}")
                            }
                            "myBookReplaceRule.json" -> {
                                val count = importOldReplaceRule(json)
                                processedFiles++; importedItems += count
                                context.toastOnUi("成功导入替换规则${count}")
                            }
                            else -> throw NoStackTraceException("不是可识别的旧版备份文件")
                        }
                    }.onFailure {
                        failedFiles++
                        context.toastOnUi("旧版数据导入失败\n${it.localizedMessage}")
                    }
                    return@let
                }
                kotlin.runCatching {// 导入书架
                    val shelfFile = if (file.isDirectory) file.getFile("myBookShelf.json") else file
                    if (!shelfFile.isFile) throw NoStackTraceException("未找到旧版书架文件")
                    val json = shelfFile.readText()
                    val importCount = importOldBookshelf(json)
                    processedFiles++; importedItems += importCount
                    context.toastOnUi("成功导入书架${importCount}")
                }.onFailure {
                    failedFiles++
                    context.toastOnUi("导入书架失败\n${it.localizedMessage}")
                }

                kotlin.runCatching {// Book source
                    val sourceFile = if (file.isDirectory) file.getFile("myBookSource.json") else null
                    if (sourceFile == null || !sourceFile.isFile) throw NoStackTraceException("未找到旧版书源文件")
                    val json = sourceFile.readText()
                    val importCount = importOldSource(json)
                    processedFiles++; importedItems += importCount
                    context.toastOnUi("成功导入书源${importCount}")
                }.onFailure {
                    failedFiles++
                    context.toastOnUi("导入源失败\n${it.localizedMessage}")
                }

                kotlin.runCatching {// Replace rules
                    val ruleFile = file.getFile("myBookReplaceRule.json")
                    if (ruleFile.exists()) {
                        val json = ruleFile.readText()
                        val importCount = importOldReplaceRule(json)
                        processedFiles++; importedItems += importCount
                        context.toastOnUi("成功导入替换规则${importCount}")
                    } else {
                        skippedFiles++
                        context.toastOnUi("未找到替换规则")
                    }
                }.onFailure {
                    failedFiles++
                    context.toastOnUi("导入替换规则失败\n${it.localizedMessage}")
                }
            }
        }
        return ImportResult(processedFiles, importedItems, failedFiles, skippedFiles)
    }

    private fun importDirectory(directory: File): ImportResult {
        var processed = 0
        var imported = 0
        var failed = 0
        var skipped = 0
        legacyFileNames.forEach { name ->
            val file = directory.walkTopDown().firstOrNull { it.isFile && it.name == name }
            if (file == null) {
                skipped++
                return@forEach
            }
            runCatching {
                val count = when (name) {
                    "myBookShelf.json" -> importOldBookshelf(file.readText())
                    "myBookSource.json" -> importOldSource(file.readText())
                    else -> importOldReplaceRule(file.readText())
                }
                processed++
                imported += count
            }.onFailure { failed++ }
        }
        return ImportResult(processed, imported, failed, skipped)
    }

    private fun importOldBookshelf(json: String): Int {
        val books = fromOldBooks(json)
        return books.count { book -> kotlin.runCatching { appDb.bookDao.insert(book) }.isSuccess }
    }

    fun importOldSource(json: String): Int {
        val sources = fromOldBookSources(json)
        return sources.count { source -> kotlin.runCatching { appDb.bookSourceDao.insert(source) }.isSuccess }
    }

    private fun importOldReplaceRule(json: String): Int {
        val rules = ReplaceAnalyzer.jsonToReplaceRules(json).getOrNull()
        rules?.let {
            return rules.count { rule -> kotlin.runCatching { appDb.replaceRuleDao.insert(rule) }.isSuccess }
        }
        return 0
    }

    private fun fromOldBooks(json: String): List<Book> {
        val books = mutableListOf<Book>()
        val items: List<Map<String, Any>> = jsonPath.parse(json).read("$")
        val existingBooks = appDb.bookDao.allBookUrls.toSet()
        for (item in items) {
            val jsonItem = jsonPath.parse(item)
            val book = Book()
            book.bookUrl = jsonItem.readString("$.noteUrl") ?: ""
            if (book.bookUrl.isBlank()) continue
            book.name = jsonItem.readString("$.bookInfoBean.name") ?: ""
            if (book.bookUrl in existingBooks) {
                DebugLog.d(javaClass.name, "Found existing book: " + book.name)
                continue
            }
            book.origin = jsonItem.readString("$.tag") ?: ""
            book.originName = jsonItem.readString("$.bookInfoBean.origin") ?: ""
            book.author = jsonItem.readString("$.bookInfoBean.author") ?: ""
            val local = if (book.origin == "loc_book") BookType.local else 0
            val isAudio = jsonItem.readString("$.bookInfoBean.bookSourceType") == "AUDIO"
            book.type = local or if (isAudio) BookType.audio else BookType.text
            book.tocUrl = jsonItem.readString("$.bookInfoBean.chapterUrl") ?: book.bookUrl
            book.coverUrl = jsonItem.readString("$.bookInfoBean.coverUrl")
            book.customCoverUrl = jsonItem.readString("$.customCoverPath")
            book.lastCheckTime = jsonItem.readLong("$.bookInfoBean.finalRefreshData") ?: 0
            book.canUpdate = jsonItem.readBool("$.allowUpdate") == true
            book.totalChapterNum = jsonItem.readInt("$.chapterListSize") ?: 0
            book.durChapterIndex = jsonItem.readInt("$.durChapter") ?: 0
            book.durChapterTitle = jsonItem.readString("$.durChapterName")
            book.durChapterPos = jsonItem.readInt("$.durChapterPage") ?: 0
            book.durChapterTime = jsonItem.readLong("$.finalDate") ?: 0
            book.intro = jsonItem.readString("$.bookInfoBean.introduce")
            book.latestChapterTitle = jsonItem.readString("$.lastChapterName")
            book.lastCheckCount = jsonItem.readInt("$.newChapters") ?: 0
            book.order = jsonItem.readInt("$.serialNumber") ?: 0
            book.variable = jsonItem.readString("$.variable")
            book.setUseReplaceRule(jsonItem.readBool("$.useReplaceRule") == true)
            books.add(book)
        }
        return books
    }

    private fun fromOldBookSources(json: String): MutableList<BookSource> {
        val sources = mutableListOf<BookSource>()
        val items: List<Map<String, Any>> = jsonPath.parse(json).read("$")
        for (item in items) {
            val jsonItem = jsonPath.parse(item)
            val source = fromOldBookSource(jsonItem)
            sources.add(source)
        }
        return sources
    }

    fun fromOldBookSource(jsonItem: DocumentContext): BookSource {
        val source = BookSource()
        return source.apply {
            bookSourceUrl = jsonItem.readString("bookSourceUrl")
                ?: throw NoStackTraceException(appCtx.getString(R.string.wrong_format))
            bookSourceName = jsonItem.readString("bookSourceName") ?: ""
            bookSourceGroup = jsonItem.readString("bookSourceGroup")
            loginUrl = jsonItem.readString("loginUrl")
            loginUi = jsonItem.readString("loginUi")
            loginCheckJs = jsonItem.readString("loginCheckJs")
            coverDecodeJs = jsonItem.readString("coverDecodeJs")
            bookSourceComment = jsonItem.readString("bookSourceComment") ?: ""
            bookUrlPattern = jsonItem.readString("ruleBookUrlPattern")
            customOrder = jsonItem.readInt("serialNumber") ?: 0
            header = uaToHeader(jsonItem.readString("httpUserAgent"))
            searchUrl = toNewUrl(jsonItem.readString("ruleSearchUrl"))
            exploreUrl = toNewUrls(jsonItem.readString("ruleFindUrl"))
            bookSourceType =
                if (jsonItem.readString("bookSourceType") == "AUDIO") BookSourceType.audio else BookSourceType.default
            enabled = jsonItem.readBool("enable") ?: true
            if (exploreUrl.isNullOrBlank()) {
                enabledExplore = false
            }
            ruleSearch = SearchRule(
                bookList = toNewRule(jsonItem.readString("ruleSearchList")),
                name = toNewRule(jsonItem.readString("ruleSearchName")),
                author = toNewRule(jsonItem.readString("ruleSearchAuthor")),
                intro = toNewRule(jsonItem.readString("ruleSearchIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleSearchKind")),
                bookUrl = toNewRule(jsonItem.readString("ruleSearchNoteUrl")),
                coverUrl = toNewRule(jsonItem.readString("ruleSearchCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleSearchLastChapter"))
            )
            ruleExplore = ExploreRule(
                bookList = toNewRule(jsonItem.readString("ruleFindList")),
                name = toNewRule(jsonItem.readString("ruleFindName")),
                author = toNewRule(jsonItem.readString("ruleFindAuthor")),
                intro = toNewRule(jsonItem.readString("ruleFindIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleFindKind")),
                bookUrl = toNewRule(jsonItem.readString("ruleFindNoteUrl")),
                coverUrl = toNewRule(jsonItem.readString("ruleFindCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleFindLastChapter"))
            )
            ruleBookInfo = BookInfoRule(
                init = toNewRule(jsonItem.readString("ruleBookInfoInit")),
                name = toNewRule(jsonItem.readString("ruleBookName")),
                author = toNewRule(jsonItem.readString("ruleBookAuthor")),
                intro = toNewRule(jsonItem.readString("ruleIntroduce")),
                kind = toNewRule(jsonItem.readString("ruleBookKind")),
                coverUrl = toNewRule(jsonItem.readString("ruleCoverUrl")),
                lastChapter = toNewRule(jsonItem.readString("ruleBookLastChapter")),
                tocUrl = toNewRule(jsonItem.readString("ruleChapterUrl"))
            )
            ruleToc = TocRule(
                chapterList = toNewRule(jsonItem.readString("ruleChapterList")),
                chapterName = toNewRule(jsonItem.readString("ruleChapterName")),
                chapterUrl = toNewRule(jsonItem.readString("ruleContentUrl")),
                nextTocUrl = toNewRule(jsonItem.readString("ruleChapterUrlNext"))
            )
            var content = toNewRule(jsonItem.readString("ruleBookContent")) ?: ""
            if (content.startsWith("$") && !content.startsWith("$.")) {
                content = content.substring(1)
            }
            ruleContent = ContentRule(
                content = content,
                replaceRegex = toNewRule(jsonItem.readString("ruleBookContentReplace")),
                nextContentUrl = toNewRule(jsonItem.readString("ruleContentUrlNext"))
            )
        }
    }


    // default规则适配
    // #正则#替换内容 替换成 ##正则##替换内容
    // | 替换成 ||
    // & 替换成 &&
    private fun toNewRule(oldRule: String?): String? {
        if (oldRule.isNullOrBlank()) return null
        var newRule = oldRule
        var reverse = false
        var allinone = false
        if (oldRule.startsWith("-")) {
            reverse = true
            newRule = oldRule.substring(1)
        }
        if (newRule.startsWith("+")) {
            allinone = true
            newRule = newRule.substring(1)
        }
        if (!newRule.startsWith("@CSS:", true) &&
            !newRule.startsWith("@XPath:", true) &&
            !newRule.startsWith("//") &&
            !newRule.startsWith("##") &&
            !newRule.startsWith(":") &&
            !newRule.contains("@js:", true) &&
            !newRule.contains("<js>", true)
        ) {
            if (newRule.contains("#") && !newRule.contains("##")) {
                newRule = oldRule.replace("#", "##")
            }
            if (newRule.contains("|") && !newRule.contains("||")) {
                if (newRule.contains("##")) {
                    val list = newRule.split("##")
                    if (list[0].contains("|")) {
                        newRule = list[0].replace("|", "||")
                        for (i in 1 until list.size) {
                            newRule += "##" + list[i]
                        }
                    }
                } else {
                    newRule = newRule.replace("|", "||")
                }
            }
            if (newRule.contains("&")
                && !newRule.contains("&&")
                && !newRule.contains("http")
                && !newRule.startsWith("/")
            ) {
                newRule = newRule.replace("&", "&&")
            }
        }
        if (allinone) {
            newRule = "+$newRule"
        }
        if (reverse) {
            newRule = "-$newRule"
        }
        return newRule
    }

    private fun toNewUrls(oldUrls: String?): String? {
        if (oldUrls.isNullOrBlank()) return null
        if (oldUrls.startsWith("@js:") || oldUrls.startsWith("<js>")) {
            return oldUrls
        }
        if (!oldUrls.contains("\n") && !oldUrls.contains("&&")) {
            return toNewUrl(oldUrls)
        }
        val urls = oldUrls.split("(&&|\r?\n)+".toRegex())
        return urls.map {
            toNewUrl(it)?.replace("\n\\s*".toRegex(), "")
        }.joinToString("\n")
    }

    private fun toNewUrl(oldUrl: String?): String? {
        if (oldUrl.isNullOrBlank()) return null
        var url: String = oldUrl
        if (oldUrl.startsWith("<js>", true)) {
            url = url.replace("=searchKey", "={{key}}")
                .replace("=searchPage", "={{page}}")
            return url
        }
        val map = HashMap<String, String>()
        headerPattern.find(url)?.let { match ->
            val header = match.value
            url = url.replace(header, "")
            map["headers"] = header.substring(8)
        }
        var urlList = url.split("|")
        url = urlList[0]
        if (urlList.size > 1) {
            map["charset"] = urlList[1].split("=")[1]
        }
        val jsList = arrayListOf<String>()
        for (match in jsPattern.findAll(url)) {
            jsList.add(match.value)
            url = url.replace(jsList.last(), "$${jsList.size - 1}")
        }
        url = url.replace("{", "<").replace("}", ">")
        url = url.replace("searchKey", "{{key}}")
        url = url.replace("<searchPage([-+]1)>".toRegex(), "{{page$1}}")
            .replace("searchPage([-+]1)".toRegex(), "{{page$1}}")
            .replace("searchPage", "{{page}}")
        for ((index, item) in jsList.withIndex()) {
            url = url.replace(
                "$$index",
                item.replace("searchKey", "key").replace("searchPage", "page")
            )
        }
        urlList = url.split("@")
        url = urlList[0]
        if (urlList.size > 1) {
            map["method"] = "POST"
            map["body"] = urlList[1]
        }
        if (map.size > 0) {
            url += "," + GSON.toJson(map)
        }
        return url
    }

    private fun uaToHeader(ua: String?): String? {
        if (ua.isNullOrEmpty()) return null
        val map = mapOf(Pair(AppConst.UA_NAME, ua))
        return GSON.toJson(map)
    }

}
