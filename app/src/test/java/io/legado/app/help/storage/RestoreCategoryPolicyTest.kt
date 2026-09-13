package io.legado.app.help.storage

import io.legado.app.domain.model.RestoreMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class RestoreCategoryPolicyTest {

    @Test
    fun `missing category never changes application`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput("bookshelf", backupPresent = false, applicationItemIds = setOf("a")),
            RestoreMode.USE_BACKUP,
        )
        assertFalse(plan.clearApplication)
        assertEquals(emptySet<String>(), plan.deleteItemIds)
    }

    @Test
    fun `explicit empty category clears only in overwrite mode`() {
        val input = RestoreCategoryInput("bookshelf", backupPresent = true, applicationItemIds = setOf("a"))
        assertEquals(true, RestoreCategoryPlanner.plan(input, RestoreMode.USE_BACKUP).clearApplication)
        assertEquals(false, RestoreCategoryPlanner.plan(input, RestoreMode.KEEP_APPLICATION).clearApplication)
    }

    @Test
    fun `ignore takes precedence over category mode`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput("bookshelf", backupPresent = true, backupItemIds = setOf("b"), ignored = true),
            RestoreMode.USE_BACKUP,
        )
        assertEquals(true, plan.skippedByIgnore)
        assertEquals(emptySet<String>(), plan.importItemIds)
    }
}
