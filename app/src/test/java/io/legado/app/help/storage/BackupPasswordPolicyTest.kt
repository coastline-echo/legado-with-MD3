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
}
