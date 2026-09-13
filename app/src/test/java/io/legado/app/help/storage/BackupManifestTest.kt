package io.legado.app.help.storage

import java.io.File
import java.nio.charset.StandardCharsets
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupManifestTest {

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
                categories = mapOf("settings" to listOf("config.xml")),
                ignoredCategories = setOf("theme"),
                configKeys = setOf("mainNavigationOrder"),
            )

            val entry = manifest.files.single()
            assertEquals("config.xml", entry.name)
            assertEquals(file.length(), entry.size)
            assertEquals(
                "9d9782cc006b3ec78fb4f02540d2af14008e9cb2f9d5b9463e2b00c3e943b7ce",
                entry.sha256,
            )
            assertEquals(listOf("config.xml"), manifest.categories["settings"])
            assertTrue("theme" in manifest.ignoredCategories)
            assertTrue("mainNavigationOrder" in manifest.configKeys)
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
}
