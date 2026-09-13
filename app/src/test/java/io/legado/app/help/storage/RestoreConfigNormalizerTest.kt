package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test

class RestoreConfigNormalizerTest {

    @Test
    fun `set preference survives normalization`() {
        val values = linkedSetOf("home", "bookshelf")
        val result = normalizeConfigMap(
            map = mapOf("mainNavigationOrder" to values),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
        )
        assertEquals(values, result["mainNavigationOrder"])
    }
}
