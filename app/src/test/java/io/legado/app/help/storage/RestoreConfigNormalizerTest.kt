package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Test
import io.legado.app.constant.PreferKey

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

    @Test
    fun `encrypted password failure is reported and not restored for new backup`() {
        val result = normalizeConfigMapWithReport(
            map = mapOf(PreferKey.webDavPassword to "ciphertext"),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
            allowLegacyPlaintextFallback = false,
        )
        assertEquals(setOf(PreferKey.webDavPassword), result.decryptionFailedKeys)
        assertEquals(null, result.values[PreferKey.webDavPassword])
    }
}
