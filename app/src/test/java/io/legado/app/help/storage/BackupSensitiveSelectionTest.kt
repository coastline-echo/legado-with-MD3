package io.legado.app.help.storage

import io.legado.app.domain.model.BackupSensitiveCategory
import io.legado.app.domain.model.BackupSensitiveSelection
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupSensitiveSelectionTest {

    @Test
    fun `new selection keeps legacy compatibility by selecting every category`() {
        val selection = BackupSensitiveSelection()
        assertTrue(selection.includes(BackupSensitiveCategory.WEBDAV_CREDENTIALS))
        assertTrue(selection.includes(BackupSensitiveCategory.SERVER_CREDENTIALS))
    }

    @Test
    fun `category can be deselected independently`() {
        val selection = BackupSensitiveSelection()
            .toggle(BackupSensitiveCategory.RSS_AUTH)
        assertFalse(selection.includes(BackupSensitiveCategory.RSS_AUTH))
        assertTrue(selection.includes(BackupSensitiveCategory.TTS_API_KEYS))
    }
}
