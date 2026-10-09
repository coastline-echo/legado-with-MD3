package io.legado.app.help.storage

import io.legado.app.utils.GSON
import java.io.File
import java.security.MessageDigest
import java.io.InputStream
import java.util.zip.ZipInputStream
import java.io.ByteArrayOutputStream

data class BackupScanResult(
    val legacyFormat: Boolean,
    val manifest: BackupManifest? = null,
    val warnings: List<String> = emptyList(),
) {
    val isUsable: Boolean get() = legacyFormat || (manifest != null && warnings.isEmpty())
}

/** 恢复写入前校验 ZIP 解压目录及清单中的文件。 */
object BackupManifestScanner {

    /** 当前分支清单固定使用 v1 结构；main 无清单时由上层走旧格式路径。 */
    private fun parseManifest(json: String): BackupManifest {
        return GSON.fromJson(json, BackupManifest::class.java)
    }

    fun scanZip(input: InputStream): BackupScanResult {
        val entries = linkedMapOf<String, Pair<Long, String>>()
        var manifest: BackupManifest? = null
        var integrity: BackupManifestTechnical? = null
        var manifestSeen = false
        var manifestWarning: String? = null
        val entryWarnings = mutableListOf<String>()
        val seenEntries = hashSetOf<String>()
        ZipInputStream(input).use { zip ->
            var entry = zip.nextEntry
            while (entry != null) {
                if (!entry.isDirectory) {
                    if (!seenEntries.add(entry.name)) {
                        entryWarnings += "ZIP 存在重复文件：${entry.name}"
                    }
                    if (!entry.name.isSafeBackupEntry()) {
                        entryWarnings += "文件路径越界：${entry.name}"
                    }
                    val digest = MessageDigest.getInstance("SHA-256")
                    val manifestOutput = if (entry.name == "manifest.json") ByteArrayOutputStream() else null
                    val integrityOutput = if (entry.name == "integrity.json") ByteArrayOutputStream() else null
                    var size = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = zip.read(buffer)
                    while (count >= 0) {
                        if (count > 0) {
                            digest.update(buffer, 0, count)
                            manifestOutput?.write(buffer, 0, count)
                            integrityOutput?.write(buffer, 0, count)
                            size += count
                        }
                        count = zip.read(buffer)
                    }
                    entries[entry.name] = size to digest.digest().joinToString("") { "%02x".format(it) }
                    if (manifestOutput != null) {
                        manifestSeen = true
                        manifest = runCatching {
                            parseManifest(manifestOutput.toString(Charsets.UTF_8))
                        }.onFailure {
                            manifestWarning = "manifest.json 解析失败：${it.localizedMessage}"
                        }.getOrNull()
                    }
                    if (integrityOutput != null) {
                        integrity = runCatching {
                            GSON.fromJson(integrityOutput.toString(Charsets.UTF_8), BackupManifestTechnical::class.java)
                        }.getOrNull()
                        if (integrity == null) entryWarnings += "integrity.json 解析失败"
                    }
                    zip.closeEntry()
                }
                entry = zip.nextEntry
            }
        }
        if (!manifestSeen) return BackupScanResult(legacyFormat = true)
        val warnings = mutableListOf<String>()
        warnings += entryWarnings.distinct()
        manifestWarning?.let { warnings += it }
        val parsedManifest = manifest
            ?.copy(technical = integrity ?: manifest.technical)
            ?.withTechnicalCategoryIds()
            ?.withTechnicalSensitiveCategoryIds()
            ?: return BackupScanResult(false, warnings = warnings.ifEmpty { listOf("manifest.json 解析失败") })
        warnings += parsedManifest.warnings
        if (parsedManifest.formatVersion != BACKUP_MANIFEST_FORMAT_VERSION) {
            warnings += "manifest.json 格式版本不受支持：${parsedManifest.formatVersion}"
        }
        if (parsedManifest.createdAt <= 0L) warnings += "manifest.json 创建时间无效"
        if (parsedManifest.appVersion.isBlank()) warnings += "manifest.json 缺少应用版本"
        if (integrity == null && parsedManifest.technical.files.isEmpty() && parsedManifest.technical.resources.isEmpty()) {
            warnings += "integrity.json 缺失，无法校验备份文件完整性"
        }
        val declaredEntries = (parsedManifest.files.map { it.name } + parsedManifest.resources.map { it.name }).toSet()
        if (declaredEntries.size != parsedManifest.files.size + parsedManifest.resources.size) {
            warnings += "manifest.json 存在重复文件声明"
        }
        validateCategories(parsedManifest, declaredEntries, warnings)
        validateCategoryCounts(parsedManifest, warnings)
        validateResourceAliases(parsedManifest, declaredEntries, warnings)
        validateSensitiveItems(root = null, manifest = parsedManifest, declaredEntries = declaredEntries, warnings = warnings)
        (parsedManifest.files.map { Triple(it.name, it.size, it.sha256) } +
            parsedManifest.resources.map { Triple(it.name, it.size, it.sha256) }).forEach { triple ->
            val (name, size, hash) = triple
            val actual = entries[name]
            if (actual == null) warnings += "缺少文件：$name"
            else {
                if (actual.first != size) warnings += "文件大小不匹配：$name"
                if (actual.second != hash) warnings += "文件校验失败：$name"
            }
        }
        entries.keys
            .filterNot { it == "manifest.json" || it == "integrity.json" || it in declaredEntries }
            .forEach { warnings += "未登记文件：$it" }
        return BackupScanResult(false, parsedManifest, warnings)
    }

    fun scan(root: File): BackupScanResult {
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) {
            return BackupScanResult(legacyFormat = true)
        }
        val manifest = runCatching { parseManifest(manifestFile.readText()) }
            .getOrElse {
                return BackupScanResult(
                    false,
                    warnings = listOf("manifest.json 解析失败：${it.localizedMessage}"),
                )
            }
        val integrity = File(root, "integrity.json").takeIf(File::isFile)?.let { file ->
            runCatching {
                GSON.fromJson(file.readText(), BackupManifestTechnical::class.java)
            }.getOrElse {
                null
            }
        }
        val mergedManifest = manifest.copy(technical = integrity ?: manifest.technical)
            .withTechnicalCategoryIds()
            .withTechnicalSensitiveCategoryIds()
        val warnings = mutableListOf<String>()
        warnings += mergedManifest.warnings
        if (mergedManifest.formatVersion != BACKUP_MANIFEST_FORMAT_VERSION) {
            warnings += "manifest.json 格式版本不受支持：${mergedManifest.formatVersion}"
        }
        if (mergedManifest.createdAt <= 0L) warnings += "manifest.json 创建时间无效"
        if (mergedManifest.appVersion.isBlank()) warnings += "manifest.json 缺少应用版本"
        if (integrity == null && mergedManifest.technical.files.isEmpty() && mergedManifest.technical.resources.isEmpty()) {
            warnings += "integrity.json 缺失，无法校验备份文件完整性"
        }
        mergedManifest.files.forEach { entry ->
            validateEntry(root, entry.name, entry.size, entry.sha256, warnings)
        }
        mergedManifest.resources.forEach { entry ->
            validateEntry(root, entry.name, entry.size, entry.sha256, warnings)
        }
        val declaredEntries = (mergedManifest.files.map { it.name } + mergedManifest.resources.map { it.name }).toSet()
        if (declaredEntries.size != mergedManifest.files.size + mergedManifest.resources.size) {
            warnings += "manifest.json 存在重复文件声明"
        }
        validateCategories(mergedManifest, declaredEntries, warnings)
        validateCategoryCounts(mergedManifest, warnings)
        validateResourceAliases(mergedManifest, declaredEntries, warnings)
        validateSensitiveItems(root, mergedManifest, declaredEntries, warnings)
        val rootPath = root.canonicalFile.toPath()
        root.walkTopDown()
            .filter(File::isFile)
            .mapNotNull { file ->
                val relative = runCatching { rootPath.relativize(file.canonicalFile.toPath()).toString() }
                    .getOrNull()
                    ?.replace(File.separatorChar, '/')
                    ?: return@mapNotNull null
                relative to file
            }
            .filterNot { (name, _) -> name == "manifest.json" || name == "integrity.json" || name in declaredEntries }
            .forEach { (name, _) -> warnings += "未登记文件：$name" }
        return BackupScanResult(false, mergedManifest, warnings)
    }

    private fun validateCategories(
        manifest: BackupManifest,
        declaredEntries: Set<String>,
        warnings: MutableList<String>,
    ) {
        val categoryEntries = if (manifest.technical.categoryFiles.isNotEmpty()) {
            manifest.technical.categoryFiles.values.flatten()
        } else {
            manifest.categories.flatMap { it.files }
        }
        if (manifest.categories.map { it.id }.distinct().size != manifest.categories.size) {
            warnings += "类别清单存在重复类别"
        }
        categoryEntries.filter { it !in declaredEntries }.forEach {
            warnings += "类别清单引用未声明文件：$it"
        }
        if (categoryEntries.toSet().size != categoryEntries.size) {
            warnings += "类别清单存在重复文件"
        }
        val resourceEntries = manifest.resources.map { it.name }.toSet()
        declaredEntries.filterNot { it in categoryEntries || it in resourceEntries }.forEach {
            warnings += "文件未归入任何类别：$it"
        }
    }

    private fun BackupManifest.withTechnicalCategoryIds(): BackupManifest {
        if (categories.all { it.id.isNotBlank() } || technical.categoryLabels.isEmpty()) return this
        return copy(
            categories = categories.map { category ->
                if (category.id.isNotBlank()) category else category.copy(
                    id = technical.categoryLabels.entries
                        .firstOrNull { (_, label) -> label == category.name }
                        ?.key
                        .orEmpty(),
                )
            },
        )
    }

    private fun BackupManifest.withTechnicalSensitiveCategoryIds(): BackupManifest {
        if (sensitiveCategories.all { it.id.isNotBlank() } || technical.sensitiveCategoryLabels.isEmpty()) return this
        return copy(
            sensitiveCategories = sensitiveCategories.map { category ->
                if (category.id.isNotBlank()) category else category.copy(
                    id = technical.sensitiveCategoryLabels.entries
                        .firstOrNull { (_, label) -> label == category.name }
                        ?.key
                        .orEmpty(),
                )
            },
        )
    }

    private fun validateResourceAliases(
        manifest: BackupManifest,
        declaredEntries: Set<String>,
        warnings: MutableList<String>,
    ) {
        manifest.resourceAliases.forEach { (alias, target) ->
            if (alias.isBlank()) warnings += "资源别名为空"
            if (target !in declaredEntries || target !in manifest.resources.map { it.name }) {
                warnings += "资源别名指向未声明资源：$alias -> $target"
            }
        }
    }

    private fun validateCategoryCounts(
        manifest: BackupManifest,
        warnings: MutableList<String>,
    ) {
        val counts = manifest.technical.categoryCounts.ifEmpty {
            manifest.categories.associate { it.id to it.count }
        }
        counts.forEach { (category, count) ->
            if (count < 0) warnings += "类别数量无效：$category=$count"
        }
    }

    private fun validateSensitiveItems(
        root: File?,
        manifest: BackupManifest,
        declaredEntries: Set<String>,
        warnings: MutableList<String>,
    ) {
        manifest.sensitiveItems.forEach { item ->
            if (item.isBlank()) warnings += "敏感项目标识为空"
        }
        val sensitiveFiles = mapOf(
            "SERVER_CREDENTIALS" to "servers.json",
            "BOOK_SOURCE_AUTH" to "bookSource.json",
            "REQUEST_HEADERS" to "bookSource.json",
            "RSS_AUTH" to "rssSources.json",
            "TTS_API_KEYS" to "httpTTS.json",
            "DIRECT_LINK_CREDENTIALS" to "directLinkUploadRule.json",
        )
        sensitiveFiles.forEach { (category, fileName) ->
            if (category !in manifest.sensitiveItems) return@forEach
            if (fileName !in declaredEntries) {
                warnings += "敏感类别未登记载荷：$category"
                return@forEach
            }
            val file = root?.let { File(it, fileName) }
            if (file?.isFile == true && !BackupSensitiveEnvelope.isEncrypted(file.readText())) {
                warnings += "敏感载荷未加密：$fileName"
            }
        }
    }

    private fun String.isSafeBackupEntry(): Boolean {
        if (isBlank() || startsWith("/") || contains('\\')) return false
        return split('/').none { it == ".." }
    }

    private fun validateEntry(
        root: File,
        name: String,
        size: Long,
        sha256: String,
        warnings: MutableList<String>,
    ) {
        val file = File(root, name)
        val rootPath = root.canonicalFile.toPath()
        val filePath = runCatching { file.canonicalFile.toPath() }.getOrNull()
        when {
            filePath == null || !filePath.startsWith(rootPath) -> warnings += "文件路径越界：$name"
            !file.isFile -> warnings += "缺少文件：$name"
            file.length() != size -> warnings += "文件大小不匹配：$name"
            file.sha256() != sha256 -> warnings += "文件校验失败：$name"
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
}
