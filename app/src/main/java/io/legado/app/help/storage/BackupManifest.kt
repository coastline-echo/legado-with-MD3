package io.legado.app.help.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 新版备份 ZIP 的结构清单。清单不保存敏感字段原文。 */
data class BackupManifest(
    val formatVersion: Int = 2,
    val appVersion: String = "",
    val createdAt: Long,
    /** 类别、中文名称、状态和文件集中在同一项，避免平行映射产生矛盾。 */
    val categories: List<BackupManifestCategory> = emptyList(),
    val configItems: List<BackupManifestConfigItem> = emptyList(),
    val sensitiveCategories: List<BackupManifestSensitiveCategory> = emptyList(),
    val files: List<BackupManifestFile> = emptyList(),
    val resources: List<BackupManifestResource> = emptyList(),
    val resourceAliases: Map<String, String> = emptyMap(),
    val ignoredCategories: Set<String> = emptySet(),
    val restoreIgnoredCategories: Set<String> = emptySet(),
    val sensitiveIncluded: Boolean = false,
    val sensitiveItems: Set<String> = emptySet(),
    val weakProtection: Boolean = false,
    val warnings: List<String> = emptyList(),
)

data class BackupManifestCategory(
    val id: String,
    val name: String,
    val status: String,
    val detail: String = "",
    val count: Int = 0,
    val files: List<String> = emptyList(),
)

data class BackupManifestSensitiveCategory(
    val id: String,
    val name: String,
    val status: String,
)

data class BackupManifestFile(
    val name: String,
    val size: Long,
    val sha256: String,
)

data class BackupManifestResource(
    val name: String,
    val sha256: String,
    val size: Long,
)

data class BackupManifestConfigItem(
    val key: String,
    val name: String,
    val status: String,
)

object BackupManifestBuilder {

    fun build(
        root: File,
        files: Collection<File>,
        categories: Collection<BackupManifestCategory> = emptyList(),
        sensitiveCategories: Collection<BackupManifestSensitiveCategory> = emptyList(),
        ignoredCategories: Set<String> = emptySet(),
        restoreIgnoredCategories: Set<String> = emptySet(),
        configItems: Collection<BackupManifestConfigItem> = emptyList(),
        resources: Collection<File> = emptyList(),
        resourceAliases: Map<String, String> = emptyMap(),
        appVersion: String = "",
        sensitiveIncluded: Boolean = false,
        sensitiveItems: Set<String> = emptySet(),
        weakProtection: Boolean = false,
        warnings: List<String> = emptyList(),
    ): BackupManifest {
        val rootPath = root.canonicalFile.toPath()
        val fileEntries = files.mapNotNull { file ->
            val canonical = file.canonicalFile
            if (!canonical.isFile || !canonical.toPath().startsWith(rootPath)) return@mapNotNull null
            BackupManifestFile(
                name = rootPath.relativize(canonical.toPath()).toString().replace(File.separatorChar, '/'),
                size = canonical.length(),
                sha256 = canonical.sha256(),
            )
        }.sortedBy { it.name }
        val resourceEntries = resources.mapNotNull { file ->
            val canonical = file.canonicalFile
            if (!canonical.isFile || !canonical.toPath().startsWith(rootPath)) return@mapNotNull null
            BackupManifestResource(
                name = rootPath.relativize(canonical.toPath()).toString().replace(File.separatorChar, '/'),
                sha256 = canonical.sha256(),
                size = canonical.length(),
            )
        }.sortedBy { it.name }
        return BackupManifest(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            categories = categories.toList(),
            sensitiveCategories = sensitiveCategories.toList(),
            files = fileEntries,
            resources = resourceEntries,
            resourceAliases = resourceAliases.toSortedMap(),
            configItems = configItems.sortedWith(
                compareBy<BackupManifestConfigItem>(
                    { it.status != "写入失败" && it.status != "不支持" },
                    { it.name },
                    { it.key },
                )
            ),
            ignoredCategories = ignoredCategories.toSortedSet(),
            restoreIgnoredCategories = restoreIgnoredCategories.toSortedSet(),
            sensitiveIncluded = sensitiveIncluded,
            sensitiveItems = sensitiveItems.toSortedSet(),
            weakProtection = weakProtection,
            warnings = warnings.toSortedSet().toList(),
        )
    }

    private fun File.sha256(): String {
        val digest = MessageDigest.getInstance("SHA-256")
        FileInputStream(this).use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            var count = input.read(buffer)
            while (count >= 0) {
                if (count > 0) digest.update(buffer, 0, count)
                count = input.read(buffer)
            }
        }
        return digest.digest().joinToString("") { byte -> "%02x".format(byte) }
    }
}
