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

    @Test
    fun `required key present but not written is reported as write failed`() {
        val result = BackupConfigIntegrityChecker.check(
            snapshotKeys = setOf("required"),
            written = emptySet(),
            ignored = emptySet(),
            unsupported = emptySet(),
            requiredKeys = setOf("required"),
        )
        assertEquals(BackupConfigKeyStatus.WRITE_FAILED, result.status("required"))
    }

    @Test
    fun `required key ignored or unsupported keeps its specific status`() {
        val result = BackupConfigIntegrityChecker.check(
            snapshotKeys = setOf("ignored", "unsupported"),
            written = emptySet(),
            ignored = setOf("ignored"),
            unsupported = setOf("unsupported"),
            requiredKeys = setOf("ignored", "unsupported"),
        )
        assertEquals(BackupConfigKeyStatus.IGNORED, result.status("ignored"))
        assertEquals(BackupConfigKeyStatus.UNSUPPORTED, result.status("unsupported"))
    }

    @Test
    fun `overlapping statuses are normalized to one final status`() {
        val result = BackupConfigIntegrityChecker.check(
            snapshotKeys = setOf("written", "ignored", "unsupported", "failed"),
            written = setOf("written", "ignored"),
            ignored = setOf("ignored", "unsupported"),
            unsupported = setOf("unsupported"),
        )
        assertEquals(setOf("written", "ignored"), result.written)
        assertEquals(emptySet<String>(), result.ignored)
        assertEquals(setOf("unsupported"), result.unsupported)
        assertEquals(setOf("failed"), result.failed)
    }
}
