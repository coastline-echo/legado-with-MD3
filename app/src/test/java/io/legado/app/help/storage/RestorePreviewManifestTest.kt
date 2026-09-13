package io.legado.app.help.storage

import io.legado.app.domain.model.RestorePreview
import org.junit.Assert.assertTrue
import org.junit.Test

class RestorePreviewManifestTest {

    @Test
    fun `manifest sensitive metadata is represented in preview model`() {
        val preview = RestorePreview(
            legacyFormat = false,
            fileCount = 1,
            resourceCount = 0,
            categories = mapOf("settings" to 1),
            warnings = emptyList(),
            sensitiveIncluded = true,
            weakProtection = true,
        )
        assertTrue(preview.sensitiveIncluded)
        assertTrue(preview.weakProtection)
    }
}
