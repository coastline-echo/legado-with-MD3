package io.legado.app.help.storage

import java.io.File
import java.nio.charset.StandardCharsets
import io.legado.app.utils.GSON
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

    @Test
    fun `sensitive source identifier is stable without exposing the source url`() {
        val url = "https://user:secret@example.com/source"
        val first = stableSensitiveIdentifier(url)
        val second = stableSensitiveIdentifier(url)

        assertEquals(first, second)
        assertFalse(first.contains("secret"))
        assertFalse(first.contains("example.com"))
    }

    @Test
    fun `manifest records file size and sha256`() {
        val root = createTempDirectory("backup-manifest").toFile()
        try {
            val file = File(root, "config.xml").apply {
                writeText("<map />", StandardCharsets.UTF_8)
            }
            val manifest = BackupManifestBuilder.build(
                root = root,
                files = listOf(file),
                categories = listOf(
                    BackupManifestCategory("settings", "应用设置", "已写入", files = listOf("config.xml"))
                ),
                ignoredCategories = setOf("theme"),
                restoreIgnoredCategories = setOf("readConfig"),
                configItems = listOf(BackupManifestConfigItem("mainNavigationOrder", "底栏顺序", "已写入")),
            )

            val entry = manifest.files.single()
            assertEquals("config.xml", entry.name)
            assertEquals(file.length(), entry.size)
            assertEquals(
                "9d9782cc006b3ec78fb4f02540d2af14008e9cb2f9d5b9463e2b00c3e943b7ce",
                entry.sha256,
            )
            assertEquals(listOf("config.xml"), manifest.categories.single().files)
            assertTrue("theme" in manifest.ignoredCategories)
            assertTrue("readConfig" in manifest.restoreIgnoredCategories)
            assertEquals("mainNavigationOrder", manifest.configItems.single().key)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest builder skips files outside root`() {
        val root = createTempDirectory("backup-manifest").toFile()
        try {
            val outside = File(root.parentFile, "outside-${root.name}").apply { writeText("x") }
            val manifest = BackupManifestBuilder.build(root, listOf(outside))
            assertTrue(manifest.files.isEmpty())
            outside.delete()
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest records nested resource path separately`() {
        val root = createTempDirectory("backup-manifest").toFile()
        try {
            val resource = File(root, "assets/background/abc.png").apply {
                requireNotNull(parentFile).mkdirs()
                writeBytes(byteArrayOf(1, 2, 3))
            }
            val manifest = BackupManifestBuilder.build(root, emptyList(), resources = listOf(resource))
            assertEquals("assets/background/abc.png", manifest.resources.single().name)
            assertEquals(3L, manifest.resources.single().size)
            assertEquals(64, manifest.resources.single().sha256.length)
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `manifest keeps readable items together without duplicate key maps`() {
        val root = createTempDirectory("readable-manifest").toFile()
        try {
            val manifest = BackupManifestBuilder.build(
                root = root,
                files = emptyList(),
                categories = listOf(
                    BackupManifestCategory("settings", "应用设置", "未写入"),
                    BackupManifestCategory("bookshelf", "书架", "未写入"),
                ),
                configItems = listOf(
                    BackupManifestConfigItem("language", "语言", "未设置"),
                ),
            )
            val json = GSON.toJson(manifest)
            assertEquals(2, manifest.formatVersion)
            assertEquals(listOf("settings", "bookshelf"), manifest.categories.map { it.id })
            assertEquals("未设置", manifest.configItems.single().status)
            assertFalse(json.contains("configKeyLabels"))
            assertFalse(json.contains("configKeyStatus"))
            assertFalse(json.contains("coverageSources"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test
    fun `content resource alias does not expose original uri in manifest`() {
        val root = createTempDirectory("resource-alias").toFile()
        try {
            val original = "content://provider/private/image?id=1&token=test"
            val json = File(root, "readConfig.json").apply { writeText("{\"background\":\"$original\"}") }
            val unrelated = File(root, "bookSource.json").apply { writeText("{\"source\":\"$original\"}") }
            val xml = File(root, "config.xml").apply {
                writeText("<map><string name=\"background\">${original.replace("&", "&amp;")}</string></map>")
            }
            val aliases = redactResourceAliases(root, mapOf(original to "assets/background/test.png"))
            val marker = aliases.keys.single()
            assertTrue(marker.startsWith("legado-backup-resource:"))
            assertFalse(GSON.toJson(aliases).contains(original))
            assertTrue(json.readText().contains(marker))
            assertTrue(xml.readText().contains(marker))
            assertFalse(json.readText().contains(original))
            assertTrue(unrelated.readText().contains(original))
        } finally {
            root.deleteRecursively()
        }
    }
}
