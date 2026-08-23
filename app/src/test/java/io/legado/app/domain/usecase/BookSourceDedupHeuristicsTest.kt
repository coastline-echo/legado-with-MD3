package io.legado.app.domain.usecase

import io.legado.app.data.entities.BookSource
import org.junit.Assert.assertEquals
import org.junit.Test

class BookSourceDedupHeuristicsTest {

    @Test
    fun `rule shape only uses configured rule sections`() {
        assertEquals(0, ruleShape(BookSource()))
        assertEquals(1, ruleShape(BookSource().apply { ruleSearch = io.legado.app.data.entities.rule.SearchRule() }))
    }

    @Test
    fun `source names ignore common separators and source suffix words`() {
        assertEquals(
            normalizeSourceName("示例书源-站点"),
            normalizeSourceName("示例书源站点"),
        )
        assertEquals(
            normalizeSourceName("Example_Source"),
            normalizeSourceName("example source"),
        )
    }
}
