package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
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

    @Test
    fun `unsupported config type is reported without dropping supported keys`() {
        val result = normalizeConfigMapWithReport(
            map = mapOf(
                "supported" to "value",
                "unsupported" to Any(),
            ),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
        )

        assertEquals("value", result.values["supported"])
        assertEquals(setOf("unsupported"), result.unsupportedKeys)
    }

    @Test
    fun `external entity xml is rejected`() {
        val maliciousXml = """
            <?xml version="1.0"?>
            <!DOCTYPE config [<!ENTITY secret SYSTEM "file:///etc/passwd">]>
            <map><string name="value">&secret;</string></map>
        """.trimIndent()

        val result = runCatching { BackupConfigCodec.decodeWithReport(maliciousXml) }

        assertTrue(result.isFailure)
    }

    @Test
    fun `malformed xml fails without returning partial values`() {
        val result = runCatching {
            BackupConfigCodec.decodeWithReport("<map><string name=\"broken\"></map>")
        }

        assertTrue(result.isFailure)
    }
}
