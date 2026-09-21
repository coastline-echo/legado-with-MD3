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
    fun `explicit empty category never clears application implicitly`() {
        val input = RestoreCategoryInput("bookshelf", backupPresent = true, applicationItemIds = setOf("a"))
        assertEquals(false, RestoreCategoryPlanner.plan(input, RestoreMode.USE_BACKUP).clearApplication)
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

    @Test
    fun `category mode resolves each category independently`() {
        val plans = RestoreCategoryPlanner.planByCategory(
            inputs = listOf(
                RestoreCategoryInput(
                    category = "bookshelf",
                    backupPresent = true,
                    backupItemIds = setOf("backup"),
                    applicationItemIds = setOf("application"),
                ),
                RestoreCategoryInput(
                    category = "bookSource",
                    backupPresent = true,
                    backupItemIds = setOf("source"),
                    applicationItemIds = emptySet(),
                ),
            ),
            modes = mapOf(
                "bookshelf" to RestoreMode.KEEP_APPLICATION,
                "bookSource" to RestoreMode.USE_BACKUP,
            ),
        )
        assertEquals(setOf("backup"), plans.single { it.category == "bookshelf" }.importItemIds)
        assertEquals(setOf("source"), plans.single { it.category == "bookSource" }.importItemIds)
        assertEquals(emptySet<String>(), plans.single { it.category == "bookshelf" }.deleteItemIds)
    }

    @Test
    fun `merge plan keeps application-only identities`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput(
                category = "bookSource",
                backupPresent = true,
                backupItemIds = setOf("backup"),
                applicationItemIds = setOf("backup", "application-only"),
            ),
            RestoreMode.USE_BACKUP,
        )
        assertEquals(emptySet<String>(), plan.deleteItemIds)
        assertEquals(setOf("backup"), plan.importItemIds)
    }

    @Test
    fun `clear then restore is explicit and empty backup still does not clear`() {
        val dataPlan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput(
                category = "bookSource",
                backupPresent = true,
                backupItemIds = setOf("backup"),
                applicationItemIds = setOf("application-only"),
            ),
            RestoreMode.CLEAR_THEN_RESTORE,
        )
        assertEquals(true, dataPlan.clearApplication)
        assertEquals(setOf("backup"), dataPlan.importItemIds)

        val emptyPlan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput("bookSource", backupPresent = true),
            RestoreMode.CLEAR_THEN_RESTORE,
        )
        assertEquals(false, emptyPlan.clearApplication)
    }
}
