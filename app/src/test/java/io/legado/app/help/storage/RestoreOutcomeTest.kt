package io.legado.app.help.storage

import io.legado.app.domain.model.BackupRestoreOutcome
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreOutcomeTest {
    @Test
    fun `all attempted categories failing is failed`() {
        assertEquals(
            BackupRestoreOutcome.FAILED,
            classifyRestoreOutcome(setOf("bookshelf", "settings"), setOf("bookshelf", "settings"), false, false),
        )
    }

    @Test
    fun `some attempted categories failing is partial`() {
        assertEquals(
            BackupRestoreOutcome.PARTIAL,
            classifyRestoreOutcome(setOf("bookshelf"), setOf("bookshelf", "settings"), false, false),
        )
    }

    @Test
    fun `no failures with warnings is success with warnings`() {
        assertEquals(
            BackupRestoreOutcome.SUCCESS_WITH_WARNINGS,
            classifyRestoreOutcome(emptySet(), setOf("settings"), true, false),
        )
    }
}
