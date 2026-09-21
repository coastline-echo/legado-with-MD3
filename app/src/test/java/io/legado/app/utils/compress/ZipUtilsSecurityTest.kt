package io.legado.app.utils.compress

import java.io.File
import java.nio.file.Files
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import org.junit.Assert.assertFalse
import org.junit.Test

class ZipUtilsSecurityTest {

    @Test
    fun `zip entry cannot escape into sibling directory with shared prefix`() {
        val root = Files.createTempDirectory("zip-root").toFile()
        val sibling = File(requireNotNull(root.parentFile), "${root.name}2").apply { mkdirs() }
        val zip = File.createTempFile("zip-security", ".zip")
        try {
            ZipOutputStream(zip.outputStream()).use { output ->
                output.putNextEntry(ZipEntry("../${sibling.name}/escaped.txt"))
                output.write("should not be written".toByteArray())
                output.closeEntry()
            }

            runCatching { ZipUtils.unZipToPath(zip, root) }
            assertFalse(File(sibling, "escaped.txt").exists())
        } finally {
            zip.delete()
            root.deleteRecursively()
            sibling.deleteRecursively()
        }
    }
}
