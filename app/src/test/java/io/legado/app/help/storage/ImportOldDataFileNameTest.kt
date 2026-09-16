package io.legado.app.help.storage

import org.junit.Assert.assertTrue
import org.junit.Test

class ImportOldDataFileNameTest {

    @Test
    fun `legacy import recognizes the three standalone file names`() {
        assertTrue(ImportOldData.legacyFileNames.contains("myBookShelf.json"))
        assertTrue(ImportOldData.legacyFileNames.contains("myBookSource.json"))
        assertTrue(ImportOldData.legacyFileNames.contains("myBookReplaceRule.json"))
    }
}
