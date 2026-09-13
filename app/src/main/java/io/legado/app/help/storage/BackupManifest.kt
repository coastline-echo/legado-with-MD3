package io.legado.app.help.storage

import java.io.File
import java.io.FileInputStream
import java.security.MessageDigest

/** 新版备份 ZIP 的结构清单。清单不保存敏感字段原文。 */
data class BackupManifest(
    val formatVersion: Int = 1,
    val appVersion: String = "",
    val createdAt: Long,
    val categories: Map<String, List<String>> = emptyMap(),
    val files: List<BackupManifestFile> = emptyList(),
    val resources: List<BackupManifestResource> = emptyList(),
    val resourceAliases: Map<String, String> = emptyMap(),
    val configKeys: Set<String> = emptySet(),
    val ignoredConfigKeys: Set<String> = emptySet(),
    val unsupportedConfigKeys: Set<String> = emptySet(),
    val notSetConfigKeys: Set<String> = emptySet(),
    val ignoredCategories: Set<String> = emptySet(),
    val sensitiveIncluded: Boolean = false,
    val sensitiveItems: Set<String> = emptySet(),
    val weakProtection: Boolean = false,
    val legacyFormat: Boolean = false,
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

object BackupManifestBuilder {

    fun build(
        root: File,
        files: Collection<File>,
        categories: Map<String, List<String>> = emptyMap(),
        ignoredCategories: Set<String> = emptySet(),
        configKeys: Set<String> = emptySet(),
        ignoredConfigKeys: Set<String> = emptySet(),
        unsupportedConfigKeys: Set<String> = emptySet(),
        notSetConfigKeys: Set<String> = emptySet(),
        resources: Collection<File> = emptyList(),
        resourceAliases: Map<String, String> = emptyMap(),
        appVersion: String = "",
        sensitiveIncluded: Boolean = false,
        sensitiveItems: Set<String> = emptySet(),
        weakProtection: Boolean = false,
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
            categories = categories.mapValues { (_, names) -> names.sorted() },
            files = fileEntries,
            resources = resourceEntries,
            resourceAliases = resourceAliases.toSortedMap(),
            configKeys = configKeys.toSortedSet(),
            ignoredConfigKeys = ignoredConfigKeys.toSortedSet(),
            unsupportedConfigKeys = unsupportedConfigKeys.toSortedSet(),
            notSetConfigKeys = notSetConfigKeys.toSortedSet(),
            ignoredCategories = ignoredCategories.toSortedSet(),
            sensitiveIncluded = sensitiveIncluded,
            sensitiveItems = sensitiveItems.toSortedSet(),
            weakProtection = weakProtection,
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
