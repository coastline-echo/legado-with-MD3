package io.legado.app.help.storage

import io.legado.app.domain.model.BackupRestoreOutcome
import io.legado.app.domain.model.BackupRestoreResult
import org.junit.Assert.assertEquals
import org.junit.Test

class BackupRestoreResultBusTest {

    @Test
    fun `results stay isolated by operation id`() {
        val first = BackupRestoreResult(
            operationId = "backup-operation-1",
            outcome = BackupRestoreOutcome.FAILED,
            failures = 1,
        )
        val second = BackupRestoreResult(
            operationId = "backup-operation-2",
            outcome = BackupRestoreOutcome.SUCCESS,
        )
        try {
            BackupRestoreResultBus.emit(first)
            BackupRestoreResultBus.emit(second)

            assertEquals(first, BackupRestoreResultBus.getResult(first.operationId))
            assertEquals(second, BackupRestoreResultBus.getResult(second.operationId))
        } finally {
            BackupRestoreResultBus.removeResult(first.operationId)
            BackupRestoreResultBus.removeResult(second.operationId)
        }
    }
}
