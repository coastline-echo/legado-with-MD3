package io.legado.app.help.storage

import io.legado.app.domain.model.BackupRestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupOutcomeTest {
    @Test
    fun `webdav upload failure without local file is failed`() {
        assertEquals(
            BackupRestoreOutcome.FAILED,
            classifyBackupOutcome(false, true, true, 1),
        )
    }

    @Test
    fun `local file survives webdav failure`() {
        assertEquals(
            BackupRestoreOutcome.SUCCESS_WITH_WARNINGS,
            classifyBackupOutcome(true, true, true, 1),
        )
    }

    @Test
    fun `local and webdav success without warnings is success`() {
        assertEquals(
            BackupRestoreOutcome.SUCCESS,
            classifyBackupOutcome(true, true, false, 0),
        )
    }

    @Test
    fun `webdav only success does not require local file`() {
        assertEquals(
            BackupRestoreOutcome.SUCCESS,
            classifyBackupOutcome(false, true, false, 0),
        )
    }

    @Test
    fun `neither destination written is failed`() {
        assertEquals(
            BackupRestoreOutcome.FAILED,
            classifyBackupOutcome(false, false, true, 1),
        )
    }
}
