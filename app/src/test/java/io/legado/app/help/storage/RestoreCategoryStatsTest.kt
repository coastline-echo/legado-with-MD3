package io.legado.app.help.storage

import io.legado.app.domain.model.RestoreMode
import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreCategoryStatsTest {

    @Test
    fun `use backup counts stable identities as inserted and updated`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput(
                category = "bookshelf",
                backupPresent = true,
                backupItemIds = setOf("existing", "new"),
                applicationItemIds = setOf("existing"),
            ),
            RestoreMode.USE_BACKUP,
        )

        assertEquals(1, plan.toStats(failed = 1).inserted)
        assertEquals(1, plan.toStats(failed = 1).updated)
        assertEquals(1, plan.toStats(failed = 1).failed)
    }

    @Test
    fun `ignored category reports backup identities as skipped`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput(
                category = "bookSource",
                backupPresent = true,
                backupItemIds = setOf("one", "two"),
                ignored = true,
            ),
            RestoreMode.USE_BACKUP,
        )

        assertEquals(2, plan.toStats().skipped)
        assertEquals(0, plan.toStats().inserted)
    }

    @Test
    fun `keep application counts existing identities as updated`() {
        val plan = RestoreCategoryPlanner.plan(
            RestoreCategoryInput(
                category = "bookshelf",
                backupPresent = true,
                backupItemIds = setOf("existing", "new"),
                applicationItemIds = setOf("existing"),
            ),
            RestoreMode.KEEP_APPLICATION,
        )

        val stats = plan.toStats()
        assertEquals(1, stats.inserted)
        assertEquals(1, stats.updated)
        assertEquals(0, stats.skipped)
    }
}
