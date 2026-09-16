package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupPasswordPolicyTest {

    @Test
    fun `empty password is allowed but marked weak`() {
        val policy = BackupPasswordPolicyResolver.resolve("")
        assertEquals(false, policy.passwordConfigured)
        assertTrue(policy.weakProtection)
        assertTrue(policy.warning!!.contains("弱保护"))
    }

    @Test
    fun `configured password is not weak`() {
        val policy = BackupPasswordPolicyResolver.resolve("secret")
        assertTrue(policy.passwordConfigured)
        assertEquals(false, policy.weakProtection)
    }

    @Test
    fun `explicit empty backup password stays independent and weak`() {
        val resolution = BackupPasswordResolver.resolve(
            explicitPassword = "",
            storedBackupPassword = null,
            applicationPassword = "application-secret",
        )
        assertEquals(BackupPasswordSource.INDEPENDENT_BACKUP_PASSWORD, resolution.source)
        assertEquals("", resolution.password)
        assertTrue(BackupPasswordPolicyResolver.resolve(resolution.password).weakProtection)
    }

    @Test
    fun `unset independent password falls back to application password`() {
        val resolution = BackupPasswordResolver.resolve(
            explicitPassword = null,
            storedBackupPassword = null,
            applicationPassword = "application-secret",
        )
        assertEquals(BackupPasswordSource.APPLICATION_PASSWORD, resolution.source)
        assertEquals("application-secret", resolution.password)
    }

    @Test
    fun `explicit empty password is distinguished from unset`() {
        val policy = BackupPasswordPolicyResolver.resolve("", explicitlyConfigured = true)
        assertEquals(false, policy.passwordConfigured)
        assertTrue(policy.weakProtection)
    }
}
