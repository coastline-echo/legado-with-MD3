package io.legado.app.help.storage

import io.legado.app.constant.PreferKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class NormalizeConfigMapTest {

    @Test
    fun `旧亮度设置恢复到独立键`() {
        val result = normalize(
            mapOf(
                PreferKey.showBrightnessView to "2",
                PreferKey.brightnessVwPos to true,
            )
        )
        assertEquals("2", result[PreferKey.readBrightnessMode])
        assertEquals("1", result[PreferKey.readBrightnessControlPosition])
        assertFalse(result.containsKey(PreferKey.showBrightnessView))
        assertFalse(result.containsKey(PreferKey.brightnessVwPos))
    }

    @Test
    fun `new sensitive envelope never falls back to plaintext after decryption failure`() {
        val report = normalizeConfigMapWithReport(
            mapOf(PreferKey.webDavPassword to "bad envelope"),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
            allowLegacyPlaintextFallback = false,
        )
        assertFalse(report.values.containsKey(PreferKey.webDavPassword))
        assertEquals(setOf(PreferKey.webDavPassword), report.decryptionFailedKeys)
    }

    @Test
    fun `兼容模式不把新版损坏信封误当明文密码`() {
        val report = normalizeConfigMapWithReport(
            mapOf(PreferKey.webDavPassword to "LEGADO_SENSITIVE_V1:broken"),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
            allowLegacyPlaintextFallback = true,
        )
        assertFalse(report.values.containsKey(PreferKey.webDavPassword))
        assertEquals(setOf(PreferKey.webDavPassword), report.decryptionFailedKeys)
    }

    @Test
    fun `legacy bookshelf key also restores independent group setting`() {
        val restored = normalize(mapOf(PreferKey.saveTabPosition to 17L))
        assertEquals(17L, restored[PreferKey.bookshelfSelectedGroupId])
        assertFalse(restored.containsKey(PreferKey.saveTabPosition))
        val independent = normalize(mapOf(
            PreferKey.saveTabPosition to 17L,
            PreferKey.bookshelfSelectedGroupId to 42L,
        ))
        assertEquals(42L, independent[PreferKey.bookshelfSelectedGroupId])
        assertFalse(independent.containsKey(PreferKey.saveTabPosition))
    }

    private fun normalize(
        map: Map<String, Any?>,
        keyIsNotIgnore: (String) -> Boolean = { true },
        decryptWebDavPassword: (String) -> String? = { null },
        hasLocalWebDavPassword: Boolean = false,
    ) = normalizeConfigMap(map, keyIsNotIgnore, decryptWebDavPassword, hasLocalWebDavPassword)

    @Test
    fun `基础类型原样保留且跳过 null`() {
        val result = normalize(
            mapOf(
                "int" to 1,
                "boolean" to true,
                "long" to 2L,
                "float" to 3f,
                "string" to "s",
                "nullKey" to null,
            )
        )
        assertEquals(
            mapOf("int" to 1, "boolean" to true, "long" to 2L, "float" to 3f, "string" to "s"),
            result
        )
    }

    @Test
    fun `Double 转为 Float`() {
        val result = normalize(mapOf("fontScale" to 1.5))
        assertEquals(1.5f, result["fontScale"])
    }

    @Test
    fun `忽略键被过滤`() {
        val result = normalize(
            mapOf("keep" to 1, "drop" to 2),
            keyIsNotIgnore = { it != "drop" },
        )
        assertEquals(mapOf("keep" to 1), result)
    }

    @Test
    fun `webDavPassword 解密成功用解密值`() {
        val result = normalize(
            mapOf(PreferKey.webDavPassword to "encrypted"),
            decryptWebDavPassword = { if (it == "encrypted") "plain" else null },
            hasLocalWebDavPassword = true,
        )
        assertEquals("plain", result[PreferKey.webDavPassword])
    }

    @Test
    fun `旧版兼容分支在本地无密码时保留明文且不误报解密失败`() {
        val report = normalizeConfigMapWithReport(
            mapOf(PreferKey.webDavPassword to "rawPassword"),
            keyIsNotIgnore = { true },
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = false,
            allowLegacyPlaintextFallback = true,
        )
        assertEquals("rawPassword", report.values[PreferKey.webDavPassword])
        assertFalse(PreferKey.webDavPassword in report.decryptionFailedKeys)
    }

    @Test
    fun `webDavPassword 解密失败且本地已有密码时丢弃`() {
        val result = normalize(
            mapOf(PreferKey.webDavPassword to "rawPassword"),
            decryptWebDavPassword = { null },
            hasLocalWebDavPassword = true,
        )
        assertFalse(result.containsKey(PreferKey.webDavPassword))
    }

    @Test
    fun `旧版 bookInfoBackgroundBlur 补写两个新背景键`() {
        val result = normalize(mapOf(PreferKey.bookInfoBackgroundBlur to "blur"))
        assertEquals("blur", result[PreferKey.bookInfoBackgroundBlur])
        assertEquals("blur", result[PreferKey.bookInfoNetworkCoverBackground])
        assertEquals("blur", result[PreferKey.bookInfoDefaultCoverBackground])
    }

    @Test
    fun `备份已含新背景键时不覆盖`() {
        val result = normalize(
            mapOf(
                PreferKey.bookInfoBackgroundBlur to "blur",
                PreferKey.bookInfoNetworkCoverBackground to "network",
            )
        )
        assertEquals("network", result[PreferKey.bookInfoNetworkCoverBackground])
        assertEquals("blur", result[PreferKey.bookInfoDefaultCoverBackground])
    }

    @Test
    fun `新背景键被忽略时不补写`() {
        val result = normalize(
            mapOf(PreferKey.bookInfoBackgroundBlur to "blur"),
            keyIsNotIgnore = { it != PreferKey.bookInfoDefaultCoverBackground },
        )
        assertEquals("blur", result[PreferKey.bookInfoNetworkCoverBackground])
        assertFalse(result.containsKey(PreferKey.bookInfoDefaultCoverBackground))
    }

    @Test
    fun `bookInfoBackgroundBlur 本身被忽略时不拆键`() {
        val result = normalize(
            mapOf(PreferKey.bookInfoBackgroundBlur to "blur"),
            keyIsNotIgnore = { it != PreferKey.bookInfoBackgroundBlur },
        )
        assertFalse(result.containsKey(PreferKey.bookInfoNetworkCoverBackground))
        assertFalse(result.containsKey(PreferKey.bookInfoDefaultCoverBackground))
    }
}
