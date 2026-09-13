package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupResourceReferenceTest {

    @Test
    fun `old absolute reference resolves by stable file name`() {
        assertEquals(
            "/files/bg/hash.png",
            BackupResourceReference.localPath(
                "C:/old-device/theme.png",
                mapOf("theme.png" to "assets/background/hash.png"),
                "/files/bg",
            ),
        )
    }
}
