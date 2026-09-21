package io.legado.app.utils

import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FileUtilsAtomicTest {

    @Test
    fun `failed atomic copy keeps the existing configuration`() {
        val directory = Files.createTempDirectory("legado-atomic-copy").toFile()
        try {
            val target = directory.resolve("config.json").apply { writeText("old") }
            val missingSource = directory.resolve("missing.json")

            val result = runCatching {
                FileUtils.copyFileAtomic(missingSource, target.absolutePath)
            }

            assertTrue(result.isFailure)
            assertEquals("old", target.readText())
        } finally {
            directory.deleteRecursively()
        }
    }
}
