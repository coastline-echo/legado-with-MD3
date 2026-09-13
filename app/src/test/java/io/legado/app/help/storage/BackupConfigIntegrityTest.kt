package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class BackupConfigIntegrityTest {

    @Test
    fun `integrity distinguishes ignored unsupported and not set keys`() {
        val result = BackupConfigIntegrityChecker.check(
            snapshotKeys = setOf("written", "ignored", "unsupported"),
            written = setOf("written"),
            ignored = setOf("ignored"),
            unsupported = setOf("unsupported"),
            requiredKeys = setOf("written", "ignored", "unsupported", "missing"),
        )
        assertEquals(BackupConfigKeyStatus.WRITTEN, result.status("written"))
        assertEquals(BackupConfigKeyStatus.IGNORED, result.status("ignored"))
        assertEquals(BackupConfigKeyStatus.UNSUPPORTED, result.status("unsupported"))
        assertEquals(setOf("missing"), result.notSet)
    }
}
