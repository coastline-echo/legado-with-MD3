package io.legado.app.help.storage

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = android.app.Application::class, sdk = [35])
class BackupSensitiveEnvelopeTest {

    @Test
    fun `only marked payload is identified as new sensitive envelope`() {
        assertTrue(BackupSensitiveEnvelope.isEncrypted("LEGADO_SENSITIVE_V1:ciphertext"))
        assertFalse(BackupSensitiveEnvelope.isEncrypted("legacy-ciphertext"))
        assertFalse(BackupSensitiveEnvelope.isEncrypted("[]"))
    }

    @Test
    fun `sensitive envelope round trips with explicit password`() {
        val plainText = "{\"serverToken\":\"secret\"}"
        val encrypted = BackupSensitiveEnvelope.encrypt(BackupAES("test-password"), plainText)

        assertEquals(plainText, BackupSensitiveEnvelope.decrypt(BackupAES("test-password"), encrypted))
    }

    @Test
    fun `wrong password fails without returning sensitive plaintext`() {
        val plainText = "{\"token\":\"secret\"}"
        val encrypted = BackupSensitiveEnvelope.encrypt(BackupAES("correct-password"), plainText)

        val result = runCatching {
            BackupSensitiveEnvelope.decrypt(BackupAES("wrong-password"), encrypted)
        }

        assertTrue(result.isFailure)
        assertFalse(result.getOrNull()?.contains("secret") == true)
    }
}
