package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class ImportOldDataResultTest {

    @Test
    fun `import result keeps processed imported failed and skipped counts`() {
        val result = ImportOldData.ImportResult(
            processedFiles = 2,
            importedItems = 7,
            failedFiles = 1,
            skippedFiles = 3,
        )
        assertEquals(2, result.processedFiles)
        assertEquals(7, result.importedItems)
        assertEquals(1, result.failedFiles)
        assertEquals(3, result.skippedFiles)
    }
}
