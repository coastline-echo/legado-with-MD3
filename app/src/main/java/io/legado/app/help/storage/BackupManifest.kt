package io.legado.app.help.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 备份 ZIP 清单和完整性文件的格式标识。 */
const val BACKUP_MANIFEST_FORMAT_VERSION = 1
const val BACKUP_INTEGRITY_FORMAT_VERSION = 1

/** 当前分支备份 ZIP 的唯一结构清单；清单不保存敏感字段原文。 */
data class BackupManifest(
    val formatVersion: Int = BACKUP_MANIFEST_FORMAT_VERSION,
    val appVersion: String = "",
    val createdAt: Long,
    /** 类别、中文名称、状态和文件集中在同一项，避免平行映射产生矛盾。 */
    val categories: List<BackupManifestCategory> = emptyList(),
    val configItems: List<BackupManifestConfigItem> = emptyList(),
    val sensitiveCategories: List<BackupManifestSensitiveCategory> = emptyList(),
    val integrity: BackupIntegrity = BackupIntegrity(),
    val technical: BackupManifestTechnical = BackupManifestTechnical(),
)

val BackupManifest.files: List<BackupManifestFile> get() = integrity.files
val BackupManifest.resources: List<BackupManifestResource> get() = integrity.resources
val BackupManifest.resourceAliases: Map<String, String> get() = integrity.resourceAliases
val BackupManifest.ignoredCategories: Set<String> get() = technical.ignoredCategories
val BackupManifest.restoreIgnoredCategories: Set<String> get() = technical.restoreIgnoredCategories
val BackupManifest.sensitiveIncluded: Boolean get() = technical.sensitiveIncluded
val BackupManifest.sensitiveItems: Set<String> get() = technical.sensitiveItems
val BackupManifest.weakProtection: Boolean get() = technical.weakProtection
val BackupManifest.warnings: List<String> get() = technical.warnings

/** 清单中的恢复辅助数据，与用户可读项目分开存放。 */
data class BackupManifestTechnical(
    val configItems: List<BackupManifestConfigItem> = emptyList(),
    /** 用户清单不展示的稳定类别标识，仅用于恢复定位。 */
    val categoryIds: Map<String, String> = emptyMap(),
    val sensitiveCategoryIds: Map<String, String> = emptyMap(),
    /** 类别与 ZIP 文件的归属关系，避免恢复时按文件名猜类别。 */
    val categoryFiles: Map<String, List<String>> = emptyMap(),
    val ignoredCategories: Set<String> = emptySet(),
    val restoreIgnoredCategories: Set<String> = emptySet(),
    val sensitiveIncluded: Boolean = false,
    val sensitiveItems: Set<String> = emptySet(),
    val weakProtection: Boolean = false,
    val warnings: List<String> = emptyList(),
)

/** integrity.json 的专用模型，只保存完整性校验和资源恢复数据。 */
data class BackupIntegrity(
    val integrityVersion: Int = BACKUP_INTEGRITY_FORMAT_VERSION,
    val manifestSha256: String = "",
    val files: List<BackupManifestFile> = emptyList(),
    val resources: List<BackupManifestResource> = emptyList(),
    val resourceAliases: Map<String, String> = emptyMap(),
    val recovery: BackupManifestTechnical = BackupManifestTechnical(),
)

data class BackupManifestCategory(
    val id: String = "",
    val name: String = "",
    val status: String = "",
    val detail: String = "",
    val count: Int = 0,
    val files: List<String> = emptyList(),
)

data class BackupManifestSensitiveCategory(
    val id: String = "",
    val name: String = "",
    val status: String = "",
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
    val key: String? = null,
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
        val sortedConfigItems = configItems.sortedWith(
            compareBy<BackupManifestConfigItem>(
                { it.status != "写入失败" && it.status != "不支持" },
                { it.name },
                { it.key },
            )
        )
        return BackupManifest(
            createdAt = System.currentTimeMillis(),
            appVersion = appVersion,
            categories = categories.toList(),
            sensitiveCategories = sensitiveCategories.toList(),
            configItems = sortedConfigItems.map { BackupManifestConfigItem(name = it.name, status = it.status) },
            integrity = BackupIntegrity(
                files = fileEntries,
                resources = resourceEntries,
                resourceAliases = resourceAliases.toSortedMap(),
            ),
            technical = BackupManifestTechnical(
                configItems = sortedConfigItems,
                categoryIds = categories.associate { it.name to it.id },
                sensitiveCategoryIds = sensitiveCategories.associate { it.name to it.id },
                categoryFiles = categories.associate { it.id to it.files },
                ignoredCategories = ignoredCategories.toSortedSet(),
                restoreIgnoredCategories = restoreIgnoredCategories.toSortedSet(),
                sensitiveIncluded = sensitiveIncluded,
                sensitiveItems = sensitiveItems.toSortedSet(),
                weakProtection = weakProtection,
                warnings = warnings.toSortedSet().toList(),
            ),
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
