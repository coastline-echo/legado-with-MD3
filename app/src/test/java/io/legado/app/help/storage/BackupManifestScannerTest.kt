package io.legado.app.help.storage

import io.legado.app.utils.GSON
import java.nio.file.Files
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestScannerTest {

    @Test
    fun `legacy directory without manifest is accepted`() {
        val root = Files.createTempDirectory("legacy-backup").toFile()
        try {
            assertTrue(BackupManifestScanner.scan(root).legacyFormat)
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
}
