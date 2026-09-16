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

    fun scanZip(input: InputStream): BackupScanResult {
        val entries = linkedMapOf<String, Pair<Long, String>>()
        var manifest: BackupManifest? = null
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
                    var size = 0L
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var count = zip.read(buffer)
                    while (count >= 0) {
                        if (count > 0) {
                            digest.update(buffer, 0, count)
                            manifestOutput?.write(buffer, 0, count)
                            size += count
                        }
                        count = zip.read(buffer)
                    }
                    entries[entry.name] = size to digest.digest().joinToString("") { "%02x".format(it) }
                    if (manifestOutput != null) {
                        manifestSeen = true
                        manifest = runCatching {
                            io.legado.app.utils.GSON.fromJson(
                                manifestOutput.toString(Charsets.UTF_8),
                                BackupManifest::class.java,
                            )
                        }.onFailure {
                            manifestWarning = "manifest.json 解析失败：${it.localizedMessage}"
                        }.getOrNull()
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
        if (manifest == null) {
            return BackupScanResult(false, warnings = warnings.ifEmpty { listOf("manifest.json 解析失败") })
        }
        if (manifest.formatVersion <= 0) warnings += "manifest.json 格式版本无效"
        if (manifest.createdAt <= 0L) warnings += "manifest.json 创建时间无效"
        if (manifest.appVersion.isBlank()) warnings += "manifest.json 缺少应用版本"
        val declaredEntries = (manifest.files.map { it.name } + manifest.resources.map { it.name }).toSet()
        if (declaredEntries.size != manifest.files.size + manifest.resources.size) {
            warnings += "manifest.json 存在重复文件声明"
        }
        validateCategories(manifest, declaredEntries, warnings)
        validateResourceAliases(manifest, declaredEntries, warnings)
        validateSensitiveItems(manifest, warnings)
        (manifest.files.map { Triple(it.name, it.size, it.sha256) } +
            manifest.resources.map { Triple(it.name, it.size, it.sha256) }).forEach { triple ->
            val (name, size, hash) = triple
            val actual = entries[name]
            if (actual == null) warnings += "缺少文件：$name"
            else {
                if (actual.first != size) warnings += "文件大小不匹配：$name"
                if (actual.second != hash) warnings += "文件校验失败：$name"
            }
        }
        entries.keys
            .filterNot { it == "manifest.json" || it in declaredEntries }
            .forEach { warnings += "未登记文件：$it" }
        return BackupScanResult(false, manifest, warnings)
    }

    fun scan(root: File): BackupScanResult {
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) {
            return BackupScanResult(legacyFormat = true)
        }
        val manifest = runCatching { GSON.fromJson(manifestFile.readText(), BackupManifest::class.java) }
            .getOrElse {
                return BackupScanResult(
                    false,
                    warnings = listOf("manifest.json 解析失败：${it.localizedMessage}"),
                )
            }
        val warnings = mutableListOf<String>()
        if (manifest.formatVersion <= 0) warnings += "manifest.json 格式版本无效"
        if (manifest.createdAt <= 0L) warnings += "manifest.json 创建时间无效"
        if (manifest.appVersion.isBlank()) warnings += "manifest.json 缺少应用版本"
        manifest.files.forEach { entry ->
            validateEntry(root, entry.name, entry.size, entry.sha256, warnings)
        }
        manifest.resources.forEach { entry ->
            validateEntry(root, entry.name, entry.size, entry.sha256, warnings)
        }
        val declaredEntries = (manifest.files.map { it.name } + manifest.resources.map { it.name }).toSet()
        if (declaredEntries.size != manifest.files.size + manifest.resources.size) {
            warnings += "manifest.json 存在重复文件声明"
        }
        validateCategories(manifest, declaredEntries, warnings)
        validateResourceAliases(manifest, declaredEntries, warnings)
        validateSensitiveItems(manifest, warnings)
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
            .filterNot { (name, _) -> name == "manifest.json" || name in declaredEntries }
            .forEach { (name, _) -> warnings += "未登记文件：$name" }
        return BackupScanResult(false, manifest, warnings)
    }

    private fun validateCategories(
        manifest: BackupManifest,
        declaredEntries: Set<String>,
        warnings: MutableList<String>,
    ) {
        val categoryEntries = manifest.categories.values.flatten()
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

    private fun validateSensitiveItems(
        manifest: BackupManifest,
        warnings: MutableList<String>,
    ) {
        manifest.sensitiveItems.forEach { item ->
            if (item.isBlank()) warnings += "敏感项目标识为空"
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
