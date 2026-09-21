package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupRestoreCategoryCatalogTest {
    @Test
    fun `database categories have unique files and are included in all categories`() {
        assertEquals(
            BackupRestoreCategoryCatalog.databaseFiles.values.size,
            BackupRestoreCategoryCatalog.databaseFiles.values.toSet().size,
        )
        assertTrue(BackupRestoreCategoryCatalog.allCategories.containsAll(BackupRestoreCategoryCatalog.databaseFiles.keys))
        assertTrue(BackupRestoreCategoryCatalog.allCategories.containsAll(BackupRestoreCategoryCatalog.configurationCategories))
    }
}
