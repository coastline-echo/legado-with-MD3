package io.legado.app.help.storage

import io.legado.app.utils.GSON
import java.io.File
import java.security.MessageDigest

data class BackupScanResult(
    val legacyFormat: Boolean,
    val manifest: BackupManifest? = null,
    val warnings: List<String> = emptyList(),
)

/** 恢复写入前校验 ZIP 解压目录及清单中的文件。 */
object BackupManifestScanner {

    fun scan(root: File): BackupScanResult {
        val manifestFile = File(root, "manifest.json")
        if (!manifestFile.isFile) {
            return BackupScanResult(legacyFormat = true)
        }
        val manifest = runCatching { GSON.fromJson(manifestFile.readText(), BackupManifest::class.java) }
            .getOrElse { return BackupScanResult(false, warnings = listOf("manifest.json 解析失败：${it.localizedMessage}")) }
        val warnings = buildList {
            manifest.files.forEach { entry ->
                validateEntry(root, entry.name, entry.size, entry.sha256, this)
            }
            manifest.resources.forEach { entry ->
                validateEntry(root, entry.name, entry.size, entry.sha256, this)
            }
        }
        return BackupScanResult(false, manifest, warnings)
    }

    private fun validateEntry(root: File, name: String, size: Long, sha256: String, warnings: MutableList<String>) {
        val file = File(root, name)
        when {
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
