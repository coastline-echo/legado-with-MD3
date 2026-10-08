package io.legado.app.help.storage

import io.legado.app.utils.GSON
import java.nio.file.Files
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestScannerTest {

    @Test
    fun `legacy directory without manifest is accepted`() {
        val root = Files.createTempDirectory("legacy-backup").toFile()
        try {
            val result = BackupManifestScanner.scan(root)
            assertTrue(result.legacyFormat)
            assertTrue(result.warnings.isEmpty())
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `tampered file is reported`() {
        val root = Files.createTempDirectory("manifest-backup").toFile()
        try {
            val file = root.resolve("bookshelf.json").apply { writeText("[]") }
            val manifest = BackupManifestBuilder.build(root, listOf(file))
            root.resolve("manifest.json").writeText(GSON.toJson(manifest))
            file.writeText("[tampered]")
            assertTrue(BackupManifestScanner.scan(root).warnings.any {
                it.contains("校验失败") || it.contains("大小不匹配")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip scan reports tampered entry before restore`() {
        val root = Files.createTempDirectory("manifest-zip").toFile()
        try {
            val source = root.resolve("bookshelf.json").apply { writeText("[]") }
            val manifest = BackupManifestBuilder.build(root, listOf(source))
            val zip = root.resolve("backup.zip")
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                output.putNextEntry(ZipEntry("bookshelf.json"))
                output.write("[tampered]".toByteArray())
                output.closeEntry()
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(GSON.toJson(manifest).toByteArray())
                output.closeEntry()
            }
            val result = BackupManifestScanner.scanZip(zip.inputStream())
            assertTrue(result.warnings.any {
                it.contains("校验失败") || it.contains("大小不匹配")
            })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `real zip with encrypted sensitive payload passes preflight`() {
        val root = Files.createTempDirectory("sensitive-zip").toFile()
        try {
            val payload = root.resolve("servers.json").apply {
                // 预检只验证新信封标记；密码解密属于恢复阶段契约。
                writeText("LEGADO_SENSITIVE_V1:ciphertext")
            }
            val manifest = BackupManifestBuilder.build(
                root = root,
                files = listOf(payload),
                categories = listOf(
                    BackupManifestCategory("server", "服务器", "已写入", files = listOf("servers.json"))
                ),
                sensitiveIncluded = true,
                sensitiveItems = setOf("SERVER_CREDENTIALS"),
                appVersion = "test",
            )
            root.resolve("manifest.json").writeText(GSON.toJson(manifest))
            val zip = root.resolve("backup.zip")
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                root.resolve("servers.json").inputStream().use { input ->
                    output.putNextEntry(ZipEntry("servers.json"))
                    input.copyTo(output)
                    output.closeEntry()
                }
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(root.resolve("manifest.json").readBytes())
                output.closeEntry()
            }
            val result = BackupManifestScanner.scanZip(zip.inputStream())
            assertTrue(result.isUsable)
            assertTrue(result.warnings.none { it.contains("敏感载荷未加密") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid manifest is a failed new backup rather than a legacy backup`() {
        val root = Files.createTempDirectory("invalid-manifest").toFile()
        try {
            root.resolve("manifest.json").writeText("{invalid")
            val result = BackupManifestScanner.scan(root)
            assertFalse(result.legacyFormat)
            assertFalse(result.isUsable)
            assertTrue(result.warnings.any { it.contains("解析失败") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `blank sensitive item identifier is reported`() {
        val root = Files.createTempDirectory("sensitive-id").toFile()
        try {
            root.resolve("manifest.json").writeText(
                GSON.toJson(
                    BackupManifest(
                        createdAt = 1L,
                        appVersion = "1",
                        sensitiveItems = setOf(""),
                    ),
                ),
            )
            val result = BackupManifestScanner.scan(root)
            assertTrue(result.warnings.any { it.contains("敏感项目标识为空") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sensitive category without encrypted payload is reported`() {
        val root = Files.createTempDirectory("sensitive-payload").toFile()
        try {
            val file = root.resolve("servers.json").apply { writeText("[]") }
            val manifest = BackupManifestBuilder.build(
                root = root,
                files = listOf(file),
                categories = listOf(
                    BackupManifestCategory("server", "服务器", "已写入", files = listOf("servers.json"))
                ),
                sensitiveItems = setOf("SERVER_CREDENTIALS"),
            )
            root.resolve("manifest.json").writeText(GSON.toJson(manifest))
            assertTrue(BackupManifestScanner.scan(root).warnings.any { it.contains("敏感载荷未加密") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `sensitive category without payload is reported`() {
        val root = Files.createTempDirectory("missing-sensitive-payload").toFile()
        try {
            root.resolve("manifest.json").writeText(
                GSON.toJson(
                    BackupManifest(
                        createdAt = 1L,
                        appVersion = "1",
                        sensitiveItems = setOf("SERVER_CREDENTIALS"),
                    ),
                ),
            )
            assertTrue(BackupManifestScanner.scan(root).warnings.any { it.contains("敏感类别未登记载荷") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `invalid category counts are reported`() {
        val root = Files.createTempDirectory("category-counts").toFile()
        try {
            root.resolve("manifest.json").writeText(
                GSON.toJson(
                    BackupManifest(
                        createdAt = 1L,
                        appVersion = "1",
                        categories = listOf(
                            BackupManifestCategory("bookshelf", "书架", "已写入", count = -1),
                            BackupManifestCategory("bookshelf", "书架", "已写入", files = listOf("missing.json")),
                        ),
                    ),
                ),
            )
            val warnings = BackupManifestScanner.scan(root).warnings
            assertTrue(warnings.any { it.contains("类别数量无效") })
            assertTrue(warnings.any { it.contains("重复类别") })
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `empty category list remains valid`() {
        val root = Files.createTempDirectory("legacy-category-counts").toFile()
        try {
            root.resolve("manifest.json").writeText(
                GSON.toJson(BackupManifest(createdAt = 1L, appVersion = "1")),
            )
            assertTrue(BackupManifestScanner.scan(root).manifest?.categories?.isEmpty() == true)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `zip scan reports entries missing from manifest`() {
        val root = Files.createTempDirectory("extra-entry").toFile()
        try {
            val zip = root.resolve("backup.zip")
            ZipOutputStream(FileOutputStream(zip)).use { output ->
                output.putNextEntry(ZipEntry("manifest.json"))
                output.write(
                    GSON.toJson(
                        BackupManifest(
                            createdAt = 1L,
                            files = emptyList(),
                            resources = emptyList(),
                        ),
                    ).toByteArray(),
                )
                output.closeEntry()
                output.putNextEntry(ZipEntry("unexpected.json"))
                output.write("{}".toByteArray())
                output.closeEntry()
            }
            val result = BackupManifestScanner.scanZip(zip.inputStream())
            assertTrue(result.warnings.any { it.contains("未登记") && it.contains("unexpected.json") })
        } finally {
            root.deleteRecursively()
        }
    }

}
