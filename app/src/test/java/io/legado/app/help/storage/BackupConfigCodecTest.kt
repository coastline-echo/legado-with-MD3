package io.legado.app.help.storage

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackupConfigCodecTest {

    @Test
    fun `supported config types round trip with xml escaping`() {
        val source = linkedMapOf(
            "string" to "a&<\"value",
            "set" to linkedSetOf("one&two", "three<four"),
            "int" to 7,
            "long" to 8L,
            "float" to 9.5f,
            "boolean" to true,
        )
        val encoded = BackupConfigCodec.encode(source)

        assertEquals(source.keys, encoded.writtenKeys)
        assertTrue(encoded.xml.contains("a&amp;&lt;&quot;value"))
        assertEquals(source, BackupConfigCodec.decode(encoded.xml))
    }

    @Test
    fun `unsupported config type is reported and omitted`() {
        val encoded = BackupConfigCodec.encode(mapOf("supported" to "value", "unsupported" to listOf(1)))

        assertEquals(setOf("supported"), encoded.writtenKeys)
        assertEquals(setOf("unsupported"), encoded.unsupportedKeys)
        assertEquals(mapOf("supported" to "value"), BackupConfigCodec.decode(encoded.xml))
    }

    @Test
    fun `unknown xml type is reported`() {
        val result = BackupConfigCodec.decodeWithReport(
            "<map><double name=\"ratio\" value=\"1.5\" /></map>",
        )
        assertTrue(result.warnings.any { it.contains("不支持的配置类型") })
        assertTrue(result.values.isEmpty())
    }
}
